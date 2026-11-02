package app.browservault.vault.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import app.browservault.vault.ServerService
import app.browservault.vault.Vault
import app.browservault.vault.VaultStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val RANGES = listOf(
    Pair(1, "Ma"),
    Pair(7, "7 nap"),
    Pair(30, "30 nap"),
    Pair(365, "1 év"),
    Pair(0, "Minden")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultScreen() {
    val context = LocalContext.current
    val store = remember { Vault.store(context) }
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var range by remember { mutableStateOf(30) }
    var browserFilter by remember { mutableStateOf("") }
    var deviceFilter by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf(listOf<Hit>()) }
    var total by remember { mutableStateOf(0) }
    var records by remember { mutableStateOf(0) }
    var stats by remember { mutableStateOf<JSONObject?>(null) }
    var searching by remember { mutableStateOf(true) }
    var pending by remember { mutableStateOf<Hit?>(null) }
    var tick by remember { mutableStateOf(0) }

    val live = ServerService.running
    val activity = ServerService.lastActivity

    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(Unit) {
        if (!ServerService.running) ServerService.start(context)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(activity, tick) {
        stats = withContext(Dispatchers.IO) { store.stats() }
        records = withContext(Dispatchers.IO) { store.count() }
    }

    LaunchedEffect(query, range, browserFilter, deviceFilter, tick) {
        searching = true
        delay(180)
        val from = if (range > 0) System.currentTimeMillis() - range * 86400000L else 0L
        val result = withContext(Dispatchers.IO) {
            store.search(query, browserFilter, deviceFilter, from, 0L, 300, 0)
        }
        hits = result.items.map { toHit(it) }
        total = result.total
        searching = false
    }

    fun export() {
        scope.launch {
            val text = withContext(Dispatchers.IO) { store.exportText() }
            shareExport(context, text)
        }
    }

    if (pending != null) {
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Törlöd ezt a bejegyzést?") },
            text = {
                Text("A rekord azonosítója megmarad, ezért egy régi mentés visszaállítása sem hozza vissza.")
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = pending
                    pending = null
                    if (target != null) {
                        scope.launch {
                            withContext(Dispatchers.IO) { store.delete(target.id) }
                            tick++
                        }
                    }
                }) {
                    Text("Törlés")
                }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) { Text("Mégse") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("BrowserVault", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = { export() }) {
                        Icon(Icons.Filled.IosShare, contentDescription = "Archívum mentése")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 14.dp)
        ) {
            ServerPanel(records = records, modifier = Modifier.padding(top = 8.dp))

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Keresés címben és URL-ben…") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RANGES.forEach { entry ->
                    FilterChip(
                        selected = range == entry.first,
                        onClick = { range = entry.first },
                        label = { Text(entry.second) }
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DropdownChip(
                    label = if (browserFilter.isEmpty()) "Minden böngésző" else browserFilter,
                    options = pairKeys(stats, "browsers"),
                    selected = browserFilter,
                    onSelect = { browserFilter = it }
                )
                DropdownChip(
                    label = if (deviceFilter.isEmpty()) "Minden eszköz" else deviceFilter,
                    options = pairKeys(stats, "devices"),
                    selected = deviceFilter,
                    onSelect = { deviceFilter = it }
                )
            }

            Text(
                text = if (live) {
                    if (searching) "Keresés…" else total.toString() + " találat"
                } else {
                    "Indítsd el a vevőt a fogadáshoz."
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 6.dp)
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))

            if (hits.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (searching) "Keresés…" else "Nincs találat erre a szűrésre.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(hits, key = { it.id }) { hit ->
                        RecordRow(hit = hit, onDelete = { pending = hit })
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                    }
                }
            }
        }
    }
}

@Composable
private fun DropdownChip(
    label: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit
) {
    var open by remember { mutableStateOf(false) }

    Box {
        FilterChip(
            selected = selected.isNotEmpty(),
            onClick = { open = true },
            label = { Text(label) }
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Mind") },
                onClick = {
                    onSelect("")
                    open = false
                }
            )
            for (option in options) {
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onSelect(option)
                        open = false
                    }
                )
            }
        }
    }
}

private fun pairKeys(stats: JSONObject?, key: String): List<String> {
    val array = stats?.optJSONArray(key) ?: return emptyList()
    val result = ArrayList<String>()
    for (index in 0 until array.length()) {
        val pair = array.optJSONArray(index) ?: continue
        val value = pair.optString(0)
        if (value.isNotEmpty()) result.add(value)
    }
    return result
}

private fun toHit(record: JSONObject): Hit {
    val url = record.optString("url")
    return Hit(
        id = record.optString("id"),
        title = record.optString("title"),
        host = hostOf(url),
        browser = record.optString("browser"),
        device = record.optString("device"),
        stamp = stampOf(record.optLong("ts"))
    )
}

private fun hostOf(url: String): String {
    return try {
        val parsed = URI(url)
        val host = (parsed.host ?: url).removePrefix("www.")
        val path = parsed.path ?: ""
        if (path.isEmpty() || path == "/") host else host + path
    } catch (error: Exception) {
        url
    }
}

private fun stampOf(millis: Long): String {
    if (millis <= 0L) return ""
    val format = SimpleDateFormat("d MMM HH:mm", Locale("hu", "HU"))
    return format.format(Date(millis))
}

private fun shareExport(context: Context, content: String) {
    val dir = File(context.cacheDir, "export").apply { mkdirs() }
    val file = File(dir, "browservault-export.jsonl")
    file.writeText(content, Charsets.UTF_8)
    val uri = FileProvider.getUriForFile(context, "app.browservault.vault.files", file)
    val intent = Intent(Intent.ACTION_SEND)
    intent.type = "text/plain"
    intent.putExtra(Intent.EXTRA_STREAM, uri)
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, "Archívum mentése"))
}
