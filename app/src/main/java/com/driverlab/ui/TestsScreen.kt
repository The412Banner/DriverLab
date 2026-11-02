package com.driverlab.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.driverlab.AppGraph
import com.driverlab.core.Catalog
import com.driverlab.core.Fmt
import com.driverlab.core.GroupTree
import com.driverlab.core.ROOT
import com.driverlab.core.SelectionStore
import com.driverlab.core.TestList
import com.driverlab.ui.theme.Mono
import com.driverlab.ui.theme.MonoSmall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun TestsScreen(nav: NavHostController) {
    val selection by AppGraph.selection.state.collectAsStateWithLifecycle()
    val label by AppGraph.selection.label.collectAsStateWithLifecycle()
    val live by AppGraph.runs.live.collectAsStateWithLifecycle()
    var path by rememberSaveable { mutableStateOf("") } // "" = all groups, else "dEQP-VK.api.smoke"
    var query by rememberSaveable { mutableStateOf("") }
    var count by remember { mutableLongStateOf(0L) }
    val scope = rememberCoroutineScope()
    val starter = rememberRunStarter(nav)
    LaunchedEffect(selection) { count = AppGraph.selection.count(selection) }
    val catalog = AppGraph.catalog
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var loadedList by remember { mutableStateOf<Pair<String, SelectionStore.ListResult>?>(null) }
    val loadList = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = "Loading the test list…"
            try {
                val cr = context.contentResolver
                val name = withContext(Dispatchers.IO) { TestList.displayName(cr, uri) }
                loadedList = name to AppGraph.selection.loadList(name, context.cacheDir) { sink -> TestList.readLines(cr, uri, sink) }
                path = ""; query = ""
            } catch (e: Exception) {
                Toast.makeText(context, "Could not read the list: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                busy = null
            }
        }
    }
    val saveList = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val snapshot = selection
        scope.launch {
            busy = "Saving the test list…"
            try {
                val n = withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")!!.bufferedWriter().use { w ->
                        TestList.write(w) { sink -> AppGraph.selection.expand(snapshot, sink) }
                    }
                }
                Toast.makeText(context, "Saved ${Fmt.count(n)} test names.", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Could not save the list: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                busy = null
            }
        }
    }

    Scaffold(
        topBar = {
            AppBar(
                "Tests",
                subtitle = "${Fmt.count(count)} of ${Fmt.count(catalog.total)} selected · ${catalog.ctsTag}",
                onBack = { nav.popBackStack() },
                actions = {
                    IconButton(onClick = { AppGraph.selection.selectAll() }) { Icon(Icons.Filled.SelectAll, "Select all") }
                    IconButton(onClick = { AppGraph.selection.clear() }) { Icon(Icons.Filled.ClearAll, "Clear selection") }
                    Box {
                        IconButton(onClick = { menu = true }, enabled = busy == null) { Icon(Icons.Filled.MoreVert, "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Load test list…") },
                                leadingIcon = { Icon(Icons.Filled.FileOpen, null) },
                                onClick = { menu = false; loadList.launch(arrayOf("text/*", "application/octet-stream")) },
                            )
                            DropdownMenuItem(
                                text = { Text("Game features…") },
                                leadingIcon = { Icon(Icons.Filled.SportsEsports, null) },
                                onClick = { menu = false; nav.navigate("features") },
                            )
                            DropdownMenuItem(
                                text = { Text("Save test list…") },
                                leadingIcon = { Icon(Icons.Filled.Save, null) },
                                enabled = count > 0,
                                onClick = { menu = false; saveList.launch("caselist.txt") },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            AnimatedVisibility(count > 0, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
                Surface(tonalElevation = 3.dp, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${Fmt.count(count)} tests", style = MaterialTheme.typography.titleMedium)
                            Text(label ?: "Custom selection", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Button(shape = BtnShape, onClick = starter.start, enabled = live?.active != true) {
                            Icon(Icons.Filled.PlayArrow, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Run")
                        }
                    }
                }
            }
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                placeholder = { Text("Search tests, e.g. smoke or blend") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Clear, "Clear") } },
                singleLine = true,
                shape = RoundedCornerShape(6.dp),
            )
            PresetChips(
                catalog.presets, label, { p -> scope.launch { AppGraph.selection.applyPreset(p) } },
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            busy?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
            }
            HorizontalDivider()
            AnimatedContent(
                targetState = if (query.trim().length >= 2) "?" + query.trim() else path,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "tests",
                modifier = Modifier.weight(1f),
            ) { target ->
                when {
                    target.startsWith("?") -> SearchResults(target.drop(1), selection)
                    target.isEmpty() -> TopList(catalog, selection) { path = it }
                    else -> GroupList(target, selection, onOpen = { path = it }, onUp = { path = it })
                }
            }
        }
    }
    starter.dialog()
    loadedList?.let { (name, r) ->
        AlertDialog(
            onDismissRequest = { loadedList = null },
            title = { Text(name) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("${Fmt.count(r.tests)} tests selected from ${Fmt.count(r.names)} names in the list.")
                    if (r.missingCount > 0) {
                        Spacer(Modifier.height(8.dp))
                        val one = r.missingCount == 1L
                        Text("${Fmt.count(r.missingCount)} ${if (one) "name is" else "names are"} not in ${catalog.ctsTag} (renamed, removed, or a different suite):",
                            style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(4.dp))
                        Text(r.missing.take(50).joinToString("\n") + if (r.missingCount > 50) "\n…" else "", style = MonoSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { loadedList = null }) { Text("OK") } },
        )
    }
}

@Composable
private fun Breadcrumb(path: String, onGo: (String) -> Unit) {
    val segs = path.split('.')
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { onGo("") }) { Text("All") }
        for (i in 1 until segs.size) {
            Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            val target = segs.take(i + 1).joinToString(".")
            TextButton(onClick = { onGo(target) }, enabled = i < segs.size - 1) { Text(segs[i], maxLines = 1) }
        }
    }
}

