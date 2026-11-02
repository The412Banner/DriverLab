package com.driverlab.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.driverlab.AppGraph
import com.driverlab.core.Fmt
import com.driverlab.core.RdCapture
import com.driverlab.core.RegNames
import com.driverlab.core.SessionMeta
import com.driverlab.ui.theme.MonoSmall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

@Composable
fun FilesScreen(nav: NavHostController) {
    val sessions by AppGraph.sessions.sessions.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var toDelete by remember { mutableStateOf<SessionMeta?>(null) }
    val sizes by produceState<Map<String, Long>>(emptyMap(), sessions) {
        value = withContext(Dispatchers.IO) { sessions.associate { it.id to AppGraph.sessions.sizeOf(AppGraph.sessions.dir(it.id)) } }
    }
    val groups = sessions.groupBy { it.driverName }
    Scaffold(topBar = { AppBar("Files", subtitle = "${sessions.size} sessions · ${Fmt.size(sizes.values.sum())} · copies in Download/DriverLab", onBack = { nav.popBackStack() }) }) { pad ->
        if (sessions.isEmpty()) {
            EmptyState(Icons.Filled.Folder, "Nothing saved yet", "Each test run saves its logs, results, captures and zips here, grouped by driver.", Modifier.padding(pad))
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((driver, list) in groups) {
                item(key = "h:$driver") {
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(Icons.Filled.Memory, MaterialTheme.colorScheme.primary, size = 32.dp)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(driver, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${list.size} sessions · ${Fmt.size(list.sumOf { sizes[it.id] ?: 0L })}", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                items(list, key = { it.id }) { s ->
                    var menu by remember { mutableStateOf(false) }
                    Card(
                        Modifier.fillMaxWidth().animateItem().clickable { nav.navigate(route("folder", s.id, ".")) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), border = cardLine(),
                    ) {
                        Row(Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.FolderOpen, null, tint = MaterialTheme.colorScheme.tertiary)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(Fmt.dateTime(s.startedAt), style = MaterialTheme.typography.bodyLarge)
                                Text("${s.label} · ${Fmt.count(s.counts.total.toLong())} tests · ${Fmt.size(sizes[s.id] ?: 0L)}",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                                DropdownMenu(menu, { menu = false }) {
                                    DropdownMenuItem(text = { Text("Share zips") }, leadingIcon = { Icon(Icons.Filled.Share, null) },
                                        onClick = { menu = false; scope.launch { shareSession(context, s) } })
                                    DropdownMenuItem(text = { Text("Save to Downloads") }, leadingIcon = { Icon(Icons.Filled.Download, null) },
                                        onClick = { menu = false; scope.launch { saveSessionToDownloads(context, s) } })
                                    DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Filled.Delete, null) },
                                        onClick = { menu = false; toDelete = s })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    toDelete?.let { s ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Delete this session?") },
            text = { Text("${s.driverName}, ${Fmt.dateTime(s.startedAt)}. Deletes everything it saved, including the copies in Download/DriverLab.") },
            confirmButton = {
                TextButton(onClick = {
                    if (!(AppGraph.runs.isActive && AppGraph.runs.live.value?.sessionId == s.id)) AppGraph.sessions.delete(s.id)
                    toDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } },
        )
    }
}

private fun iconFor(f: File): ImageVector = when {
    f.isDirectory -> Icons.Filled.Folder
    f.name.endsWith(".zip") -> Icons.Filled.Archive
    f.name.endsWith(".rd.gz") || f.name.endsWith(".rd") -> Icons.Filled.Memory
    f.name.endsWith(".log") || f.name.endsWith(".txt") || f.name.endsWith(".tsv") || f.name.endsWith(".qpa") || f.name.endsWith(".json") -> Icons.Filled.Description
    else -> Icons.Filled.InsertDriveFile
}

/** Shared file actions: share, save a copy (system picker), copy to Downloads, delete. */
@Composable
private fun rememberFileActions(id: String, onDeleted: () -> Unit): (File) -> FileActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<File?>(null) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val f = pending
        if (uri != null && f != null) scope.launch {
            runCatching { AppGraph.sessions.copyTo(f, uri) }
                .onSuccess { Toast.makeText(context, "Saved ${f.name}", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(context, "Could not save: ${it.message}", Toast.LENGTH_LONG).show() }
        }
        pending = null
    }
    val meta = AppGraph.sessions.get(id)
    return { f ->
        FileActions(
            share = { context.startActivity(AppGraph.sessions.shareIntent(listOf(f))) },
            saveCopy = { pending = f; saver.launch(f.name) },
            toDownloads = {
                scope.launch {
                    val day = Fmt.day(meta?.startedAt ?: System.currentTimeMillis())
                    val r = withContext(Dispatchers.IO) { runCatching { AppGraph.sessions.copyToDownloads(f, day) } }
                    r.onSuccess { uri ->
                        meta?.let { AppGraph.sessions.writeMeta(it.copy(exports = it.exports + uri)) }
                        Toast.makeText(context, "Copied to Download/DriverLab/$day", Toast.LENGTH_SHORT).show()
                    }.onFailure { Toast.makeText(context, "Could not copy: ${it.message}", Toast.LENGTH_LONG).show() }
                }
            },
            delete = { if (f.deleteRecursively()) onDeleted() },
        )
    }
}

class FileActions(val share: () -> Unit, val saveCopy: () -> Unit, val toDownloads: () -> Unit, val delete: () -> Unit)

@Composable
fun FolderScreen(nav: NavHostController, id: String, path: String) {
    val base = AppGraph.sessions.dir(id)
    val dir = if (path == "." || path.isEmpty()) base else File(base, path)
    var version by remember { mutableIntStateOf(0) }
    val actionsFor = rememberFileActions(id) { version++ }
    var confirm by remember { mutableStateOf<File?>(null) }
    val entries by produceState<List<File>?>(null, dir, version) {
        value = withContext(Dispatchers.IO) {
            dir.listFiles()?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name })) ?: emptyList()
        }
    }
    val meta = AppGraph.sessions.get(id)
    val title = if (dir == base) (meta?.driverName ?: id) else dir.name
    Scaffold(topBar = {
        AppBar(title, subtitle = if (dir == base) meta?.let { Fmt.dateTime(it.startedAt) } else dir.relativeTo(base).path, onBack = { nav.popBackStack() })
    }) { pad ->
        val list = entries
        if (list == null) { LinearProgressIndicator(Modifier.fillMaxWidth().padding(pad)); return@Scaffold }
        if (list.isEmpty()) { EmptyState(Icons.Filled.Folder, "Empty folder", "", Modifier.padding(pad)); return@Scaffold }
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(vertical = 8.dp)) {
            items(list, key = { it.name }) { f ->
                var menu by remember { mutableStateOf(false) }
                val rel = f.relativeTo(base).path
                Row(
                    Modifier.fillMaxWidth().animateItem().clickable {
                        if (f.isDirectory) nav.navigate(route("folder", id, rel)) else nav.navigate(route("file", id, rel))
                    }.padding(start = 16.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconBadge(iconFor(f), if (f.isDirectory) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary, size = 36.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(f.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (f.isDirectory) "${f.list()?.size ?: 0} items" else "${Fmt.size(f.length())} · ${Fmt.dateTime(f.lastModified())}",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!f.isDirectory) Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                        val a = actionsFor(f)
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("Share") }, leadingIcon = { Icon(Icons.Filled.Share, null) }, onClick = { menu = false; a.share() })
                            DropdownMenuItem(text = { Text("Save a copy…") }, leadingIcon = { Icon(Icons.Filled.Save, null) }, onClick = { menu = false; a.saveCopy() })
                            DropdownMenuItem(text = { Text("Copy to Downloads") }, leadingIcon = { Icon(Icons.Filled.ContentCopy, null) }, onClick = { menu = false; a.toDownloads() })
                            DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Filled.Delete, null) }, onClick = { menu = false; confirm = f })
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(start = 64.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }
        }
    }
    confirm?.let { f ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Delete ${f.name}?") },
            text = { Text("This file is removed from the app. Copies already saved elsewhere are kept.") },
            confirmButton = { TextButton(onClick = { actionsFor(f).delete(); confirm = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

@Composable
fun FileViewerScreen(nav: NavHostController, id: String, path: String) {
    val context = LocalContext.current
    val f = File(AppGraph.sessions.dir(id), path)
    val a = rememberFileActions(id) { nav.popBackStack() }(f)
    var confirm by remember { mutableStateOf(false) }
    Scaffold(topBar = {
        AppBar(f.name, subtitle = Fmt.size(f.length()), onBack = { nav.popBackStack() }, actions = {
            IconButton(onClick = a.share) { Icon(Icons.Filled.Share, "Share") }
            IconButton(onClick = a.saveCopy) { Icon(Icons.Filled.Save, "Save a copy") }
            IconButton(onClick = a.toDownloads) { Icon(Icons.Filled.Download, "Copy to Downloads") }
            IconButton(onClick = { confirm = true }) { Icon(Icons.Filled.Delete, "Delete") }
        })
    }) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            val n = f.name
            when {
                n.endsWith(".rd.gz") || n.endsWith(".rd") -> {
                    val s by produceState<RdCapture.Summary?>(null, f) { value = withContext(Dispatchers.IO) { RdCapture.summary(f) } }
                    val sum = s
                    if (sum == null) LinearProgressIndicator(Modifier.fillMaxWidth()) else {
                        val names = remember(sum.gen) { RegNames.get(context, sum.gen) }
                        val text = buildString {
                            append("chip ${Fmt.hex(sum.chipId)} (${sum.gen}), ${sum.submits} submits, ${sum.regs.size} registers")
                            if (sum.missingIbs > 0) append(", ${sum.missingIbs} command buffers not in capture")
                            sum.error?.let { append(", $it") }
                            append("\n\n")
                            for (k in sum.regs.keys.sorted()) {
                                val vals = sum.regs[k].orEmpty().joinToString(" ") { (v, c) -> Fmt.hex(v) + if (c > 1) "×$c" else "" }
                                append((names[k] ?: "REG_" + k.toString(16)).padEnd(36)).append(Fmt.hex(k.toLong(), 5)).append("  ").append(vals).append('\n')
                            }
                        }
                        TextPane(text)
                    }
                }
                n.endsWith(".zip") -> {
                    val entries by produceState<List<Pair<String, Long>>?>(null, f) {
                        value = withContext(Dispatchers.IO) {
                            runCatching { ZipFile(f).use { z -> z.entries().toList().map { it.name to it.size } } }.getOrDefault(emptyList())
                        }
                    }
                    val e = entries
                    if (e == null) LinearProgressIndicator(Modifier.fillMaxWidth()) else LazyColumn(contentPadding = PaddingValues(16.dp)) {
                        item { Text("${e.size} files", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) }
                        items(e) { (name, size) ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Text(name, style = MonoSmall, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(Fmt.size(size), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                else -> {
                    val text by produceState<String?>(null, f) {
                        value = withContext(Dispatchers.IO) {
                            runCatching {
                                val limit = 2L shl 20
                                val bytes = f.inputStream().use { s -> s.readNBytesCompat(limit.toInt()) }
                                val t = String(bytes, Charsets.UTF_8)
                                if (f.length() > limit) "$t\n\n… showing the first 2 MB of ${Fmt.size(f.length())}. Share or save the file to read all of it." else t
                            }.getOrElse { "Cannot open: ${it.message}" }
                        }
                    }
                    val t = text
                    if (t == null) LinearProgressIndicator(Modifier.fillMaxWidth()) else TextPane(t)
                }
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Delete ${f.name}?") },
            text = { Text("This file is removed from the app. Copies already saved elsewhere are kept.") },
            confirmButton = { TextButton(onClick = { confirm = false; a.delete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

private fun java.io.InputStream.readNBytesCompat(n: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream(minOf(n, 1 shl 20))
    val buf = ByteArray(1 shl 16)
    var left = n
    while (left > 0) {
        val r = read(buf, 0, minOf(buf.size, left))
        if (r <= 0) break
        out.write(buf, 0, r)
        left -= r
    }
    return out.toByteArray()
}
