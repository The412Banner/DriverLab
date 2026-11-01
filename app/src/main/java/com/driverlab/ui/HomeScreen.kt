package com.driverlab.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.driverlab.AppGraph
import com.driverlab.core.DriverInfo
import com.driverlab.core.Fmt
import com.driverlab.core.RunManager
import com.driverlab.core.SessionMeta
import com.driverlab.core.SessionState
import kotlinx.coroutines.launch

/** Starts a run with the current driver, selection and settings (with the needed confirmations). */
class RunStarter(val start: () -> Unit, val dialog: @Composable () -> Unit)

@Composable
fun rememberRunStarter(nav: NavHostController): RunStarter {
    val context = LocalContext.current
    val settings by AppGraph.settings.state.collectAsStateWithLifecycle()
    val selection by AppGraph.selection.state.collectAsStateWithLifecycle()
    val label by AppGraph.selection.label.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf<String?>(null) }
    fun launchNow() {
        val total = AppGraph.selection.count(selection)
        val driver = AppGraph.drivers.get(settings.driverId) ?: AppGraph.drivers.get(DriverInfo.SYSTEM_ID)!!
        val req = RunManager.Request(
            driverId = driver.id,
            label = label ?: "Custom selection",
            selection = selection,
            total = total,
            record = settings.record,
            captureCapBytes = settings.captureCapBytes,
            timeoutSec = settings.timeoutSec,
            batchSize = settings.batchSize,
            jobs = settings.jobs,
            perTestLogLimit = settings.perTestLogLimit,
            export = settings.exportToDownloads,
            rawLog = settings.rawKgslLog,
        )
        if (AppGraph.runs.start(req)) nav.navigate("run")
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { launchNow() }
    fun proceed() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            launchNow()
        }
    }
    val start = {
        val total = AppGraph.selection.count(selection)
        confirm = when {
            total <= 0 -> null
            settings.record && total > 300 ->
                "Recording runs every test on its own and saves its GPU commands. ${Fmt.count(total)} tests will take a long time and can use a lot of storage."
            total > 50_000 -> "${Fmt.count(total)} tests can take many hours or days on a phone."
            else -> null
        }
        if (confirm == null && total > 0) proceed()
    }
    val dialog: @Composable () -> Unit = {
        confirm?.let { text ->
            AlertDialog(
                onDismissRequest = { confirm = null },
                title = { Text("Start this run?") },
                text = { Text(text) },
                confirmButton = { TextButton(onClick = { confirm = null; proceed() }) { Text("Start") } },
                dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
            )
        }
    }
    return remember(selection, settings, label) { RunStarter(start, dialog) }
}