@Composable
private fun NodeRow(name: String, sub: String, path: String, isLeaf: Boolean, tests: Int, @Suppress("UNUSED_PARAMETER") selectionKey: Any, onOpen: () -> Unit) {
    val state = AppGraph.selection.triState(path)
    Row(
        Modifier.fillMaxWidth().clickable {
            if (isLeaf) AppGraph.selection.set(path, state != androidx.compose.ui.state.ToggleableState.On, tests) else onOpen()
        }.padding(start = 4.dp, end = 12.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TriStateCheckbox(state = state, onClick = {
            AppGraph.selection.set(path, state != androidx.compose.ui.state.ToggleableState.On, tests)
        })
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(name, style = if (isLeaf) MaterialTheme.typography.bodyMedium.copy(fontFamily = Mono) else MaterialTheme.typography.bodyLarge,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ExplainButton(path, isLeaf, tests)
        if (!isLeaf) Icon(Icons.Filled.ChevronRight, "Open", tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TopList(catalog: Catalog, selection: Any, onOpen: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        items(catalog.tops, key = { it.name }) { t ->
            if (t.isGles) {
                NodeRow("OpenGL ES tests (freedreno)", "${Fmt.count(t.tests.toLong())} programs · system driver only", t.path, false, t.tests, selection) { onOpen(t.path) }
            } else {
                NodeRow(t.name, "${Fmt.count(t.tests.toLong())} tests", t.path, false, t.tests, selection) { onOpen(t.path) }
            }
        }
    }
}

@Composable
private fun GroupList(path: String, selection: Any, onOpen: (String) -> Unit, onUp: (String) -> Unit) {
    val top = AppGraph.catalog.topOf(path) ?: return
    val tree by produceState<GroupTree?>(null, top) { value = AppGraph.catalog.tree(top) }
    Column(Modifier.fillMaxSize()) {
        Breadcrumb(path, onUp)
        val t = tree
        if (t == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
            return@Column
        }
        val node = remember(t, path) { t.find(path) }
        if (node < 0) {
            Text("This group is not in the catalog.", Modifier.padding(16.dp))
            return@Column
        }
        val kids = remember(t, node) { t.children(node).toList() }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(kids, key = { it }) { c ->
                val p = "$path.${t.name(c)}"
                NodeRow(
                    t.name(c),
                    if (t.isLeaf(c)) "" else "${Fmt.count(t.tests[c].toLong())} tests",
                    p, t.isLeaf(c), t.tests[c], selection,
                ) { onOpen(p) }
            }
        }
    }
}

@Composable
private fun SearchResults(query: String, selection: Any) {
    var hits by remember { mutableStateOf<List<Catalog.Hit>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(query) {
        hits = emptyList()
        delay(300)
        busy = true
        val acc = ArrayList<Catalog.Hit>()
        withContext(Dispatchers.Default) {
            AppGraph.catalog.search(query, 400) { h ->
                acc += h
                if (acc.size % 20 == 0) hits = acc.toList()
            }
        }
        hits = acc.toList()
        busy = false
    }
    Column(Modifier.fillMaxSize()) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (!busy && hits.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No matching tests", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(hits, key = { it.path }) { h ->
                NodeRow(
                    h.path.removePrefix("$ROOT."),
                    if (h.isLeaf) "" else "${Fmt.count(h.tests.toLong())} tests",
                    h.path, true, h.tests, selection,
                ) {}
            }
            if (hits.size >= 400) item {
                Text("Showing the first 400 matches. Type more to narrow it down.", Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}
