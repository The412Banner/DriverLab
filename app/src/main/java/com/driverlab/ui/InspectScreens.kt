package com.driverlab.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.StickyNote2
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.driverlab.AppGraph
import com.driverlab.core.CompareResult
import com.driverlab.core.Fmt
import com.driverlab.core.Fn
import com.driverlab.core.InspectFile
import com.driverlab.core.Overview
import com.driverlab.ui.theme.MonoSmall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun hex(a: Long) = "0x%x".format(a)

private fun shortName(label: String) = label.substringAfterLast('/')

/** Driver inspector for one driver: its library files, then Overview / Functions / Text / Source. */
@Composable
fun InspectScreen(nav: NavHostController, driverId: String) {
    val driver = AppGraph.drivers.get(driverId)
    val files by produceState<List<InspectFile>?>(null, driverId) { value = withContext(Dispatchers.IO) { AppGraph.inspector.files(driverId) } }
    var fileLabel by rememberSaveable { mutableStateOf<String?>(null) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var comparing by remember { mutableStateOf(false) }
    DisposableEffect(driverId) { onDispose { AppGraph.inspector.release() } }
    val list = files
    val file = list?.firstOrNull { it.label == fileLabel } ?: list?.firstOrNull()
    Scaffold(topBar = {
        AppBar(driver?.name ?: "Inspect", subtitle = file?.let { shortName(it.label) + " · " + Fmt.size(it.size) }, onBack = { nav.popBackStack() }, actions = {
            if (file != null) IconButton(onClick = { comparing = true }) { Icon(Icons.Filled.Compare, "Compare") }
        })
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            when {
                list == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                file == null -> Text("No library files found for this driver.", Modifier.padding(16.dp))
                else -> {
                    if (list.size > 1) {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            list.forEach { f ->
                                FilterChip(selected = f.label == file.label, onClick = { fileLabel = f.label }, label = { Text(shortName(f.label), style = MaterialTheme.typography.labelMedium) })
                            }
                        }
                    }
                    val titles = listOf("Overview", "Functions", "Text", "Source")
                    PrimaryTabRow(selectedTabIndex = tab) {
                        titles.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        when (tab) {
                            0 -> OverviewTab(file)
                            1 -> FunctionsTab(nav, file)
                            2 -> TextTab(file)
                            else -> SourceTab(file) { tab = 1 }
                        }
                    }
                }
            }
        }
    }
    if (comparing && file != null) {
        ComparePicker(file, onDismiss = { comparing = false }) { other ->
            comparing = false
            nav.navigate(route("inspectcmp", file.driverId, file.label, other.driverId, other.label))
        }
    }
}

@Composable
private fun OverviewTab(f: InspectFile) {
    var refresh by remember(f.path) { mutableIntStateOf(0) }
    val state by produceState<Result<Overview>?>(null, f.path, refresh) {
        value = null
        value = runCatching { AppGraph.inspector.overview(f, refresh = refresh > 0) }
    }
    val r = state
    if (r == null) {
        Column(Modifier.padding(16.dp)) {
            Text("Reading the file…", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        return
    }
    val o = r.getOrElse {
        Column(Modifier.padding(16.dp)) {
            Text("Could not read the file: ${it.message}", color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { refresh++ }) { Text("Try again") }
        }
        return
    }
    val context = LocalContext.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            InspectCard {
                InfoLine("Version", o.version.ifEmpty { "not found in the file" })
                if (o.mesaCommit.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { InfoLine("Mesa commit", o.mesaCommit, mono = true) }
                        TextButton(onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://gitlab.freedesktop.org/mesa/mesa/-/tree/${o.mesaCommit}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }) { Text("Source") }
                    }
                }
                o.details.forEach { (k, v) -> InfoLine(k, v) }
                InfoLine("GPUs named", if (o.gpus.isEmpty()) "none" else o.gpus.joinToString(", "))
                InfoLine("Code", "${o.arch} ${o.bits}-bit, ${o.lang}" + if (o.stripped) ", stripped" else "")
                InfoLine("Size", Fmt.size(o.size))
            }
        }
        item {
            InspectCard {
                SectionTitle("Names inside")
                InfoLine("Exported", "${o.exports}")
                InfoLine("All symbols", "${o.names}")
                if (o.scrambled > 0) InfoLine("Scrambled", "${o.scrambled}")
                InfoLine("Text pieces", "${o.strings}")
            }
        }
        if (o.needed.isNotEmpty()) item {
            InspectCard {
                SectionTitle("Needs")
                Text(o.needed.joinToString("\n"), style = MonoSmall)
            }
        }
        if (o.sourcePaths.isNotEmpty()) item {
            var open by remember { mutableStateOf(false) }
            InspectCard(Modifier.clickable { open = !open }) {
                SectionTitle("Source file paths (${o.sourcePaths.size})")
                Text(
                    if (open) o.sourcePaths.joinToString("\n") else o.sourcePaths.take(5).joinToString("\n") + if (o.sourcePaths.size > 5) "\n…" else "",
                    style = MonoSmall,
                )
            }
        }
        item { TextButton(onClick = { refresh++ }) { Icon(Icons.Filled.Refresh, null); Spacer(Modifier.width(6.dp)); Text("Read again") } }
    }
}

