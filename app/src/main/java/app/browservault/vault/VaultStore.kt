package app.browservault.vault

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.time.Instant
import java.util.Locale

data class SearchPage(val total: Int, val items: List<JSONObject>)

/** Az archívum: JSONL fájlok az app privát tárolójában, memóriában indexelve. */
class VaultStore(context: Context) {

    private val dir = File(context.filesDir, "data").apply { mkdirs() }
    private val recordsFile = File(dir, "records.jsonl")
    private val tombstonesFile = File(dir, "deleted.jsonl")

    private val records = LinkedHashMap<String, JSONObject>()
    private val tombstones = HashSet<String>()

    init {
        load()
    }

    @Synchronized
    fun count(): Int = records.size

    private fun load() {
        for (line in readLines(recordsFile)) {
            try {
                val record = JSONObject(line)
                val id = record.optString("id")
                if (id.isNotEmpty()) records[id] = record
            } catch (error: Exception) {
                /* sérült sor kihagyva */
            }
        }
        for (line in readLines(tombstonesFile)) {
            try {
                val id = JSONObject(line).optString("id")
                if (id.isNotEmpty()) {
                    tombstones.add(id)
                    records.remove(id)
                }
            } catch (error: Exception) {
                /* sérült sor kihagyva */
            }
        }
        compact()
    }

    private fun readLines(file: File): List<String> {
        if (!file.exists()) return emptyList()
        return file.readLines(Charsets.UTF_8).filter { it.trim().isNotEmpty() }
    }

    private fun compact() {
        val builder = StringBuilder()
        for (record in records.values) builder.append(record.toString()).append('\n')
        recordsFile.writeText(builder.toString(), Charsets.UTF_8)
    }

    @Synchronized
    fun ingest(body: JSONObject): JSONObject {
        val list = body.optJSONArray("records") ?: JSONArray()
        val bodyDevice = body.optString("device")

        var accepted = 0
        var duplicates = 0
        var rejected = 0
        val fresh = ArrayList<String>()

        var index = 0
        while (index < list.length() && index < 2000) {
            val record = normalize(list.optJSONObject(index), bodyDevice)
            index++
            if (record == null) {
                rejected++
                continue
            }
            val id = record.getString("id")
            if (records.containsKey(id) || tombstones.contains(id)) {
                duplicates++
                continue
            }
            records[id] = record
            fresh.add(record.toString())
            accepted++
        }

        if (fresh.isNotEmpty()) {
            val builder = StringBuilder()
            for (line in fresh) builder.append(line).append('\n')
            recordsFile.appendText(builder.toString(), Charsets.UTF_8)
        }

        return JSONObject()
            .put("accepted", accepted)
            .put("duplicates", duplicates)
            .put("rejected", rejected)
            .put("total", records.size)
    }

    private fun normalize(input: JSONObject?, bodyDevice: String): JSONObject? {
        if (input == null) return null
        val url = input.optString("url")
        if (url.isEmpty()) return null

        val parsed = try {
            URI(url)
        } catch (error: Exception) {
            return null
        }
        val scheme = parsed.scheme ?: return null
        if (scheme != "http" && scheme != "https") return null

        val visited = parseTime(input.optString("visited_at")) ?: return null
        val browser = cut(input.optString("browser").ifEmpty { "ismeretlen" }, 40)
        val device = cut(input.optString("device").ifEmpty { bodyDevice.ifEmpty { "pc" } }, 60)
        val seconds = visited / 1000
        val provided = input.optString("id")
        val id = cut(
            provided.ifEmpty { hashId(device + "|" + browser + "|" + url + "|" + seconds) },
            80
        )

        return JSONObject()
            .put("id", id)
            .put("url", cut(url, 2048))
            .put("title", cut(input.optString("title"), 500))
            .put("visited_at", Instant.ofEpochMilli(visited).toString())
            .put("ts", visited)
            .put("browser", browser)
            .put("device", device)
            .put("source", if (input.optString("source") == "import") "import" else "live")
            .put("received_at", Instant.now().toString())
    }

    @Synchronized
    fun search(
        query: String,
        browser: String,
        device: String,
        from: Long,
        to: Long,
        limit: Int,
        offset: Int
    ): SearchPage {
        val needle = query.trim().lowercase(Locale.ROOT)
        val matched = ArrayList<JSONObject>()

        for (record in records.values) {
            val stamp = record.optLong("ts")
            if (browser.isNotEmpty() && record.optString("browser") != browser) continue
            if (device.isNotEmpty() && record.optString("device") != device) continue
            if (from > 0 && stamp < from) continue
            if (to > 0 && stamp > to) continue
            if (needle.isNotEmpty()) {
                val haystack = (record.optString("title") + " " + record.optString("url"))
                    .lowercase(Locale.ROOT)
                if (!haystack.contains(needle)) continue
            }
            matched.add(record)
        }

        matched.sortByDescending { it.optLong("ts") }
        val safeLimit = limit.coerceIn(1, 300)
        val safeOffset = offset.coerceAtLeast(0)
        val items = matched.drop(safeOffset).take(safeLimit)
        return SearchPage(matched.size, items)
    }

    @Synchronized
    fun stats(): JSONObject {
        var newest = 0L
        var oldest = 0L
        val browsers = HashMap<String, Int>()
        val devices = HashMap<String, Int>()

        for (record in records.values) {
            val stamp = record.optLong("ts")
            if (oldest == 0L || stamp < oldest) oldest = stamp
            if (stamp > newest) newest = stamp
            val browser = record.optString("browser")
            browsers[browser] = (browsers[browser] ?: 0) + 1
            val device = record.optString("device")
            devices[device] = (devices[device] ?: 0) + 1
        }

        return JSONObject()
            .put("total", records.size)
            .put("deleted", tombstones.size)
            .put("oldest", if (oldest > 0) Instant.ofEpochMilli(oldest).toString() else JSONObject.NULL)
            .put("newest", if (newest > 0) Instant.ofEpochMilli(newest).toString() else JSONObject.NULL)
            .put("browsers", pairs(browsers))
            .put("devices", pairs(devices))
    }

    private fun pairs(counts: Map<String, Int>): JSONArray {
        val array = JSONArray()
        for (entry in counts.entries.sortedByDescending { it.value }) {
            array.put(JSONArray().put(entry.key).put(entry.value))
        }
        return array
    }

    @Synchronized
    fun delete(id: String): Boolean {
        if (records.remove(id) == null) return false
        tombstones.add(id)
        val stone = JSONObject().put("id", id).put("at", Instant.now().toString())
        tombstonesFile.appendText(stone.toString() + "\n", Charsets.UTF_8)
        compact()
        return true
    }

    @Synchronized
    fun exportText(): String {
        val builder = StringBuilder()
        for (record in records.values) builder.append(record.toString()).append('\n')
        return builder.toString()
    }

    private fun parseTime(value: String): Long? {
        if (value.isEmpty()) return null
        val numeric = value.toLongOrNull()
        if (numeric != null) return numeric
        return try {
            Instant.parse(value).toEpochMilli()
        } catch (error: Exception) {
            null
        }
    }

    private fun hashId(value: String): String {
        var hash = 0x811c9dc5.toInt()
        for (character in value) {
            hash = hash xor character.code
            hash *= 0x01000193
        }
        return java.lang.Long.toString(hash.toLong() and 0xFFFFFFFFL, 36)
    }

    private fun cut(value: String, max: Int): String {
        return if (value.length <= max) value else value.substring(0, max)
    }
}
