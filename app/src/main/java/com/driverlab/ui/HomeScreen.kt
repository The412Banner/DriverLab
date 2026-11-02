package com.driverlab.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.driverlab.AppGraph
import com.driverlab.core.Counts
import com.driverlab.core.DriverInfo
import com.driverlab.core.Fmt
import com.driverlab.core.RunManager
import com.driverlab.core.SessionMeta
import com.driverlab.core.SessionState
import com.driverlab.core.SpeedDriver
import com.driverlab.core.SpeedManager
import com.driverlab.core.SpeedMath
import com.driverlab.core.SpeedRequest
import com.driverlab.core.SpeedResult
import com.driverlab.core.SpeedScenes
import com.driverlab.ui.theme.LocalDriverColors
import com.driverlab.ui.theme.LocalStatusColors
import com.driverlab.ui.theme.Mono
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Starts a run with the current driver, selection and settings (with the needed confirmations). */
class RunStarter(val start: () -> Unit, val dialog: @Composable () -> Unit)

/** [openRunScreen] = false keeps the user on the dashboard, which shows the run live. */
@Composable
fun rememberRunStarter(nav: NavHostController, openRunScreen: Boolean = true): RunStarter {
    val context = LocalContext.current
    val settings by AppGraph.settings.state.collectAsStateWithLifecycle()
    val selection by AppGraph.selection.state.collectAsStateWithLifecycle()
    val label by AppGraph.selection.label.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf<String?>(null) }
    fun launchNow() {
        val total = AppGraph.selection.count(selection)
        val driver = AppGraph.drivers.get(settings.driverId) ?: AppGraph.drivers.get(DriverInfo.SYSTEM_ID)!!
        val second = settings.secondDriverId.takeIf { it.isNotEmpty() && it != driver.id && AppGraph.drivers.get(it) != null }
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
            repeat = if (settings.record) 1 else settings.repeat,
            gpuCheck = settings.gpuCheck,
            thenDriverId = second,
        )
        if (AppGraph.runs.start(req) && openRunScreen) nav.navigate("run")
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
        val total = AppGraph.selection.count(selection) * (if (settings.record) 1 else settings.repeat.coerceAtLeast(1))
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

/**
 * Home: one dashboard. Driver A against driver B, the test type, one Start button and four live tiles.
 * Upright it is one column; on a wide screen (sideways, tablets) setup goes left and the tiles right.
 */
@Composable
fun HomeScreen(nav: NavHostController) {
    val settings by AppGraph.settings.state.collectAsStateWithLifecycle()
    val drivers by AppGraph.drivers.drivers.collectAsStateWithLifecycle()
    val runLive by AppGraph.runs.live.collectAsStateWithLifecycle()
    val speedLive by AppGraph.speed.live.collectAsStateWithLifecycle()
    val sessions by AppGraph.sessions.sessions.collectAsStateWithLifecycle()
    val speedResults by AppGraph.speed.results.collectAsStateWithLifecycle()
    val selection by AppGraph.selection.state.collectAsStateWithLifecycle()
    val label by AppGraph.selection.label.collectAsStateWithLifecycle()
    val starter = rememberRunStarter(nav, openRunScreen = false)
    var count by remember { mutableLongStateOf(0L) }
    LaunchedEffect(selection) { count = AppGraph.selection.count(selection) }
    var picking by remember { mutableStateOf<Char?>(null) }
    var scenesDialog by remember { mutableStateOf(false) }

    // Tests per second, sampled twice a second while a run is going.
    val rates = remember { mutableStateListOf<Double>() }
    LaunchedEffect(runLive?.sessionId, runLive?.active) {
        if (runLive?.active != true) return@LaunchedEffect
        var last = AppGraph.runs.live.value?.done ?: 0L
        var lastT = System.currentTimeMillis()
        while (true) {
            delay(500)
            val l = AppGraph.runs.live.value ?: break
            val now = System.currentTimeMillis()
            val r = ((l.done - last) * 1000.0 / (now - lastT).coerceAtLeast(1)).coerceAtLeast(0.0)
            last = l.done
            lastT = now
            rates.add(r)
            while (rates.size > 48) rates.removeAt(0)
            if (!l.active) break
        }
    }

    val speedMode = settings.homeMode == 1
    val a = drivers.firstOrNull { it.id == settings.driverId } ?: drivers.firstOrNull { it.isSystem }
    val b = drivers.firstOrNull { it.id == settings.secondDriverId && it.id != a?.id }
    val state = DashState(settings, a, b, runLive, speedLive, sessions.firstOrNull(), speedResults.firstOrNull(), rates.toList(), label, count)

    BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().displayCutoutPadding()) {
        val wide = maxWidth > maxHeight && maxWidth >= 600.dp
        Column(Modifier.fillMaxSize()) {
            DashHeader(nav, wide)
            if (wide) {
                Row(Modifier.weight(1f).fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(
                        Modifier.width(320.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        SetupBlock(nav, state, speedMode, starter, { picking = it }, { scenesDialog = true })
                        RecentBlock(nav, sessions, speedResults)
                    }
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Tiles(nav, state, speedMode, wide = true)
                    }
                }
            } else {
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SetupBlock(nav, state, speedMode, starter, { picking = it }, { scenesDialog = true })
                    Tiles(nav, state, speedMode, wide = false)
                    RecentBlock(nav, sessions, speedResults)
                }
            }
        }
    }
    starter.dialog()
    picking?.let { which ->
        DriverPickDialog(which, drivers, a, b, onDismiss = { picking = null }) { id ->
            AppGraph.settings.update {
                if (which == 'A') it.copy(driverId = id ?: DriverInfo.SYSTEM_ID, secondDriverId = if (id == it.secondDriverId) "" else it.secondDriverId)
                else it.copy(secondDriverId = id ?: "")
            }
            picking = null
        }
    }
    if (scenesDialog) ScenesDialog(settings.speedScenes) { scenesDialog = false }
}

