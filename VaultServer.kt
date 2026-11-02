package app.browservault.vault

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.time.Instant

/**
 * A bővítmény felé nyitott HTTP végpont. Ugyanaz az API, mint a Termux-os
 * vevőé: POST /ingest Bearer kulccsal, plusz keresés, statisztika, mentés.
 */
class VaultServer(
    private val store: VaultStore,
    private val config: VaultConfig,
    private val onRequest: (String) -> Unit
) {

    private var socket: ServerSocket? = null
    private var worker: Thread? = null

    @Volatile
    private var running = false

    fun start() {
        if (running) return
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress(config.port))
        socket = server
        running = true
        worker = Thread({
            while (running) {
                var client: Socket? = null
                try {
                    client = server.accept()
                } catch (error: Exception) {
                    if (!running) break
                    continue
                }
                val accepted = client
                if (accepted != null) {
                    Thread({ serve(accepted) }).start()
                }
            }
        }, "browservault-http")
        worker?.start()
    }

    fun stop() {
        running = false
        try {
            socket?.close()
        } catch (error: Exception) {
            /* már zárva */
        }
        socket = null
        worker = null
    }

    private fun serve(client: Socket) {
        try {
            client.soTimeout = 15000
            val input = BufferedInputStream(client.getInputStream())
            val head = readHead(input)
            if (head == null) {
                client.close()
                return
            }

            val lines = head.split("\r\n")
            val requestLine = lines.firstOrNull() ?: ""
            val parts = requestLine.split(" ")
            val method = if (parts.isEmpty()) "GET" else parts[0].uppercase()
            val target = if (parts.size > 1) parts[1] else "/"

            val headers = HashMap<String, String>()
            for (index in 1 until lines.size) {
                val line = lines[index]
                val colon = line.indexOf(':')
                if (colon > 0) {
                    headers[line.substring(0, colon).trim().lowercase()] =
                        line.substring(colon + 1).trim()
                }
            }

            val output = client.getOutputStream()
            val declared = headers["content-length"]?.toIntOrNull() ?: 0
            if (declared > MAX_BODY) {
                send(output, 413, "application/json", "{\"error\":\"tul nagy keres\"}")
                return
            }
            val body = if (declared > 0) readExactly(input, declared) else ""
            route(method, target, headers, body, output)
            onRequest(method + " " + target)
        } catch (error: Exception) {
            /* megszakadt kapcsolat */
        } finally {
            try {
                client.close()
            } catch (error: Exception) {
                /* már zárva */
            }
        }
    }

    private fun route(
        method: String,
        target: String,
        headers: Map<String, String>,
        body: String,
        output: OutputStream
    ) {
        val question = target.indexOf('?')
        val path = if (question >= 0) target.substring(0, question) else target
        val query = if (question >= 0) target.substring(question + 1) else ""
        val params = parseQuery(query)

        if (method == "OPTIONS") {
            send(output, 204, "text/plain", "")
            return
        }

        if (method == "GET" && (path == "/" || path == "/index.html")) {
            val page = "<!doctype html><meta charset=\"utf-8\"><title>BrowserVault</title>" +
                "<body style=\"background:#0b1220;color:#e6edf7;font:15px system-ui;padding:24px\">" +
                "<h1 style=\"color:#22d3ee;font-size:18px\">BrowserVault fut</h1>" +
                "<p>A keresőfelület az appban van. A bővítmény a /ingest végpontra küld, Bearer kulccsal.</p>" +
                "<p>Rekordok: " + store.count() + "</p>"
            send(output, 200, "text/html", page)
            return
        }

        if (method == "GET" && path == "/api/health") {
            val payload = JSONObject()
                .put("ok", true)
                .put("app", "BrowserVault")
                .put("version", BUILD_VERSION)
                .put("records", store.count())
            send(output, 200, "application/json", payload.toString())
            return
        }

        if (!authorized(headers)) {
            send(output, 401, "application/json", "{\"error\":\"ervenytelen kulcs\"}")
            return
        }

        if (method == "POST" && (path == "/ingest" || path == "/api/records")) {
            val payload = try {
                JSONObject(body)
            } catch (error: Exception) {
                send(output, 400, "application/json", "{\"error\":\"ervenytelen json\"}")
                return
            }
            send(output, 200, "application/json", store.ingest(payload).toString())
            return
        }

        if (method == "GET" && (path == "/api/search" || path == "/api/records")) {
            val result = store.search(
                params["q"] ?: "",
                params["browser"] ?: "",
                params["device"] ?: "",
                parseStamp(params["from"]),
                parseStamp(params["to"]),
                params["limit"]?.toIntOrNull() ?: 50,
                params["offset"]?.toIntOrNull() ?: 0
            )
            val items = JSONArray()
            for (item in result.items) items.put(item)
            val payload = JSONObject().put("total", result.total).put("items", items)
            send(output, 200, "application/json", payload.toString())
            return
        }

        if (method == "GET" && path == "/api/stats") {
            send(output, 200, "application/json", store.stats().toString())
            return
        }

        if (method == "GET" && path == "/api/export") {
            send(
                output,
                200,
                "application/x-ndjson",
                store.exportText(),
                "Content-Disposition: attachment; filename=browservault-export.jsonl\r\n"
            )
            return
        }

        if (method == "DELETE" && path.startsWith("/api/record/")) {
            val id = decode(path.substring("/api/record/".length))
            val payload = JSONObject().put("removed", store.delete(id))
            send(output, 200, "application/json", payload.toString())
            return
        }

        send(output, 404, "application/json", "{\"error\":\"nincs ilyen vegpont\"}")
    }

    private fun authorized(headers: Map<String, String>): Boolean {
        val header = headers["authorization"] ?: ""
        val token = if (header.startsWith("Bearer ")) header.substring(7) else ""
        return token.isNotEmpty() && token == config.token
    }

    private fun readHead(input: InputStream): String? {
        val builder = StringBuilder()
        var state = 0
        while (true) {
            val byte = input.read()
            if (byte == -1) return if (builder.isEmpty()) null else builder.toString()
            builder.append(byte.toChar())
            state = when {
                byte == 13 -> if (state == 2) 3 else 1
                byte == 10 -> when (state) {
                    1 -> 2
                    3 -> 4
                    else -> 0
                }
                else -> 0
            }
            if (state == 4) break
            if (builder.length > 65536) return null
        }
        return builder.toString()
    }

    private fun readExactly(input: InputStream, length: Int): String {
        val data = ByteArray(length)
        var read = 0
        while (read < length) {
            val count = input.read(data, read, length - read)
            if (count <= 0) break
            read += count
        }
        return String(data, 0, read, Charsets.UTF_8)
    }

    private fun send(
        output: OutputStream,
        status: Int,
        type: String,
        body: String,
        extra: String = ""
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 " + status + " " + reason(status) + "\r\n" +
            "Content-Type: " + type + "; charset=utf-8\r\n" +
            "Content-Length: " + bytes.size + "\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Access-Control-Allow-Headers: Authorization, Content-Type\r\n" +
            "Access-Control-Allow-Methods: GET, POST, DELETE, OPTIONS\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n" + extra + "\r\n"
        output.write(head.toByteArray(Charsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        204 -> "No Content"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        413 -> "Payload Too Large"
        else -> "Error"
    }

    private fun parseQuery(raw: String): Map<String, String> {
        val map = HashMap<String, String>()
        if (raw.isEmpty()) return map
        for (part in raw.split("&")) {
            if (part.isEmpty()) continue
            val equals = part.indexOf('=')
            val key = if (equals >= 0) part.substring(0, equals) else part
            val value = if (equals >= 0) part.substring(equals + 1) else ""
            map[decode(key)] = decode(value)
        }
        return map
    }

    private fun decode(value: String): String {
        return try {
            URLDecoder.decode(value, "UTF-8")
        } catch (error: Exception) {
            value
        }
    }

    private fun parseStamp(value: String?): Long {
        if (value.isNullOrEmpty()) return 0L
        val numeric = value.toLongOrNull()
        if (numeric != null) return numeric
        return try {
            Instant.parse(value).toEpochMilli()
        } catch (error: Exception) {
            0L
        }
    }

    companion object {
        private const val MAX_BODY = 8 * 1024 * 1024
        const val BUILD_VERSION = "1.0.0"
    }
}
