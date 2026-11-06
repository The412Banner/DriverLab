package com.driverlab.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.driverlab.AppGraph
import com.driverlab.BuildConfig
import com.driverlab.core.CtsSetup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.OutlinedButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(nav: NavHostController) {
    val s by AppGraph.settings.state.collectAsStateWithLifecycle()
    val setup by AppGraph.setup.state.collectAsStateWithLifecycle()
    Scaffold(topBar = { AppBar("Settings", onBack = { nav.popBackStack() }) }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { SectionTitle("Look") }
            item {
                SettingCard("Theme", "Follows the system unless you pick one") {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        listOf("System", "Light", "Dark").forEachIndexed { i, label ->
                            SegmentedButton(
                                selected = s.theme == i,
                                onClick = { AppGraph.settings.update { it.copy(theme = i) } },
                                shape = SegmentedButtonDefaults.itemShape(i, 3),
                            ) { Text(label) }
                        }
                    }
                }
            }
            item {
                SwitchRow("Wallpaper colours", "Use Material You colours instead of DriverLab's", s.dynamicColor) { v ->
                    AppGraph.settings.update { it.copy(dynamicColor = v) }
                }
            }
            item { SectionTitle("Running tests") }
            item {
                SettingCard("Time limit per test", "${s.timeoutSec} s — a test that takes longer is stopped and marked Timeout") {
                    Slider(
                        value = s.timeoutSec.toFloat(),
                        onValueChange = { v -> AppGraph.settings.update { it.copy(timeoutSec = (v / 10).roundToInt() * 10) } },
                        valueRange = 10f..600f,
                    )
                }
            }
            item {
                SettingCard("Tests per CTS process", "${s.batchSize} — larger is faster; a crash only restarts the rest of its batch") {
                    Slider(
                        value = s.batchSize.toFloat(),
                        onValueChange = { v -> AppGraph.settings.update { it.copy(batchSize = (v / 25).roundToInt().coerceAtLeast(1) * 25) } },
                        valueRange = 25f..2000f,
                    )
                }
            }
            item {
                val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
                val jobs = s.jobs.coerceIn(1, cores)
                SettingCard(
                    "Test programs at once",
                    (if (jobs == 1) "1 — one after another" else "$jobs — up to $jobs CPU cores at a time (this phone has $cores)") +
                        ". More is faster on big runs; test times are less comparable and each program needs memory. " +
                        "Recording always runs one at a time.",
                ) {
                    if (cores > 1) Slider(
                        value = jobs.toFloat(),
                        onValueChange = { v -> AppGraph.settings.update { it.copy(jobs = v.roundToInt().coerceIn(1, cores)) } },
                        valueRange = 1f..cores.toFloat(),
                        steps = (cores - 2).coerceAtLeast(0),
                    )
                }
            }
            item {
                SwitchRow("Keep screen on", "While a run is shown on screen", s.keepScreenOn) { v -> AppGraph.settings.update { it.copy(keepScreenOn = v) } }
            }
            item { SectionTitle("Recording") }
            item {
                SwitchRow("Record GPU commands", "Each test runs on its own and its command stream is saved as .rd", s.record) { v ->
                    AppGraph.settings.update { it.copy(record = v) }
                }
            }
            item {
                SwitchRow(
                    "Full kernel log",
                    "When recording the system driver, each test's log lists every kernel call with its data, in the same format as the adreno-cmdstream-gather app (logs can be several MB). Off: a short summary.",
                    s.rawKgslLog,
                ) { v -> AppGraph.settings.update { it.copy(rawKgslLog = v) } }
            }
            item {
                SettingCard("Capture size", s.captureLabel + ". The command streams are always kept; this limits other buffers such as textures.") {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        listOf("Light", "Standard", "Full").forEachIndexed { i, label ->
                            SegmentedButton(
                                selected = s.captureLevel == i,
                                onClick = { AppGraph.settings.update { it.copy(captureLevel = i) } },
                                shape = SegmentedButtonDefaults.itemShape(i, 3),
                            ) { Text(label) }
                        }
                    }
                }
            }
            item { SectionTitle("Saving") }
            item {
                SwitchRow("Save to Downloads", "After each run: Download/DriverLab/<date>/ gets the full zip, the test_results zip and the readable log", s.exportToDownloads) { v ->
                    AppGraph.settings.update { it.copy(exportToDownloads = v) }
                }
            }
            item {
                SettingCard("Per-test log files", "Written for every test when recording, or when a run has up to ${"%,d".format(s.perTestLogLimit)} tests") {
                    Slider(
                        value = s.perTestLogLimit.toFloat(),
                        onValueChange = { v -> AppGraph.settings.update { it.copy(perTestLogLimit = (v / 500).roundToInt().coerceAtLeast(1) * 500) } },
                        valueRange = 500f..50000f,
                    )
                }
            }
            item { SectionTitle("Driver inspector") }
            item {
                SwitchRow("Find names files online", "Real function names for Banners-Turnip drivers from github.com. Only the file's build ID is sent.", s.onlineNames == 1) { v ->
                    AppGraph.settings.update { it.copy(onlineNames = if (v) 1 else 2) }
                }
            }
            if (s.onlineNames == 1) item { NamesPackCard() }
            item { SectionTitle("About") }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), border = cardLine()) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Logo(44.dp)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text("DriverLab ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium)
                                Text("Vulkan CTS ${(setup as? CtsSetup.State.Ready)?.tag ?: AppGraph.catalog.ctsTag}",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(CREDITS, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

private const val CREDITS =
    "Runs the Khronos Vulkan Conformance Test Suite (VK-GL-CTS, Apache 2.0) on the phone's driver or on a driver you import.\n\n" +
        "Register names come from Mesa's freedreno register database (MIT). The .rd capture format and the idea of " +
        "recording the Qualcomm driver come from Rob Clark's freedreno tools (MIT). Turnip recordings use Mesa's own FD_RD_DUMP.\n\n" +
        "Inspired by the adreno-cmdstream-gather app by Huggo, shared by vauzi (Vauzi-17), whose Adreno 710/720/722 work " +
        "started this. Built for Banners-Turnip by The412Banner."

/** One-time download of the names files of all imported drivers, so the inspector has them offline. */
@Composable
private fun NamesPackCard() {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    SettingCard("Names for all my drivers", status ?: "Downloads them once; after that they work offline") {
        OutlinedButton(enabled = !busy, onClick = {
            busy = true
            scope.launch {
                val found = runCatching {
                    AppGraph.inspector.downloadAllNames { done, total, found -> status = "Checked $done of $total files · $found found" }
                }
                status = found.fold({ n -> if (n == 0) "Nothing new found" else "$n names files saved" }, { e -> "Stopped: ${e.message}" })
                busy = false
            }
        }) { Text(if (busy) "Downloading…" else "Download") }
    }
}

@Composable
private fun SettingCard(title: String, sub: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), border = cardLine()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun SwitchRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), border = cardLine()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked, onChange)
        }
    }
}

@Composable
fun SetupScreen(state: CtsSetup.State) {
    val scope = rememberCoroutineScope()
    val pulse by rememberInfiniteTransition(label = "setup").animateFloat(0.94f, 1.06f, infiniteRepeatable(tween(900, easing = LinearEasing), androidx.compose.animation.core.RepeatMode.Reverse), label = "pulse")
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.scale(pulse)) { Logo(96.dp) }
            Spacer(Modifier.height(24.dp))
            Text("DriverLab", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(12.dp))
            when (state) {
                is CtsSetup.State.Working -> {
                    Text(state.message, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth(0.7f))
                }
                is CtsSetup.State.Failed -> {
                    androidx.compose.material3.Icon(Icons.Filled.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
                    Text(state.message, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    Button(shape = BtnShape, onClick = { scope.launch(Dispatchers.IO) { AppGraph.setup.ensure() } }) { Text("Try again") }
                }
                else -> LinearProgressIndicator(Modifier.fillMaxWidth(0.7f))
            }
        }
    }
}