/** Everything the dashboard shows, read once per frame. */
private class DashState(
    val settings: com.driverlab.core.AppSettings,
    val a: DriverInfo?,
    val b: DriverInfo?,
    val run: RunManager.Live?,
    val speed: SpeedManager.Live?,
    val lastSession: SessionMeta?,
    val lastSpeed: SpeedResult?,
    val rates: List<Double>,
    val label: String?,
    val count: Long,
) {
    val runActive get() = run?.active == true
    val speedActive get() = speed?.active == true
    val speedScenes: List<String>
        get() = settings.speedScenes.split(',').map { it.trim() }.filter { id -> SpeedScenes.byId(id) != null }
            .ifEmpty { SpeedScenes.all.map { it.id } }
}

@Composable
private fun DashHeader(nav: NavHostController, wide: Boolean) {
    val probe = AppGraph.drivers.get(DriverInfo.SYSTEM_ID)?.probe
    Row(
        Modifier.fillMaxWidth().height(if (wide) 52.dp else 60.dp).padding(start = 14.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (wide) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("DriverLab", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.4).sp)
                DeviceLine(probe?.deviceName, Modifier.padding(bottom = 2.dp))
            }
        } else {
            Column(Modifier.weight(1f)) {
                Text("DriverLab", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.4).sp)
                DeviceLine(probe?.deviceName)
            }
        }
        HeaderIcon(Icons.AutoMirrored.Filled.ViewList, "Tests") { nav.navigate("tests") }
        HeaderIcon(Icons.Filled.Assessment, "Results") { nav.navigate("results") }
        HeaderIcon(Icons.Filled.DeveloperBoard, "Drivers") { nav.navigate("drivers") }
        HeaderIcon(Icons.Filled.Settings, "Settings") { nav.navigate("settings") }
    }
    androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun DeviceLine(gpu: String?, modifier: Modifier = Modifier) {
    val parts = listOfNotNull(Build.MODEL, gpu?.ifEmpty { null }, "Android " + Build.VERSION.RELEASE)
    Text(parts.joinToString(" · ").uppercase(), modifier = modifier, fontFamily = Mono, fontSize = 10.sp, letterSpacing = 0.5.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun HeaderIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(4.dp)).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SetupBlock(
    nav: NavHostController,
    s: DashState,
    speedMode: Boolean,
    starter: RunStarter,
    onPick: (Char) -> Unit,
    onScenes: () -> Unit,
) {
    val dc = LocalDriverColors.current
    val scope = rememberCoroutineScope()
    // Driver A against driver B.
    Box(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VsCard("DRIVER A", s.a?.name ?: "System driver", s.a?.let { driverSub(it) }.orEmpty(), dc.a, Modifier.weight(1f)) { onPick('A') }
            if (s.b != null) VsCard("DRIVER B", s.b.name, driverSub(s.b), dc.b, Modifier.weight(1f)) { onPick('B') }
            else VsCard("DRIVER B", "Add a driver to compare", "optional", MaterialTheme.colorScheme.outline, Modifier.weight(1f), dashed = true) { onPick('B') }
        }
        Box(
            Modifier.align(Alignment.Center).size(30.dp).clip(RoundedCornerShape(4.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center,
        ) { Text("VS", fontFamily = Mono, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    // Test type.
    Column {
        Row(Modifier.fillMaxWidth().height(44.dp)) {
            ModeTab("Conformance", !speedMode, Modifier.weight(1f)) { AppGraph.settings.update { it.copy(homeMode = 0) } }
            ModeTab("Speed test", speedMode, Modifier.weight(1f)) { AppGraph.settings.update { it.copy(homeMode = 1) } }
        }
        androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
    if (!speedMode) {
        if (!s.runActive) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (p in AppGraph.catalog.presets) {
                    FilterChip(selected = s.label == p.title, onClick = { scope.launch { AppGraph.selection.applyPreset(p) } }, label = { Text(p.title) })
                }
                FilterChip(selected = s.label != null && AppGraph.catalog.presets.none { it.title == s.label }, onClick = { nav.navigate("tests") },
                    label = { Text(if (s.label != null && AppGraph.catalog.presets.none { it.title == s.label }) s.label else "Choose tests…", maxLines = 1, overflow = TextOverflow.Ellipsis) })
                FilterChip(selected = s.settings.record, onClick = { AppGraph.settings.update { it.copy(record = !it.record) } },
                    leadingIcon = { Icon(Icons.Filled.FiberManualRecord, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error) },
                    label = { Text("Record") })
            }
        }
        val reps = if (s.settings.record) 1 else s.settings.repeat
        val busy = s.speedActive
        StartButton(
            text = when {
                s.runActive -> "Stop"
                busy -> "A speed test is running"
                s.count == 0L -> "Choose tests to run"
                else -> "Run ${Fmt.count(s.count)} tests" + (if (reps > 1) " ×$reps" else "") + (if (s.b != null) " on A and B" else "")
            },
            running = s.runActive, enabled = s.runActive || (!busy && s.count > 0),
        ) { if (s.runActive) AppGraph.runs.stop() else starter.start() }
        val l = s.run
        ProgressLine(
            fraction = l?.fraction ?: 0f,
            text = when {
                l == null -> "ready"
                l.active -> "${Fmt.count(l.done)} / ${Fmt.count(l.total)}" + (if (l.steps > 1) " · driver ${l.step}/${l.steps}" else "")
                else -> (l.message ?: l.phase.name.lowercase())
            },
            onClick = if (l != null) ({ nav.navigate("run") }) else null,
        )
        if (l != null && !l.active) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (l.sessionId.isNotEmpty()) OutlinedButton(shape = BtnShape, onClick = { nav.navigate(route("session", l.sessionId)) }, modifier = Modifier.weight(1f)) { Text("Results") }
                if (l.pairIds.size == 2) OutlinedButton(shape = BtnShape, onClick = { nav.navigate(route("compare", l.pairIds[0], l.pairIds[1])) }, modifier = Modifier.weight(1f)) { Text("Compare A · B") }
            }
        }
    } else {
        val sc = s.speedScenes
        if (!s.speedActive) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = true, onClick = onScenes, label = { Text("${sc.size} of ${SpeedScenes.all.size} scenes") })
                FilterChip(selected = false, onClick = { AppGraph.settings.update { it.copy(speedRounds = cycle(listOf(1, 3, 5), it.speedRounds)) } },
                    label = { Text(Fmt.plural(s.settings.speedRounds, "round")) })
                FilterChip(selected = false, onClick = { AppGraph.settings.update { it.copy(speedSeconds = cycle(listOf(3, 5, 10), it.speedSeconds)) } },
                    label = { Text("${s.settings.speedSeconds} s each") })
                FilterChip(selected = false, onClick = { AppGraph.settings.update { it.copy(speedPause = cycle(listOf(0, 10, 30), it.speedPause)) } },
                    label = { Text(if (s.settings.speedPause == 0) "no pause" else "${s.settings.speedPause} s pause") })
            }
        }
        val busy = s.runActive
        StartButton(
            text = when {
                s.speedActive -> "Stop"
                busy -> "A test run is in progress"
                s.speed?.phase == SpeedManager.Phase.DONE -> "Run the speed test again"
                else -> "Start the speed test"
            },
            running = s.speedActive, enabled = s.speedActive || !busy,
        ) {
            if (s.speedActive) AppGraph.speed.stop()
            else AppGraph.speed.start(
                SpeedRequest(
                    s.a?.id ?: DriverInfo.SYSTEM_ID, s.b?.id, sc, s.settings.speedRounds, s.settings.speedSeconds, s.settings.speedPause,
                    preview = true, export = s.settings.exportToDownloads,
                ),
            )
        }
        val l = s.speed
        ProgressLine(
            fraction = l?.fraction ?: 0f,
            text = when {
                l == null -> "ready"
                l.active -> "round ${l.round} of ${l.rounds}" + (l.scene?.let { " · " + (SpeedScenes.byId(it)?.title ?: it) } ?: "")
                else -> l.message ?: l.phase.name.lowercase()
            },
            onClick = null,
        )
        if (l != null && !l.active && l.id.isNotEmpty() && AppGraph.speed.load(l.id) != null) {
            OutlinedButton(shape = BtnShape, onClick = { nav.navigate(route("speedresult", l.id)) }, modifier = Modifier.fillMaxWidth()) { Text("Speed results and pictures") }
        }
    }
}