@Composable
fun HomeScreen(nav: NavHostController) {
    val settings by AppGraph.settings.state.collectAsStateWithLifecycle()
    val drivers by AppGraph.drivers.drivers.collectAsStateWithLifecycle()
    val selection by AppGraph.selection.state.collectAsStateWithLifecycle()
    val label by AppGraph.selection.label.collectAsStateWithLifecycle()
    val live by AppGraph.runs.live.collectAsStateWithLifecycle()
    val sessions by AppGraph.sessions.sessions.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val starter = rememberRunStarter(nav)
    var pickDriver by remember { mutableStateOf(false) }
    var count by remember { mutableLongStateOf(0L) }
    LaunchedEffect(selection) { count = AppGraph.selection.count(selection) }

    val driver = drivers.firstOrNull { it.id == settings.driverId } ?: drivers.firstOrNull { it.isSystem }
    val systemProbe = drivers.firstOrNull { it.isSystem }?.probe

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Logo(48.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("DriverLab", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        listOfNotNull(Build.MODEL, systemProbe?.deviceName?.ifEmpty { null }).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { nav.navigate("settings") }) { Icon(Icons.Filled.Settings, "Settings") }
            }
        }
        item {
            AnimatedVisibility(live != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                live?.let { RunBanner(it) { nav.navigate("run") } }
            }
        }
        item { SectionTitle("Driver") }
        item {
            Card(
                Modifier.fillMaxWidth().clickable { pickDriver = true },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(if (driver?.isSystem != false) Icons.Filled.Memory else Icons.Filled.DeveloperBoard, MaterialTheme.colorScheme.primary, size = 44.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(driver?.name ?: "System driver", style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val p = driver?.probe
                        Text(
                            when {
                                p == null -> "Not checked yet"
                                !p.ok -> "Could not load: ${p.message.lineSequence().firstOrNull().orEmpty()}"
                                else -> "${p.deviceName} · Vulkan ${p.apiVersion}"
                            },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
                        )
                    }
                    Icon(Icons.Filled.ChevronRight, null)
                }
            }
        }
        item { SectionTitle("Tests") }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Column(Modifier.padding(16.dp).animateContentSize()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(Icons.Filled.Science, MaterialTheme.colorScheme.tertiary, size = 44.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(if (count == 0L) "No tests selected" else (label ?: "Custom selection"), style = MaterialTheme.typography.titleMedium)
                            Text(if (count == 0L) "Pick a preset or choose tests" else "${Fmt.count(count)} tests",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { nav.goTab("tests") }) { Text("Choose") }
                    }
                    Spacer(Modifier.height(10.dp))
                    PresetChips(AppGraph.catalog.presets, label, { p -> scope.launch { AppGraph.selection.applyPreset(p) } })
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Filled.FiberManualRecord, MaterialTheme.colorScheme.error, size = 44.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Record GPU commands", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (settings.record) "Saves a .rd capture per test · ${settings.captureLabel}" else "Off · results and logs only",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = settings.record, onCheckedChange = { v -> AppGraph.settings.update { it.copy(record = v) } })
                }
            }
        }
        item {
            Button(
                onClick = starter.start,
                enabled = count > 0 && live?.active != true,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Icon(Icons.Filled.PlayArrow, null)
                Spacer(Modifier.width(8.dp))
                Text(if (live?.active == true) "A run is in progress" else "Run ${if (count > 0) Fmt.count(count) + " " else ""}tests on ${driver?.name ?: "driver"}",
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (sessions.isNotEmpty()) {
            item { SectionTitle("Recent sessions") }
            items(sessions.take(4), key = { it.id }) { s ->
                SessionCard(s, Modifier.animateItem()) { nav.navigate(route("session", s.id)) }
            }
        }
    }
    starter.dialog()
    if (pickDriver) {
        AlertDialog(
            onDismissRequest = { pickDriver = false },
            title = { Text("Choose a driver") },
            text = {
                Column {
                    for (d in drivers) {
                        Row(
                            Modifier.fillMaxWidth().selectable(d.id == driver?.id) {
                                AppGraph.settings.update { it.copy(driverId = d.id) }
                                pickDriver = false
                            }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = d.id == driver?.id, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(d.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(d.probe?.deviceName?.ifEmpty { null } ?: if (d.isSystem) "Built-in" else d.libName,
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickDriver = false; nav.goTab("drivers") }) { Text("Import a driver") } },
            dismissButton = { TextButton(onClick = { pickDriver = false }) { Text("Close") } },
        )
    }
}

@Composable
fun RunBanner(live: RunManager.Live, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when (live.phase) {
                        RunManager.Phase.DONE -> "Run finished"
                        RunManager.Phase.STOPPED -> "Run stopped"
                        RunManager.Phase.FAILED -> "Run failed"
                        else -> "Running on ${live.driverName}"
                    },
                    style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.weight(1f),
                )
                Text("${Fmt.count(live.done)} / ${Fmt.count(live.total)}", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(progress = { live.fraction }, modifier = Modifier.fillMaxWidth().height(6.dp))
            Spacer(Modifier.height(8.dp))
            CountsRow(live.counts)
        }
    }
}

@Composable
fun SessionCard(s: SessionMeta, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val running = s.state == SessionState.RUNNING && AppGraph.runs.live.value?.sessionId == s.id && AppGraph.runs.isActive
    Card(
        modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.driverName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${Fmt.dateTime(s.startedAt)} · ${s.label}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    when {
                        running -> "Running"
                        s.state == SessionState.RUNNING -> "Interrupted"
                        s.state == SessionState.STOPPED -> "Stopped"
                        s.state == SessionState.FAILED -> "Failed"
                        else -> Fmt.duration((s.finishedAt - s.startedAt).coerceAtLeast(0))
                    },
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(10.dp))
            SegmentBar(s.counts)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CountsRow(s.counts, Modifier.weight(1f))
                if (s.record) Icon(Icons.Filled.FiberManualRecord, "Recorded", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}