@Composable
private fun InspectCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        border = cardLine(),
        shape = BtnShape,
    ) { Column(Modifier.padding(14.dp)) { content() } }
}

@Composable
private fun FunctionsTab(nav: NavHostController, f: InspectFile) {
    val scope = rememberCoroutineScope()
    var list by remember(f.path) { mutableStateOf(AppGraph.inspector.cachedFunctions(f)) }
    var busy by remember(f.path) { mutableStateOf(false) }
    var error by remember(f.path) { mutableStateOf<String?>(null) }
    var query by rememberSaveable(f.path) { mutableStateOf("") }
    val names = remember(f.path, list) { AppGraph.inspector.names(f) }
    fun find(refresh: Boolean) = scope.launch {
        busy = true; error = null
        runCatching { AppGraph.inspector.functions(f, refresh) }.onSuccess { list = it }.onFailure { error = it.message }
        busy = false
    }
    val l = list
    if (l == null || busy) {
        Column(Modifier.padding(16.dp)) {
            if (busy) {
                Text("Finding functions…", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                Button(shape = BtnShape, onClick = { find(false) }) { Text("Find functions") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
        }
        return
    }
    val shown = remember(l, query) {
        if (query.isBlank()) l else l.filter { it.name.contains(query, true) || hex(it.addr).contains(query, true) }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(query, { query = it }, Modifier.weight(1f), singleLine = true, leadingIcon = { Icon(Icons.Filled.Search, null) },
                placeholder = { Text("Name or address") }, textStyle = MonoSmall)
            IconButton(onClick = { find(true) }) { Icon(Icons.Filled.Refresh, "Find again") }
        }
        Text("${shown.size} of ${l.size}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp))
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(shown, key = { it.addr }) { fn -> FnRow(fn, renamed = names.containsKey(fn.addr)) { nav.navigate(route("inspectfn", f.driverId, f.label, fn.addr.toString())) } }
        }
    }
}

@Composable
private fun FnRow(fn: Fn, renamed: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 7.dp)) {
        Text(fn.name, style = MonoSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
            color = if (renamed) MaterialTheme.colorScheme.tertiary else Color.Unspecified)
        Text("${hex(fn.addr)} · ${fn.size} bytes", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TextTab(f: InspectFile) {
    var query by rememberSaveable(f.path) { mutableStateOf("") }
    val result by produceState<Pair<List<String>, Int>?>(null, f.path, query) {
        value = runCatching { AppGraph.inspector.strings(f, query) }.getOrDefault(emptyList<String>() to 0)
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, null) }, placeholder = { Text("Search text") }, textStyle = MonoSmall)
        val r = result
        if (r == null) LinearProgressIndicator(Modifier.fillMaxWidth()) else {
            Text(if (r.second > r.first.size) "${r.first.size} of ${r.second}" else "${r.second}", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
            SelectionContainer {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 24.dp)) {
                    items(r.first) { s -> Text(s, style = MonoSmall, modifier = Modifier.padding(vertical = 2.dp)) }
                }
            }
        }
    }
}

