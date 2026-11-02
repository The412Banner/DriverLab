package com.driverlab.ui

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.driverlab.AppGraph
import com.driverlab.core.Bucket
import com.driverlab.core.Counts
import com.driverlab.core.Fmt
import com.driverlab.core.isGles
import com.driverlab.core.FlagCheck
import com.driverlab.core.TestList
import com.driverlab.core.Qpa
import com.driverlab.core.RdCapture
import com.driverlab.core.RegNames
import com.driverlab.core.SessionMeta
import com.driverlab.core.SessionState
import com.driverlab.core.SessionStore
import com.driverlab.core.TestResult
import com.driverlab.core.TestStatus
import com.driverlab.ui.theme.LocalStatusColors
import com.driverlab.ui.theme.Mono
import com.driverlab.ui.theme.MonoSmall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ResultsScreen(nav: NavHostController) {
    val sessions by AppGraph.sessions.sessions.collectAsStateWithLifecycle()
    var driverFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var problemsOnly by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var comparing by rememberSaveable { mutableStateOf(false) }
    var picked by remember { mutableStateOf(listOf<String>()) }
    val drivers = sessions.map { it.driverName }.distinct()
    val shown = sessions.filter {
        (driverFilter == null || it.driverName == driverFilter) &&
            (!problemsOnly || it.counts.failed + it.counts.crashed > 0) && matchesSession(it, query)
    }
    val byDay = shown.groupBy { dayLabel(it.startedAt) }
    Scaffold(
        topBar = {
            AppBar("Results", subtitle = "${sessions.size} sessions", onBack = { nav.popBackStack() }, actions = {
                IconButton(onClick = { nav.navigate("speed") }) { Icon(Icons.Filled.Speed, "Speed tests") }
                IconButton(onClick = { nav.navigate("files") }) { Icon(Icons.Filled.Folder, "Files") }
                IconButton(onClick = { comparing = !comparing; picked = emptyList() }) { Icon(Icons.Filled.SwapHoriz, "Compare two sessions") }
            })
        },
        floatingActionButton = {
            if (comparing && picked.size == 2) {
                ExtendedFloatingActionButton(
                    onClick = { nav.navigate(route("compare", picked[0], picked[1])); comparing = false; picked = emptyList() },
                    icon = { Icon(Icons.Filled.SwapHoriz, null) },
                    text = { Text("Compare") },
                )
            }
        },
    ) { pad ->
        if (sessions.isEmpty()) {
            EmptyState(Icons.Filled.Assessment, "No results yet", "Run some tests and the results show up here.", Modifier.padding(pad))
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(top = 8.dp), placeholder = { Text("Search driver, GPU, tests, date") },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Clear, "Clear") } },
                    singleLine = true, shape = RoundedCornerShape(6.dp))
            }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(problemsOnly, { problemsOnly = !problemsOnly }, label = { Text("With problems") }, leadingIcon = { StatusDot(Bucket.FAILED) })
                    if (drivers.size > 1) {
                        FilterChip(driverFilter == null, { driverFilter = null }, label = { Text("All drivers") })
                        for (d in drivers) FilterChip(driverFilter == d, { driverFilter = if (driverFilter == d) null else d }, label = { Text(d, maxLines = 1) })
                    }
                }
            }
            if (comparing) item {
                Text("Pick two sessions to compare (${picked.size}/2)", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
            if (shown.isEmpty()) item {
                Text("No sessions match.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            byDay.forEach { (day, list) ->
                stickyHeader(key = "day-$day") {
                    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxWidth()) {
                        Text("$day · ${list.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(vertical = 6.dp))
                    }
                }
                items(list, key = { it.id }) { s ->
                    Row(Modifier.animateItem(), verticalAlignment = Alignment.CenterVertically) {
                        if (comparing) {
                            Checkbox(checked = s.id in picked, onCheckedChange = { on ->
                                picked = if (on) (picked + s.id).takeLast(2) else picked - s.id
                            })
                        }
                        SessionCard(s, Modifier.weight(1f)) {
                            if (comparing) picked = if (s.id in picked) picked - s.id else (picked + s.id).takeLast(2)
                            else nav.navigate(route("session", s.id))
                        }
                    }
                }
            }
        }
    }
}