private fun <T> cycle(values: List<T>, cur: T): T = values[(values.indexOf(cur) + 1) % values.size]

private fun driverSub(d: DriverInfo): String =
    if (d.env.isNotBlank()) d.env.lines().filter { it.isNotBlank() }.joinToString("  ")
    else listOfNotNull(d.probe?.deviceName?.ifEmpty { null }, d.probe?.apiVersion?.ifEmpty { null }?.let { "Vk $it" }).joinToString(" · ").ifEmpty { if (d.isSystem) "Built-in" else d.libName }

@Composable
private fun VsCard(tag: String, name: String, sub: String, accent: Color, modifier: Modifier, dashed: Boolean = false, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(6.dp))
            .border(1.dp, if (dashed) MaterialTheme.colorScheme.outline else accent.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable(onClick = onClick).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(tag, fontFamily = Mono, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = accent)
        Text(name, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (dashed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
        Text(sub, fontFamily = Mono, fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ModeTab(text: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.fillMaxHeight().clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
            color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        if (on) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth(0.64f).height(2.dp).background(MaterialTheme.colorScheme.primary))
    }
}

@Composable
private fun StartButton(text: String, running: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled, shape = BtnShape,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        colors = if (running) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)
        else ButtonDefaults.buttonColors(),
        border = if (running) BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f)) else null,
    ) {
        Icon(if (running) Icons.Filled.Stop else Icons.Filled.PlayArrow, null)
        Spacer(Modifier.width(8.dp))
        Text(text, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ProgressLine(fraction: Float, text: String, onClick: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.weight(1f).height(4.dp), drawStopIndicator = {}, gapSize = 0.dp,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest)
        Text(text, fontFamily = Mono, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false))
    }
}