@Composable
private fun SourceTab(f: InspectFile, openFunctions: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val jobs by AppGraph.inspector.jobs.collectAsStateWithLifecycle()
    val job = jobs[f.path]
    var has by remember(f.path, job?.running) { mutableStateOf(AppGraph.inspector.hasSource(f)) }
    LaunchedEffect(job?.running) { has = AppGraph.inspector.hasSource(f) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Turns every function of ${shortName(f.label)} into C. Rebuilt from machine code, not the original source.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val j = job
        when {
            j != null && j.running -> {
                Text(if (j.total > 0) "${j.phase}: ${j.done} of ${j.total}" else "${j.phase}…", style = MaterialTheme.typography.bodyMedium)
                if (j.total > 0) LinearProgressIndicator(progress = { j.done.toFloat() / j.total }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
                OutlinedButton(shape = BtnShape, onClick = { AppGraph.inspector.stopSource(f) }) { Text("Stop") }
            }
            else -> {
                job?.error?.let { Text("Stopped: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (has) {
                    val count = remember(f.path, job) { AppGraph.inspector.cachedFunctions(f)?.size ?: 0 }
                    Text("Source code made: $count functions.", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(shape = BtnShape, onClick = openFunctions) { Text("Open") }
                        OutlinedButton(shape = BtnShape, onClick = {
                            scope.launch {
                                val r = runCatching {
                                    val zip = AppGraph.inspector.sourceZip(f)
                                    withContext(Dispatchers.IO) { AppGraph.sessions.copyToDownloads(zip, "inspect", zip.name) }
                                }
                                Toast.makeText(context, r.fold({ "Saved to Download/DriverLab/inspect" }, { "Could not save: ${it.message}" }), Toast.LENGTH_LONG).show()
                            }
                        }) { Text("Save zip") }
                        OutlinedButton(shape = BtnShape, onClick = {
                            scope.launch {
                                runCatching { AppGraph.inspector.sourceZip(f) }
                                    .onSuccess { context.startActivity(AppGraph.sessions.shareIntent(listOf(it))) }
                                    .onFailure { Toast.makeText(context, "Could not share: ${it.message}", Toast.LENGTH_LONG).show() }
                            }
                        }) { Text("Share") }
                    }
                    TextButton(onClick = { AppGraph.inspector.makeSource(f) }) { Text("Make again") }
                } else {
                    Button(shape = BtnShape, onClick = { AppGraph.inspector.makeSource(f) }) { Text("Make source code") }
                }
            }
        }
    }
}

// ---- One function ----

private val CODE_TOKENS = Regex(
    """("(?:\\.|[^"\\])*")|(\b0x[0-9a-fA-F]+\b|\b\d+\b)|\b(if|else|while|for|do|return|switch|case|break|continue|goto|default|sizeof)\b|""" +
        """\b(u?int(?:8|16|32|64)_t|undefined\d*|char|bool|void|float|double|code|ulong|uint|int|long|short|byte|unk\w+)\b|""" +
        """([A-Za-z_][\w.:]*)(?=\s*\()""",
)

private class CodeColors(val comment: Color, val string: Color, val number: Color, val keyword: Color, val type: Color, val call: Color)

private fun highlight(line: String, c: CodeColors, onCall: (String) -> Unit): AnnotatedString = buildAnnotatedString {
    val trimmed = line.trimStart()
    if (trimmed.startsWith("//")) {
        withStyle(SpanStyle(color = c.comment)) { append(line) }
        return@buildAnnotatedString
    }
    var pos = 0
    for (m in CODE_TOKENS.findAll(line)) {
        if (m.range.first > pos) append(line.substring(pos, m.range.first))
        val g = m.groups
        when {
            g[1] != null -> withStyle(SpanStyle(color = c.string)) { append(m.value) }
            g[2] != null -> withStyle(SpanStyle(color = c.number)) { append(m.value) }
            g[3] != null -> withStyle(SpanStyle(color = c.keyword, fontWeight = FontWeight.SemiBold)) { append(m.value) }
            g[4] != null -> withStyle(SpanStyle(color = c.type)) { append(m.value) }
            else -> {
                val name = m.value
                withLink(LinkAnnotation.Clickable(name, TextLinkStyles(SpanStyle(color = c.call))) { onCall(name) }) { append(name) }
            }
        }
        pos = m.range.last + 1
    }
    if (pos < line.length) append(line.substring(pos))
}

/** One function as C: decompiled now, from the made source, or your edited copy. Rename, note, edit, copy. */
@Composable
fun FunctionScreen(nav: NavHostController, driverId: String, label: String, addrText: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val addr = addrText.toLongOrNull() ?: 0L
    val f = remember(driverId, label) { AppGraph.inspector.file(driverId, label) }
    var reload by remember { mutableIntStateOf(0) }
    var live by remember { mutableStateOf(false) }
    val code by produceState<Result<String>?>(null, f, addr, reload) {
        value = null
        value = if (f == null) Result.failure(IllegalStateException("The file is gone.")) else runCatching {
            if (live) AppGraph.inspector.decompile(f, addr) else AppGraph.inspector.code(f, addr)
        }
    }
    val edited = remember(f, addr, reload) { f?.let { AppGraph.inspector.edited(it, addr) } != null }
    var editing by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var noting by remember { mutableStateOf(false) }
    val text = code?.getOrNull()
    val title = text?.lineSequence()?.firstOrNull()?.removePrefix("// ")?.trim() ?: hex(addr)
    val byName = remember(f) { f?.let { AppGraph.inspector.cachedFunctions(it) }?.associateBy { it.name }.orEmpty() }
    Scaffold(topBar = {
        AppBar(title, subtitle = hex(addr) + (if (edited) " · edited" else "") + " · " + shortName(label), onBack = { nav.popBackStack() }, actions = {
            if (text != null && !editing) {
                IconButton(onClick = { renaming = true }) { Icon(Icons.Filled.Label, "Rename") }
                IconButton(onClick = { noting = true }) { Icon(Icons.Filled.StickyNote2, "Note") }
                IconButton(onClick = { editing = true }) { Icon(Icons.Filled.Edit, "Edit") }
                IconButton(onClick = {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(title, text))
                    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                }) { Icon(Icons.Filled.ContentCopy, "Copy") }
                if (edited) IconButton(onClick = { f?.let { AppGraph.inspector.revertEdit(it, addr) }; reload++ }) { Icon(Icons.Filled.Undo, "Undo edits") }
            }
        })
    }) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            val r = code
            when {
                r == null -> Column(Modifier.padding(16.dp)) {
                    Text("Decompiling…", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                r.isFailure -> Text("Could not decompile: ${r.exceptionOrNull()?.message}", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                editing -> CodeEditor(text.orEmpty(), onCancel = { editing = false }) { t ->
                    f?.let { AppGraph.inspector.saveEdit(it, addr, t) }
                    editing = false
                    reload++
                }
                else -> CodePane(text.orEmpty()) { name ->
                    val target = Regex("""^fcn\.([0-9a-fA-F]+)$""").find(name)?.groupValues?.get(1)?.toLongOrNull(16) ?: byName[name]?.addr
                    if (target != null && target != addr) nav.navigate(route("inspectfn", driverId, label, target.toString()))
                }
            }
        }
    }
    if (renaming && f != null) {
        var name by remember { mutableStateOf(title.substringBefore('(').trim()) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename") },
            text = { OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, textStyle = MonoSmall) },
            confirmButton = {
                TextButton(onClick = {
                    renaming = false
                    scope.launch {
                        runCatching { AppGraph.inspector.rename(f, addr, name) }
                            .onFailure { Toast.makeText(context, "Could not rename: ${it.message}", Toast.LENGTH_LONG).show() }
                        live = true
                        reload++
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
    if (noting && f != null) {
        var note by remember { mutableStateOf(AppGraph.inspector.notes(f)[addr].orEmpty()) }
        AlertDialog(
            onDismissRequest = { noting = false },
            title = { Text("Note") },
            text = { OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth().heightIn(min = 100.dp)) },
            confirmButton = {
                TextButton(onClick = {
                    AppGraph.inspector.setNote(f, addr, note)
                    noting = false
                    live = true
                    reload++
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { noting = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CodePane(text: String, onCall: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val colors = remember(scheme) {
        CodeColors(
            comment = scheme.onSurfaceVariant, string = Color(0xFF7FB77E), number = Color(0xFFD7A65F),
            keyword = scheme.primary, type = Color(0xFF6FB3D2), call = scheme.tertiary,
        )
    }
    val lines = remember(text) { text.lines() }
    SelectionContainer {
        Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp, 8.dp, 12.dp, 32.dp)) {
                items(lines.size) { i ->
                    val a = remember(lines[i], colors) { highlight(lines[i], colors, onCall) }
                    Text(a, style = MonoSmall, softWrap = false)
                }
            }
        }
    }
}

@Composable
private fun CodeEditor(initial: String, onCancel: () -> Unit, onSave: (String) -> Unit) {
    var t by remember { mutableStateOf(initial) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            Button(shape = BtnShape, onClick = { onSave(t) }) { Text("Save") }
        }
        Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
            BasicTextField(
                t, { t = it },
                textStyle = MonoSmall.copy(color = MaterialTheme.colorScheme.onSurface),
                modifier = Modifier.padding(12.dp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

// ---- Compare ----

@Composable
private fun ComparePicker(current: InspectFile, onDismiss: () -> Unit, onPick: (InspectFile) -> Unit) {
    val drivers by AppGraph.drivers.drivers.collectAsStateWithLifecycle()
    val all by produceState<List<Pair<String, InspectFile>>?>(null, drivers) {
        value = withContext(Dispatchers.IO) {
            drivers.flatMap { d -> AppGraph.inspector.files(d.id).map { d.name to it } }.filter { it.second.path != current.path }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Compare ${shortName(current.label)} with") },
        text = {
            val l = all
            if (l == null) LinearProgressIndicator(Modifier.fillMaxWidth()) else LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(l) { (dn, f) ->
                    Column(Modifier.fillMaxWidth().clickable { onPick(f) }.padding(vertical = 8.dp)) {
                        Text(shortName(f.label), style = MonoSmall, color = if (shortName(f.label) == shortName(current.label)) MaterialTheme.colorScheme.primary else Color.Unspecified)
                        Text("$dn · ${Fmt.size(f.size)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Two library files side by side: versions, GPUs, exports, needed libraries, text and named functions. */
@Composable
fun InspectCompareScreen(nav: NavHostController, a: String, fa: String, b: String, fb: String) {
    val fileA = remember(a, fa) { AppGraph.inspector.file(a, fa) }
    val fileB = remember(b, fb) { AppGraph.inspector.file(b, fb) }
    var withFunctions by remember { mutableStateOf(false) }
    val result by produceState<Result<CompareResult>?>(null, fileA, fileB, withFunctions) {
        value = null
        value = if (fileA == null || fileB == null) Result.failure(IllegalStateException("A file is gone.")) else
            runCatching { AppGraph.inspector.compare(fileA, fileB, withFunctions) }
    }
    val nameA = AppGraph.drivers.get(a)?.name.orEmpty()
    val nameB = AppGraph.drivers.get(b)?.name.orEmpty()
    DisposableEffect(Unit) { onDispose { AppGraph.inspector.release() } }
    Scaffold(topBar = { AppBar("Compare", subtitle = "A: $nameA · B: $nameB", onBack = { nav.popBackStack() }) }) { pad ->
        val r = result
        Box(Modifier.fillMaxSize().padding(pad)) {
            when {
                r == null -> Column(Modifier.padding(16.dp)) {
                    Text(if (withFunctions) "Finding functions in both files…" else "Reading both files…", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                r.isFailure -> Text("Could not compare: ${r.exceptionOrNull()?.message}", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                else -> CompareBody(r.getOrThrow(), fileA!!, fileB!!, withFunctions) { withFunctions = true }
            }
        }
    }
}

@Composable
private fun CompareBody(c: CompareResult, fa: InspectFile, fb: InspectFile, withFunctions: Boolean, onFunctions: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            InspectCard {
                CompareLine("File", shortName(fa.label), shortName(fb.label))
                CompareLine("Version", c.a.version.ifEmpty { "—" }, c.b.version.ifEmpty { "—" })
                c.a.details.map { it.first }.union(c.b.details.map { it.first }).forEach { k ->
                    CompareLine(k, c.a.details.firstOrNull { it.first == k }?.second ?: "—", c.b.details.firstOrNull { it.first == k }?.second ?: "—")
                }
                CompareLine("Size", Fmt.size(c.a.size), Fmt.size(c.b.size))
                CompareLine("Exported", "${c.a.exports}", "${c.b.exports}")
                CompareLine("Symbols", "${c.a.names}", "${c.b.names}")
                CompareLine("GPUs", "${c.a.gpus.size}", "${c.b.gpus.size}")
            }
        }
        item { DiffCard("GPUs", c.gpusAdded, c.gpusRemoved) }
        item { DiffCard("Needed libraries", c.neededAdded, c.neededRemoved) }
        item { DiffCard("Exported functions", c.exportsAdded, c.exportsRemoved) }
        item { DiffCard("Text", c.stringsAdded, c.stringsRemoved) }
        item {
            if (!withFunctions) {
                OutlinedButton(shape = BtnShape, onClick = onFunctions) { Text("Compare functions too") }
            } else {
                InspectCard {
                    SectionTitle("Named functions that differ (${c.functions.size})")
                    Text("A: ${c.functionsA} named · B: ${c.functionsB} named", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    c.functions.take(400).forEach { (n, sa, sb) ->
                        Text("${sa ?: "—"} → ${sb ?: "—"}  $n", style = MonoSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (c.functions.size > 400) Text("… ${c.functions.size - 400} more", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun CompareLine(label: String, a: String, b: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(96.dp))
        Text(a, style = MonoSmall, modifier = Modifier.weight(1f), color = if (a != b) MaterialTheme.colorScheme.primary else Color.Unspecified)
        Text(b, style = MonoSmall, modifier = Modifier.weight(1f), color = if (a != b) MaterialTheme.colorScheme.tertiary else Color.Unspecified)
    }
}

@Composable
private fun DiffCard(title: String, added: List<String>, removed: List<String>) {
    var open by remember { mutableStateOf(false) }
    InspectCard(Modifier.clickable { open = !open }) {
        SectionTitle("$title: ${added.size} only in B, ${removed.size} only in A")
        if (open) {
            val limit = 300
            removed.take(limit).forEach { Text("− $it", style = MonoSmall, color = MaterialTheme.colorScheme.primary, maxLines = 3, overflow = TextOverflow.Ellipsis) }
            if (removed.size > limit) Text("… ${removed.size - limit} more only in A", style = MaterialTheme.typography.labelSmall)
            added.take(limit).forEach { Text("+ $it", style = MonoSmall, color = MaterialTheme.colorScheme.tertiary, maxLines = 3, overflow = TextOverflow.Ellipsis) }
            if (added.size > limit) Text("… ${added.size - limit} more only in B", style = MaterialTheme.typography.labelSmall)
        }
    }
}