/** Shares the session's zip bundles (builds them if missing). */
suspend fun shareSession(context: Context, m: SessionMeta) {
    val exp = File(AppGraph.sessions.dir(m.id), "export")
    val files = exp.listFiles()?.filter { it.name.endsWith(".zip") }?.takeIf { it.isNotEmpty() }
        ?: AppGraph.sessions.buildBundles(m).filter { it.name.endsWith(".zip") }
    context.startActivity(AppGraph.sessions.shareIntent(files))
}

suspend fun saveSessionToDownloads(context: Context, m: SessionMeta) {
    val files = AppGraph.sessions.buildBundles(m)
    val uris = AppGraph.sessions.exportToDownloads(files, Fmt.day(m.startedAt))
    AppGraph.sessions.writeMeta(m.copy(exports = m.exports + uris))
    withContext(Dispatchers.Main) {
        Toast.makeText(context, "Saved ${uris.size} files to Download/DriverLab/${Fmt.day(m.startedAt)}", Toast.LENGTH_LONG).show()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SessionScreen(nav: NavHostController, id: String) {
    val sessions by AppGraph.sessions.sessions.collectAsStateWithLifecycle()
    val live by AppGraph.runs.live.collectAsStateWithLifecycle()
    val meta = sessions.firstOrNull { it.id == id } ?: AppGraph.sessions.get(id)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var sortName by rememberSaveable { mutableStateOf(ResultSort.PROBLEMS.name) }
    var grouped by rememberSaveable { mutableStateOf(true) }
    var collapsed by remember { mutableStateOf(setOf<String>()) }
    var sortMenu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var listDialog by remember { mutableStateOf(false) }
    val running = live?.sessionId == id && live?.active == true
    val loaded by produceState<SessionStore.Loaded?>(null, id, meta?.counts, running) {
        value = AppGraph.sessions.loadResults(id)
    }
    if (meta == null) {
        Scaffold(topBar = { AppBar("Session", onBack = { nav.popBackStack() }) }) { pad ->
            EmptyState(Icons.Filled.Assessment, "Session not found", "It may have been deleted.", Modifier.padding(pad))
        }
        return
    }
    Scaffold(
        topBar = {
            AppBar(meta.driverName, subtitle = Fmt.dateTime(meta.startedAt), onBack = { nav.popBackStack() }, actions = {
                if (meta.pairId.isNotEmpty() && AppGraph.sessions.get(meta.pairId) != null) {
                    IconButton(onClick = {
                        val other = AppGraph.sessions.get(meta.pairId)!!
                        val (a, b) = if (other.startedAt < meta.startedAt) other.id to meta.id else meta.id to other.id
                        nav.navigate(route("compare", a, b))
                    }) { Icon(Icons.AutoMirrored.Filled.CompareArrows, "Compare with the other driver") }
                }
                IconButton(onClick = { listDialog = true }, enabled = !running) { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, "Make a test list") }
                IconButton(onClick = { nav.navigate(route("folder", id, ".")) }) { Icon(Icons.Filled.Folder, "Files") }
                IconButton(onClick = { scope.launch { shareSession(context, meta) } }) { Icon(Icons.Filled.Share, "Share") }
                IconButton(onClick = { scope.launch { saveSessionToDownloads(context, meta) } }) { Icon(Icons.Filled.Download, "Save to Downloads") }
                IconButton(onClick = { confirmDelete = true }, enabled = !running) { Icon(Icons.Filled.Delete, "Delete") }
            })
        },
    ) { pad ->
        val results = loaded?.results.orEmpty()
        val sort = ResultSort.valueOf(sortName)
        // Filtering and sorting run off the main thread; the short wait also smooths typing.
        val organized by produceState<Organized?>(null, results, filter, query, sort, grouped) {
            delay(120)
            value = withContext(Dispatchers.Default) { organize(results, filter, query, sort, grouped) }
        }
        val org = organized
        if (org != null) {
            ResultsNav.sessionId = id
            ResultsNav.names = org.flat.map { it.name }
        }
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item { SessionHeader(meta, running) }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(filter == null, { filter = null }, label = { Text("All ${Fmt.count(meta.counts.total.toLong())}") })
                    for (b in listOf(Bucket.FAILED, Bucket.CRASHED, Bucket.WARNING, Bucket.SKIPPED, Bucket.PASSED)) {
                        val n = meta.counts[b]
                        if (n > 0) FilterChip(filter == b.name, { filter = if (filter == b.name) null else b.name },
                            label = { Text("${b.label} ${Fmt.count(n.toLong())}") }, leadingIcon = { StatusDot(b) })
                    }
                    val pick: (String) -> Unit = { key -> filter = if (filter == key) null else key }
                    ExtraChip(filter, ExtraFilter.FLAKY, "Sometimes fails", meta.flaky, pick)
                    ExtraChip(filter, ExtraFilter.GPU_ERRORS, "GPU errors", meta.gpuErrorTests, pick)
                    val known = results.count { it.mesaNote.isNotEmpty() }
                    if (known > 0) {
                        ExtraChip(filter, ExtraFilter.NEW_PROBLEMS, "Not known to Mesa", results.count { keep(it, ExtraFilter.NEW_PROBLEMS) }, pick)
                        ExtraChip(filter, ExtraFilter.MESA_KNOWN, "Known to Mesa", known, pick)
                    }
                }
            }
            item {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text("Search names and messages") },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Clear, "Clear") } },
                    singleLine = true, shape = RoundedCornerShape(6.dp))
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(grouped, { grouped = true }, label = { Text("By group") })
                    FilterChip(!grouped, { grouped = false }, label = { Text("List") })
                    Spacer(Modifier.weight(1f))
                    Box {
                        TextButton(onClick = { sortMenu = true }) {
                            Icon(Icons.AutoMirrored.Filled.Sort, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(sort.label)
                        }
                        DropdownMenu(sortMenu, { sortMenu = false }) {
                            ResultSort.entries.forEach { o -> DropdownMenuItem(text = { Text(o.label) }, onClick = { sortName = o.name; sortMenu = false }) }
                        }
                    }
                }
            }
            if (loaded == null || org == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (loaded?.truncated == true) item {
                Text("Large session: showing every problem and the first 50,000 passes. The full list is in results.tsv.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (org != null && org.flat.isEmpty() && loaded != null) item {
                Text("No tests match.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val groups = org?.groups
            if (org != null && groups == null) {
                items(org.flat.take(20_000), key = { it.name + it.finishedAt }) { r ->
                    ResultRow(r) { nav.navigate(route("test", id, r.name)) }
                }
            }
            if (groups != null) {
                var budget = 20_000
                groups.forEach { g ->
                    val open = g.key !in collapsed
                    stickyHeader(key = "g-" + g.key) {
                        GroupHeader(g, open) { collapsed = if (open) collapsed + g.key else collapsed - g.key }
                    }
                    if (open && budget > 0) {
                        val part = g.items.take(budget)
                        budget -= part.size
                        items(part, key = { it.name + it.finishedAt }) { r ->
                            ResultRow(r) { nav.navigate(route("test", id, r.name)) }
                        }
                    }
                }
            }
        }
    }
    if (listDialog) TestListDialog(nav, meta) { listDialog = false }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this session?") },
            text = { Text("Deletes its results, logs, captures and the copies saved in Download/DriverLab.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; AppGraph.sessions.delete(id); nav.popBackStack() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

/**
 * Turns a session's results into a test list: chosen outcomes (failed and crashed by default)
 * become the selection for a new run, or a caselist.txt file.
 */
@Composable
private fun TestListDialog(nav: NavHostController, meta: SessionMeta, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val order = listOf(Bucket.FAILED, Bucket.CRASHED, Bucket.WARNING, Bucket.SKIPPED, Bucket.PASSED)
    var picked by remember { mutableStateOf(setOf(Bucket.FAILED, Bucket.CRASHED)) }
    var busy by remember { mutableStateOf(false) }
    val chosen = order.filter { it in picked }.sumOf { meta.counts[it] }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            try {
                val chosenNow = picked
                val n = withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")!!.bufferedWriter().use { w ->
                        TestList.write(w) { sink -> AppGraph.sessions.forEachNameWith(meta.id, chosenNow, sink) }
                    }
                }
                Toast.makeText(context, "Saved ${Fmt.count(n)} test names.", Toast.LENGTH_SHORT).show()
                onDismiss()
            } catch (e: Exception) {
                Toast.makeText(context, "Could not save the list: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                busy = false
            }
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Make a test list") },
        text = {
            Column {
                Text("Tests from this run with these results:", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                for (b in order) {
                    val n = meta.counts[b]
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = n > 0) { picked = if (b in picked) picked - b else picked + b },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = b in picked && n > 0, onCheckedChange = null, enabled = n > 0)
                        Spacer(Modifier.width(8.dp))
                        Text("${b.label} (${Fmt.count(n.toLong())})")
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text("Select them to run again (for example on another driver), or save them as caselist.txt.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        },
        confirmButton = {
            TextButton(enabled = chosen > 0 && !busy, onClick = {
                scope.launch {
                    busy = true
                    try {
                        val chosenNow = picked
                        val label = order.filter { it in chosenNow }.joinToString(" + ") { it.label.lowercase() } + " · " + meta.driverName
                        val r = AppGraph.selection.loadList(label.replaceFirstChar { it.uppercase() }, context.cacheDir) { sink ->
                            AppGraph.sessions.forEachNameWith(meta.id, chosenNow, sink)
                        }
                        val note = if (r.missingCount == 0L) "" else " (${Fmt.count(r.missingCount)} not in this CTS)"
                        Toast.makeText(context, "Selected ${Fmt.count(r.tests)} tests$note.", Toast.LENGTH_SHORT).show()
                        onDismiss()
                        nav.goTab("tests", fresh = true)
                    } finally {
                        busy = false
                    }
                }
            }) { Text("Select to run") }
        },
        dismissButton = {
            Row {
                TextButton(enabled = chosen > 0 && !busy, onClick = { save.launch("caselist.txt") }) { Text("Save file") }
                TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun ExtraChip(filter: String?, key: String, label: String, n: Int, pick: (String) -> Unit) {
    if (n > 0) FilterChip(filter == key, { pick(key) }, label = { Text("$label ${Fmt.count(n.toLong())}") })
}

@Composable
fun SessionHeader(m: SessionMeta, running: Boolean) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), border = cardLine()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            ProgressRing(m.counts, 1f, running, Modifier.size(96.dp), stroke = 10.dp) {
                Text(if (m.counts.total == 0) "—" else "${m.counts.passed * 100 / m.counts.total.coerceAtLeast(1)}%", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(m.label, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "${Fmt.count(m.counts.total.toLong())} of ${Fmt.count(m.totalTests)} tests · " + when {
                        running -> "running"
                        m.state == SessionState.RUNNING -> "interrupted"
                        else -> m.state.name.lowercase() + " in " + Fmt.duration((m.finishedAt - m.startedAt).coerceAtLeast(0))
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                CountsRow(m.counts)
                if (m.record) Text("GPU commands recorded", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                if (m.repeat > 1) Text(
                    "Each test ran ${m.repeat} times · " + if (m.flaky > 0) "${m.flaky} had different results" else "same result every time",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary,
                )
                when {
                    m.gpuCheck == "on" -> Text(
                        if (m.gpuErrorLines == 0) "GPU error check: no GPU errors" else "GPU errors: ${m.gpuErrorLines} kernel log lines during ${m.gpuErrorTests} tests",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (m.gpuErrorLines == 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    )
                    m.gpuCheck.isNotEmpty() -> Text("GPU error check did not run: ${m.gpuCheck}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (m.message.isNotEmpty()) Text(m.message, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, maxLines = 4)
            }
        }
    }
}

@Composable
private fun GroupHeader(g: ResultGroup, open: Boolean, onToggle: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).clickable(onClick = onToggle).padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, if (open) "Collapse" else "Expand", Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text(g.key, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            for (b in listOf(Bucket.FAILED, Bucket.CRASHED, Bucket.WARNING, Bucket.SKIPPED, Bucket.PASSED)) {
                val n = g.counts[b]
                if (n > 0) {
                    Spacer(Modifier.width(8.dp)); StatusDot(b); Spacer(Modifier.width(3.dp))
                    Text(Fmt.count(n.toLong()), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
fun ResultRow(r: TestResult, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(r.status.bucket)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(r.name.removePrefix("dEQP-VK."), fontFamily = Mono, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (r.message.isNotEmpty() && r.status != TestStatus.PASS) {
                Text(r.message, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            val tags = listOfNotNull(
                if (r.flaky) "Sometimes fails" else null,
                if (r.gpuErrors > 0) "GPU errors: ${r.gpuErrors}" else null,
            )
            if (tags.isNotEmpty()) Text(tags.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            if (r.mesaNote.isNotEmpty()) Text(r.mesaNote, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            StatusPill(r.status)
            Text(Fmt.duration(r.durationMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private suspend fun findResult(id: String, name: String): TestResult? = AppGraph.sessions.resultFor(id, name)

private fun stdoutSlice(id: String, r: TestResult): String {
    val out = File(AppGraph.sessions.dir(id), "output/batch-%05d.txt".format(r.batch))
    if (!out.exists()) return ""
    val sb = StringBuilder()
    var inside = false
    out.bufferedReader().useLines { seq ->
        for (l in seq) {
            if (l == "Test case '${r.name}'..") { inside = true; sb.append(l).append('\n'); continue }
            if (inside) {
                if (l.startsWith("Test case '")) break
                if (sb.length < 200_000) sb.append(l).append('\n')
            }
        }
    }
    return sb.toString()
}

@Composable
fun TestDetailScreen(nav: NavHostController, id: String, name: String) {
    val context = LocalContext.current
    val result by produceState<TestResult?>(null, id, name) { value = findResult(id, name) }
    var tab by rememberSaveable { mutableStateOf(0) }
    val dir = AppGraph.sessions.dir(id)
    val r = result
    val testLog = File(dir, "tests/${SessionStore.fileBase(name)}${if ((r?.round ?: 1) > 1) "~r${r?.round}" else ""}.log")
    val runs by produceState<List<TestResult>>(emptyList(), id, name) { value = AppGraph.sessions.runsOf(id, name) }
    val gpuLines by produceState<List<String>>(emptyList(), id, name) {
        value = if ((AppGraph.sessions.get(id)?.gpuErrorLines ?: 0) > 0) AppGraph.sessions.gpuErrors(id)[name].orEmpty() else emptyList()
    }
    val capture = r?.capture?.let { File(dir, it) }?.takeIf { it.exists() }
    Scaffold(topBar = {
        AppBar(name.substringAfterLast('.'), subtitle = name.removePrefix("dEQP-VK."), onBack = { nav.popBackStack() }, actions = {
            val (prev, next) = remember(id, name) { ResultsNav.neighbours(id, name) }
            fun go(other: String) = nav.navigate(route("test", id, other)) { popUpTo("test/{id}/{name}") { inclusive = true } }
            if (prev != null || next != null) {
                IconButton(onClick = { prev?.let(::go) }, enabled = prev != null) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous test") }
                IconButton(onClick = { next?.let(::go) }, enabled = next != null) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next test") }
            }
            val files = listOfNotNull(testLog.takeIf { it.exists() }, capture)
            if (files.isNotEmpty()) IconButton(onClick = { context.startActivity(AppGraph.sessions.shareIntent(files)) }) { Icon(Icons.Filled.Share, "Share") }
        })
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (r == null) { LinearProgressIndicator(Modifier.fillMaxWidth()); return@Column }
            Card(Modifier.padding(16.dp).fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), border = cardLine()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusPill(r.status)
                        Spacer(Modifier.width(10.dp))
                        Text(Fmt.duration(r.durationMs), style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.weight(1f))
                        Text(Fmt.time(r.finishedAt), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (r.message.isNotEmpty()) { Spacer(Modifier.height(8.dp)); Text(r.message, style = MaterialTheme.typography.bodyMedium) }
                    if (runs.size > 1) {
                        Spacer(Modifier.height(6.dp))
                        Text("Runs: " + runs.joinToString("  ") { "${it.round}: ${it.status.label}" },
                            style = MaterialTheme.typography.bodySmall, color = if (r.flaky) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        if (r.round > 1) Text("The logs below are from run ${r.round}.", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (r.mesaNote.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(r.mesaNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                    }
                    if (gpuLines.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text("GPU errors in the kernel log while this test ran:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        SelectionContainer { Text(gpuLines.take(8).joinToString("\n") + if (gpuLines.size > 8) "\n… ${gpuLines.size - 8} more" else "", style = MonoSmall) }
                    }
                    Spacer(Modifier.height(6.dp))
                    SelectionContainer { Text(name, style = MonoSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    val runDriver = AppGraph.sessions.get(id)?.let { AppGraph.drivers.get(it.driverId) }
                    if (runDriver != null && FlagCheck.isTurnip(runDriver) && !isGles(name) &&
                        (r.status.bucket == Bucket.FAILED || r.status.bucket == Bucket.CRASHED)
                    ) {
                        Spacer(Modifier.height(8.dp))
                        FilledTonalButton(shape = BtnShape, onClick = { nav.navigate(route("flags", id, name)) }) { Text("Find a setting that fixes it") }
                    }
                }
            }
            val tabs = listOfNotNull("Log", "CTS log", if (capture != null) "Registers" else null)
            TabRow(selectedTabIndex = tab.coerceIn(0, tabs.size - 1)) {
                tabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
            }
            when (tabs.getOrNull(tab)) {
                "Log" -> {
                    val text by produceState("Loading…", id, name) {
                        value = withContext(Dispatchers.IO) {
                            if (testLog.exists()) testLog.readText().take(400_000) else stdoutSlice(id, r).ifEmpty { "No output was saved for this test." }
                        }
                    }
                    TextPane(text)
                }
                "CTS log" -> {
                    val text by produceState("Loading…", id, name) {
                        value = withContext(Dispatchers.IO) {
                            val qpa = File(dir, "qpa/batch-%05d.qpa".format(r.batch))
                            Qpa.section(qpa, name)?.let { Qpa.readable(it).ifEmpty { it } } ?: "The CTS log has no entry for this test (it may have crashed before writing one)."
                        }
                    }
                    TextPane(text)
                }
                "Registers" -> CaptureView(nav, id, name, capture!!)
            }
        }
    }
}

@Composable
fun TextPane(text: String) {
    SelectionContainer {
        Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp)) {
                val lines = text.lines()
                items(lines.size) { i -> Text(lines[i], style = MonoSmall, softWrap = false) }
            }
        }
    }
}

@Composable
private fun CaptureView(nav: NavHostController, id: String, name: String, capture: File) {
    val context = LocalContext.current
    val sessions by AppGraph.sessions.sessions.collectAsStateWithLifecycle()
    val summary by produceState<RdCapture.Summary?>(null, capture) { value = withContext(Dispatchers.IO) { RdCapture.summary(capture) } }
    val others by produceState<List<SessionMeta>>(emptyList(), id, name, sessions) {
        value = withContext(Dispatchers.IO) {
            sessions.filter { it.id != id && it.record }.filter { s -> AppGraph.sessions.captures(s.id).containsKey(name) }
        }
    }
    var filter by rememberSaveable { mutableStateOf("") }
    val s = summary
    if (s == null) { LinearProgressIndicator(Modifier.fillMaxWidth()); return }
    val names = remember(s.gen) { RegNames.get(context, s.gen) }
    val rows = remember(s, filter) {
        s.regs.keys.sorted().map { k -> k to (names[k] ?: "REG_" + k.toString(16)) }
            .filter { filter.isBlank() || it.second.contains(filter, true) || it.first.toString(16).contains(filter, true) }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item {
            Text("Chip ${Fmt.hex(s.chipId)} (${s.gen}) · ${s.submits} submits · ${s.regs.size} registers" +
                (if (s.missingIbs > 0) " · ${s.missingIbs} command buffers not in capture" else "") + (s.error?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
        }
        if (others.isNotEmpty()) item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer), border = cardLine()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Compare with another driver", style = MaterialTheme.typography.titleSmall)
                    for (o in others.take(6)) {
                        TextButton(onClick = { nav.navigate(route("regdiff", id, o.id, name)) }) {
                            Text("${o.driverName} · ${Fmt.dateTime(o.startedAt)}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        item {
            OutlinedTextField(filter, { filter = it }, Modifier.fillMaxWidth(), placeholder = { Text("Filter registers") },
                leadingIcon = { Icon(Icons.Filled.Search, null) }, singleLine = true, shape = RoundedCornerShape(6.dp))
            Spacer(Modifier.height(8.dp))
        }
        items(rows, key = { it.first }) { (off, nm) ->
            val vals = s.regs[off].orEmpty()
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(nm, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(Fmt.hex(off.toLong(), 5), style = MonoSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(horizontalAlignment = Alignment.End) {
                    for ((v, n) in vals.take(4)) Text("${Fmt.hex(v)}${if (n > 1) " ×$n" else ""}", style = MonoSmall)
                    if (vals.size > 4) Text("+${vals.size - 4} more values", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        }
    }
}

@Composable
fun CompareScreen(nav: NavHostController, a: String, b: String) {
    val ma = AppGraph.sessions.get(a)
    val mb = AppGraph.sessions.get(b)
    data class Row3(val name: String, val sa: TestStatus?, val sb: TestStatus?, val fa: Boolean = false, val fb: Boolean = false)
    data class Cmp(val both: Int, val same: Int, val diffs: List<Row3>, val captured: List<String>)
    val cmp by produceState<Cmp?>(null, a, b) {
        value = withContext(Dispatchers.IO) {
            // Sessions that ran tests several times compare the combined result and whether it varied.
            val ra = AppGraph.sessions.statusMap(a)
            val rb = AppGraph.sessions.statusMap(b)
            var both = 0
            var same = 0
            val diffs = ArrayList<Row3>()
            for ((k, v) in ra) {
                val w = rb[k] ?: continue
                both++
                if (v == w) same++ else diffs += Row3(k, v.first, w.first, v.second, w.second)
            }
            val ca = AppGraph.sessions.captures(a)
            val cb = AppGraph.sessions.captures(b)
            Cmp(both, same, diffs.sortedBy { it.name }, ca.keys.filter { it in cb }.sorted())
        }
    }
    Scaffold(topBar = { AppBar("Compare", subtitle = "${ma?.driverName ?: a} vs ${mb?.driverName ?: b}", onBack = { nav.popBackStack() }) }) { pad ->
        val c = cmp
        if (c == null) { LinearProgressIndicator(Modifier.fillMaxWidth().padding(pad)); return@Scaffold }
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), border = cardLine()) {
                    Column(Modifier.padding(16.dp).animateContentSize()) {
                        Text("A  ${ma?.driverName.orEmpty()}", style = MaterialTheme.typography.titleSmall)
                        Text("B  ${mb?.driverName.orEmpty()}", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        InfoLine("In both", Fmt.count(c.both.toLong()))
                        InfoLine("Same result", Fmt.count(c.same.toLong()))
                        InfoLine("Different", Fmt.count(c.diffs.size.toLong()))
                        InfoLine("Recorded in both", Fmt.count(c.captured.size.toLong()))
                    }
                }
            }
            if (c.captured.isNotEmpty()) {
                item { SectionTitle("GPU register comparison") }
                items(c.captured.take(500), key = { "cap:$it" }) { n ->
                    Text(n.removePrefix("dEQP-VK."), fontFamily = Mono, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { nav.navigate(route("regdiff", a, b, n)) }.padding(8.dp))
                }
            }
            if (c.diffs.isNotEmpty()) {
                item { SectionTitle("Different results") }
                items(c.diffs.take(5000), key = { "d:" + it.name }) { d ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { nav.navigate(route("test", a, d.name)) }.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(d.name.removePrefix("dEQP-VK."), fontFamily = Mono, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.width(6.dp))
                        Column(horizontalAlignment = Alignment.End) {
                            d.sa?.let { StatusPill(it) }
                            if (d.fa) Text("sometimes", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.height(2.dp))
                            d.sb?.let { StatusPill(it) }
                            if (d.fb) Text("sometimes", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RegisterDiffScreen(nav: NavHostController, a: String, b: String, name: String) {
    val context = LocalContext.current
    val ma = AppGraph.sessions.get(a)
    val mb = AppGraph.sessions.get(b)
    var onlyDiff by rememberSaveable { mutableStateOf(true) }
    data class Both(val sa: RdCapture.Summary, val sb: RdCapture.Summary, val rows: List<RdCapture.DiffRow>)
    val data by produceState<Both?>(null, a, b, name) {
        value = withContext(Dispatchers.IO) {
            val fa = AppGraph.sessions.captures(a)[name]
            val fb = AppGraph.sessions.captures(b)[name]
            if (fa == null || fb == null) null else {
                val sa = RdCapture.summary(fa)
                val sb = RdCapture.summary(fb)
                val names = RegNames.get(context, if (sa.chipId != 0L) sa.gen else sb.gen)
                Both(sa, sb, RdCapture.diff(sa, sb, names))
            }
        }
    }
    val colors = LocalStatusColors.current
    Scaffold(topBar = { AppBar("Registers", subtitle = name.removePrefix("dEQP-VK."), onBack = { nav.popBackStack() }) }) { pad ->
        val d = data
        if (d == null) { LinearProgressIndicator(Modifier.fillMaxWidth().padding(pad)); return@Scaffold }
        val rows = if (onlyDiff) d.rows.filter { !it.same } else d.rows
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp)) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), border = cardLine()) {
                    Column(Modifier.padding(14.dp)) {
                        Text("A  ${ma?.driverName.orEmpty()}  ·  ${d.sa.submits} submits, ${d.sa.regs.size} registers", style = MaterialTheme.typography.bodySmall)
                        Text("B  ${mb?.driverName.orEmpty()}  ·  ${d.sb.submits} submits, ${d.sb.regs.size} registers", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(6.dp))
                        Text("${d.rows.count { !it.same }} registers differ", style = MaterialTheme.typography.titleMedium)
                    }
                }
                Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(onlyDiff, { onlyDiff = true }, label = { Text("Differences") })
                    FilterChip(!onlyDiff, { onlyDiff = false }, label = { Text("All registers") })
                }
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(8.dp)) {
                    Text("Register", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1.3f))
                    Text("A", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    Text("B", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                }
            }
            items(rows, key = { it.offset }) { r ->
                val tint = if (r.same) MaterialTheme.colorScheme.onSurface else colors.failed
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
                    Column(Modifier.weight(1.3f)) {
                        Text(r.name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(Fmt.hex(r.offset.toLong(), 5), style = MonoSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column(Modifier.weight(1f)) {
                        if (r.a.isEmpty()) Text("not written", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        for (v in r.a.take(4)) Text(Fmt.hex(v), style = MonoSmall, color = tint)
                    }
                    Column(Modifier.weight(1f)) {
                        if (r.b.isEmpty()) Text("not written", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        for (v in r.b.take(4)) Text(Fmt.hex(v), style = MonoSmall, color = tint)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }
        }
    }
}