/** The four live tiles (two rows of two). */
@Composable
private fun ColumnScope.Tiles(nav: NavHostController, s: DashState, speedMode: Boolean, wide: Boolean) {
    val rowMod = if (wide) Modifier.weight(1f).fillMaxWidth() else Modifier.fillMaxWidth().height(170.dp)
    if (!speedMode) {
        Row(rowMod, horizontalArrangement = Arrangement.spacedBy(8.dp)) { PassTile(nav, s); RateTile(nav, s) }
        Row(rowMod, horizontalArrangement = Arrangement.spacedBy(8.dp)) { RepeatTile(s); GpuTile(s) }
    } else if (wide) {
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PictureTile(s, Modifier.weight(1.35f).fillMaxHeight(), fill = true)
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FrameTile(s, Modifier.weight(1f).fillMaxWidth())
                ScoreTile(s, Modifier.weight(1f).fillMaxWidth())
            }
        }
    } else {
        PictureTile(s, Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth().height(170.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FrameTile(s, Modifier.weight(1f).fillMaxHeight())
            ScoreTile(s, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
private fun Tile(modifier: Modifier, onClick: (() -> Unit)? = null, padding: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(6.dp)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .then(if (padding) Modifier.padding(10.dp) else Modifier),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

@Composable
private fun BigNumber(text: String, color: Color = MaterialTheme.colorScheme.onSurface, size: Int = 28) {
    Text(text, fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = size.sp, letterSpacing = (-0.8).sp, color = color, maxLines = 1)
}

@Composable
private fun RowScope.PassTile(nav: NavHostController, s: DashState) {
    val l = s.run
    val counts: Counts = when {
        l != null && (l.active || l.sessionId.isNotEmpty()) -> l.counts
        else -> s.lastSession?.counts ?: Counts()
    }
    val pct = if (counts.total > 0) "${counts.passed * 100 / counts.total}%" else "—"
    val whose = when {
        l != null && l.active -> "Live · ${l.driverName}"
        l != null && l.sessionId.isNotEmpty() -> "Last run · ${l.driverName}"
        s.lastSession != null -> "Last run · ${s.lastSession.driverName}"
        else -> "No runs yet"
    }
    Tile(Modifier.weight(1f).fillMaxHeight(), onClick = {
        when {
            l != null && l.active -> nav.navigate("run")
            l != null && l.sessionId.isNotEmpty() -> nav.navigate(route("session", l.sessionId))
            s.lastSession != null -> nav.navigate(route("session", s.lastSession.id))
        }
    }) {
        TileLabel("Pass rate")
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            ResultRing(counts, Modifier.size(84.dp), center = pct)
        }
        val sc = LocalStatusColors.current
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(Fmt.count(counts.passed.toLong()), fontFamily = Mono, fontSize = 11.sp, color = sc.passed)
            Text(Fmt.count((counts.failed + counts.crashed).toLong()), fontFamily = Mono, fontSize = 11.sp, color = sc.failed)
            Text(Fmt.count(counts.skipped.toLong()), fontFamily = Mono, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(whose, fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}

@Composable
private fun RowScope.RateTile(nav: NavHostController, s: DashState) {
    val now = s.rates.lastOrNull()
    Tile(Modifier.weight(1f).fillMaxHeight(), onClick = if (s.run != null) ({ nav.navigate("run") }) else null) {
        TileLabel("Tests / second")
        BigNumber(if (s.runActive && now != null) Fmt.count(now.toLong()) else "—", MaterialTheme.colorScheme.primary)
        Sparkline(s.rates, MaterialTheme.colorScheme.primary, Modifier.weight(1f).fillMaxWidth())
        Text(if (s.runActive) (s.run?.current?.removePrefix("dEQP-VK.") ?: "starting") else "Shown while tests run",
            fontFamily = Mono, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun RowScope.RepeatTile(s: DashState) {
    val rec = s.settings.record
    val l = s.run
    val flaky = s.lastSession?.takeIf { it.repeat > 1 }?.flaky
    Tile(Modifier.weight(1f).fillMaxHeight()) {
        TileLabel("Repeat · flaky")
        BigNumber(if (rec) "1×" else "${s.settings.repeat}×")
        Text(
            when {
                rec -> "Recording runs each test once"
                l != null && l.active && l.rounds > 1 -> "Run ${l.round} of ${l.rounds}"
                flaky != null -> if (flaky == 0) "Last run: no test changed result" else "Last run: $flaky tests sometimes fail"
                else -> "Flags tests that only sometimes fail"
            },
            fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
        )
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(shape = BtnShape, enabled = !rec && !s.runActive, onClick = { AppGraph.settings.update { it.copy(repeat = (it.repeat - 1).coerceAtLeast(1)) } },
                modifier = Modifier.weight(1f).height(36.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("−") }
            OutlinedButton(shape = BtnShape, enabled = !rec && !s.runActive, onClick = { AppGraph.settings.update { it.copy(repeat = (it.repeat + 1).coerceAtMost(10)) } },
                modifier = Modifier.weight(1f).height(36.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("+") }
        }
    }
}

@Composable
private fun RowScope.GpuTile(s: DashState) {
    val sc = LocalStatusColors.current
    val l = s.run
    val (big, note) = when {
        !s.settings.gpuCheck -> "off" to "Needs root: allow DriverLab in Magisk › Superuser"
        l != null && (l.active || l.sessionId.isNotEmpty()) && l.gpuCheck == "on" -> Fmt.count(l.gpuErrorLines.toLong()) to "GPU errors in the kernel log"
        l != null && l.gpuCheck.isNotEmpty() && l.gpuCheck != "on" -> "—" to l.gpuCheck
        s.lastSession?.gpuCheck == "on" -> Fmt.count(s.lastSession.gpuErrorLines.toLong()) to "GPU errors in the last run"
        else -> "—" to "Checked while tests run"
    }
    val color = when {
        big == "off" || big == "—" -> MaterialTheme.colorScheme.onSurfaceVariant
        big == "0" -> sc.passed
        else -> sc.failed
    }
    Tile(Modifier.weight(1f).fillMaxHeight()) {
        TileLabel("GPU health")
        BigNumber(big, color)
        Text(note, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Check", fontSize = 12.sp, modifier = Modifier.weight(1f))
            Switch(checked = s.settings.gpuCheck, onCheckedChange = { v -> AppGraph.settings.update { it.copy(gpuCheck = v) } })
        }
    }
}

@Composable
private fun PictureTile(s: DashState, modifier: Modifier, fill: Boolean = false) {
    val dc = LocalDriverColors.current
    val l = s.speed
    val isB = l != null && l.bId != null && l.driverName == s.b?.name && s.b?.id == l.bId
    val accent = if (isB) dc.b else dc.a
    var big by remember { mutableStateOf(false) }
    Tile(modifier, padding = false) {
        Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TileLabel("Live picture" + (l?.scene?.takeIf { s.speedActive }?.let { " · " + (SpeedScenes.byId(it)?.title ?: it) } ?: ""), Modifier.weight(1f))
            if (s.speedActive) Text((if (isB) "B · " else "A · ") + (l?.driverName ?: ""), fontFamily = Mono, fontSize = 11.sp, color = accent,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        }
        Box((if (fill) Modifier.fillMaxWidth().weight(1f) else Modifier.fillMaxWidth().aspectRatio(16f / 9f)).padding(top = 2.dp)) {
            if (s.speedActive) {
                PreviewWindow(AppGraph.speed.previewFile, Modifier.fillMaxSize().clickable { big = true }, rounded = false)
                val ms = l?.ft?.lastOrNull()
                if (ms != null && ms > 0) {
                    Text(
                        if (l?.scene == "pipelines") "%.1f ms".format(ms) else Fmt.count((1000 / ms).toLong()) + " fps",
                        fontFamily = Mono, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).background(Color(0xB8000000), RoundedCornerShape(3.dp))
                            .border(1.dp, Color(0xFF2A3140), RoundedCornerShape(3.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            } else {
                LastCard(s.lastSpeed, Modifier.fillMaxSize())
            }
        }
    }
    if (big && s.speedActive) {
        FullScreenPicture(onClose = { big = false }) { PreviewWindow(AppGraph.speed.previewFile, Modifier.fillMaxSize(), rounded = false) }
    }
}

/** The test card of the last speed test, or a hint when there is none. */
@Composable
private fun LastCard(r: SpeedResult?, modifier: Modifier) {
    val file = r?.let { rr -> rr.pictures.firstOrNull()?.let { File(AppGraph.speed.dir(rr.id), it.file) } }
    val img by produceState<ImageBitmap?>(null, file) {
        value = file?.takeIf { it.exists() }?.let { f -> withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(f.absolutePath)?.asImageBitmap() }.getOrNull() } }
    }
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        val i = img
        if (i != null) {
            Image(i, "Test card from the last speed test", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            Text("last test", fontFamily = Mono, fontSize = 10.sp, color = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).background(Color(0xB8000000), RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
        } else {
            Text("The picture shows here while the speed test runs", fontSize = 12.sp, color = Color(0xFFB0B8C4))
        }
    }
}

@Composable
private fun FrameTile(s: DashState, modifier: Modifier) {
    val dc = LocalDriverColors.current
    val l = s.speed
    val isB = l != null && l.bId != null && s.b?.id == l.bId && l.driverName == s.b?.name
    val color = if (isB) dc.b else dc.a
    val ms = l?.ft?.lastOrNull()
    Tile(modifier) {
        TileLabel("Frame time")
        BigNumber(if (s.speedActive && ms != null) "%.2f ms".format(ms) else "—", color, size = 22)
        Sparkline(if (s.speedActive) l?.ft.orEmpty() else emptyList(), color, Modifier.weight(1f).fillMaxWidth())
    }
}

@Composable
private fun ScoreTile(s: DashState, modifier: Modifier) {
    val dc = LocalDriverColors.current
    val l = s.speed
    // Live: verdicts from the rounds so far. Idle: the last finished speed test.
    val finished = s.lastSpeed?.takeIf { l == null || (!l.active && it.id == l.id) }
    val result: SpeedResult? = if (finished == null && l != null && l.samples.isNotEmpty() && l.bId != null) {
        val req = SpeedRequest(l.aId, l.bId, SpeedScenes.all.map { it.id }.filter { id -> l.samples.any { it.scene == id } }, l.rounds, 0, 0, false)
        SpeedResult(l.id, l.startedAt, 0, "running", req, listOf(SpeedDriver(l.aId, "A", "", "", ""), SpeedDriver(l.bId, "B", "", "", "")), l.samples, emptyList(), "")
    } else (finished ?: s.lastSpeed)?.takeIf { it.drivers.size == 2 }
    val rows = result?.let { SpeedMath.rows(it) }.orEmpty().filter { r -> r.stats.size == 2 && r.stats.values.all { it.runs > 0 } }
    val aId = result?.drivers?.getOrNull(0)?.id
    val bId = result?.drivers?.getOrNull(1)?.id
    val pics = result?.pictures.orEmpty()
    Tile(modifier) {
        TileLabel(if (l != null && l.active) "Score so far" else "Last speed test")
        ScoreLine("A faster", rows.count { it.clear && it.winner == aId }, dc.a)
        ScoreLine("B faster", rows.count { it.clear && it.winner == bId }, dc.b)
        ScoreLine("No clear gap", rows.count { !it.clear }, MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        if (pics.isNotEmpty()) {
            val bad = pics.count { it.share >= 0 && !it.matches }
            val sc = LocalStatusColors.current
            Text(if (bad == 0) "pictures match" else "$bad pictures differ", fontFamily = Mono, fontSize = 10.5.sp,
                color = if (bad == 0) sc.passed else sc.failed,
                modifier = Modifier.border(1.dp, (if (bad == 0) sc.passed else sc.failed).copy(alpha = 0.6f), RoundedCornerShape(3.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
        } else if (result == null) {
            Text("Needs two drivers", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ScoreLine(label: String, n: Int, color: Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = color, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text("$n", fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
    }
}

/** The last few runs and speed tests. */
@Composable
private fun RecentBlock(nav: NavHostController, sessions: List<SessionMeta>, speed: List<SpeedResult>) {
    data class Item(val at: Long, val title: String, val sub: String, val tag: String, val tagColor: Color, val bar: Color, val open: () -> Unit)
    val sc = LocalStatusColors.current
    val primary = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val items = (sessions.take(6).map { m ->
        val problems = m.counts.failed + m.counts.crashed
        val tag = when {
            m.gpuErrorTests > 0 -> "GPU errors ${m.gpuErrorTests}" to sc.failed
            m.flaky > 0 -> "${m.flaky} sometimes fail" to sc.warning
            problems > 0 -> "$problems failed" to sc.failed
            m.state == SessionState.FINISHED -> "all passed" to sc.passed
            else -> m.state.name.lowercase() to dim
        }
        Item(m.startedAt, "${m.label} · ${m.driverName}", "${Fmt.time(m.startedAt)} · ${Fmt.count(m.counts.total.toLong())} tests" + (if (m.repeat > 1) " ×${m.repeat}" else ""),
            tag.first, tag.second, if (problems > 0) sc.failed else sc.passed) { nav.navigate(route("session", m.id)) }
    } + speed.take(3).map { r ->
        val bad = r.pictures.count { it.share >= 0 && !it.matches }
        Item(r.startedAt, "Speed test · " + r.drivers.joinToString(" vs ") { it.name }, "${Fmt.time(r.startedAt)} · ${Fmt.plural(r.request.scenes.size, "scene")} · ${Fmt.plural(r.request.rounds, "round")}",
            if (r.pictures.isEmpty()) r.state else if (bad == 0) "pictures match" else "$bad pictures differ", if (bad == 0) sc.passed else sc.failed, primary) {
            nav.navigate(route("speedresult", r.id))
        }
    }).sortedByDescending { it.at }.take(4)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), border = cardLine()) {
        Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TileLabel("Recent", Modifier.weight(1f))
            TextButton(onClick = { nav.navigate("results") }) { Text("All results") }
        }
        if (items.isEmpty()) {
            Text("Nothing yet. Pick a test set and press Run.", fontSize = 12.sp, color = dim, modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 12.dp))
        }
        for (item in items) {
            androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().clickable(onClick = item.open).padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.width(4.dp).height(28.dp).clip(RoundedCornerShape(2.dp)).background(item.bar))
                Column(Modifier.weight(1f)) {
                    Text(item.title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(item.sub, fontFamily = Mono, fontSize = 10.5.sp, color = dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(item.tag, fontFamily = Mono, fontSize = 10.5.sp, color = item.tagColor, maxLines = 1,
                    modifier = Modifier.border(1.dp, item.tagColor.copy(alpha = 0.55f), RoundedCornerShape(3.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
            }
        }
    }
}

@Composable
private fun DriverPickDialog(which: Char, drivers: List<DriverInfo>, a: DriverInfo?, b: DriverInfo?, onDismiss: () -> Unit, onPick: (String?) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Driver $which") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (which == 'B') Text("Optional. Runs and speed tests use both drivers, then compare them.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val choices: List<DriverInfo?> = if (which == 'B') listOf<DriverInfo?>(null) + drivers.filter { it.id != a?.id } else drivers
                for (d in choices) {
                    val sel = if (which == 'A') d?.id == a?.id else d?.id == b?.id
                    Row(
                        Modifier.fillMaxWidth().selectable(sel) { onPick(d?.id) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = sel, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(d?.name ?: "No driver B", style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(d?.let { driverSub(it) } ?: "Test one driver", fontFamily = Mono, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun ScenesDialog(current: String, onDismiss: () -> Unit) {
    var sel by remember {
        mutableStateOf(current.split(',').map { it.trim() }.filter { id -> SpeedScenes.byId(id) != null }.ifEmpty { SpeedScenes.all.map { it.id } }.toSet())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Speed test scenes") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for (sc in SpeedScenes.all) {
                    Row(Modifier.fillMaxWidth().clickable { sel = if (sc.id in sel) sel - sc.id else sel + sc.id }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = sc.id in sel, onCheckedChange = null)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(sc.title, style = MaterialTheme.typography.bodyLarge)
                            Text(sc.what, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = sel.isNotEmpty(), onClick = {
                val ids = SpeedScenes.all.map { it.id }.filter { it in sel }
                AppGraph.settings.update { it.copy(speedScenes = if (ids.size == SpeedScenes.all.size) "" else ids.joinToString(",")) }
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun SessionCard(s: SessionMeta, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val running = s.state == SessionState.RUNNING && AppGraph.runs.live.value?.sessionId == s.id && AppGraph.runs.isActive
    Card(
        modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), border = cardLine(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.driverName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${Fmt.dateTime(s.startedAt)} · ${s.label}", fontFamily = Mono, fontSize = 11.sp,
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
                    fontFamily = Mono, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            SegmentBar(s.counts, height = 6.dp)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CountsRow(s.counts, Modifier.weight(1f))
                if (s.record) Icon(Icons.Filled.FiberManualRecord, "Recorded", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}
