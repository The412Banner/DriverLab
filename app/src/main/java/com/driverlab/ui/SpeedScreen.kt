package com.driverlab.ui

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.driverlab.AppGraph
import com.driverlab.core.DriverInfo
import com.driverlab.core.Fmt
import com.driverlab.core.SpeedManager
import com.driverlab.core.SpeedMath
import com.driverlab.core.SpeedRequest
import com.driverlab.core.SpeedResult
import com.driverlab.core.SpeedScenes
import com.driverlab.ui.theme.MonoSmall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Speed test: pick drivers and scenes, run, watch the picture, read the verdicts. */
@Composable
fun SpeedScreen(nav: NavHostController) {
    val drivers by AppGraph.drivers.drivers.collectAsStateWithLifecycle()
    val settings by AppGraph.settings.state.collectAsStateWithLifecycle()
    val live by AppGraph.speed.live.collectAsStateWithLifecycle()
    val past by AppGraph.speed.results.collectAsStateWithLifecycle()
    val runLive by AppGraph.runs.live.collectAsStateWithLifecycle()
    var driverA by rememberSaveable { mutableStateOf(settings.driverId) }
    var driverB by rememberSaveable { mutableStateOf(settings.secondDriverId.takeIf { it != settings.driverId }.orEmpty()) }
    var scenes by rememberSaveable { mutableStateOf(SpeedScenes.all.map { it.id }) }
    var rounds by rememberSaveable { mutableStateOf(3) }
    var seconds by rememberSaveable { mutableStateOf(5) }
    var pause by rememberSaveable { mutableStateOf(10) }
    var preview by rememberSaveable { mutableStateOf(true) }
    var picking by remember { mutableStateOf<Char?>(null) }
    val l = live
    val a = drivers.firstOrNull { it.id == driverA } ?: drivers.firstOrNull { it.isSystem }
    val b = drivers.firstOrNull { it.id == driverB && it.id != a?.id }
    val busyElsewhere = runLive?.active == true

    Scaffold(topBar = { AppBar("Speed test", subtitle = "Small Vulkan scenes, same work on every driver", onBack = { nav.popBackStack() }) }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (l != null) {
                item { SpeedLiveCard(l, nav) }
            }
            if (l?.active != true) {
                item {
                    Text(
                        "Runs the same small drawing and compute jobs on one or two drivers and measures how fast each one is. " +
                            "With two drivers they take turns (A then B, then B then A) so neither always gets the cooler phone. " +
                            "A gap is only called a win when it is bigger than the normal variation between rounds.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item { SectionTitle("Drivers") }
                item { DriverPickRow("A", a?.name ?: "System driver") { picking = 'A' } }
                item { DriverPickRow("B", b?.name ?: "None (test one driver)") { picking = 'B' } }
                item { SectionTitle("Scenes") }
                items(SpeedScenes.all, key = { it.id }) { s ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .clickable { scenes = if (s.id in scenes) scenes - s.id else SpeedScenes.all.map { it.id }.filter { it in scenes || it == s.id } }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = s.id in scenes, onCheckedChange = null)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.title, style = MaterialTheme.typography.bodyLarge)
                            Text(s.what, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item { SectionTitle("How long") }
                item { ChoiceRow("Rounds per driver", listOf(1, 3, 5), rounds, { "$it" }) { rounds = it } }
                item { ChoiceRow("Seconds per scene", listOf(3, 5, 10), seconds, { "$it s" }) { seconds = it } }
                item { ChoiceRow("Pause between runs", listOf(0, 10, 30), pause, { if (it == 0) "None" else "$it s" }) { pause = it } }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Show the picture", style = MaterialTheme.typography.bodyLarge)
                            Text("A small window shows what the driver draws, a few times a second (not counted in the times).",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = preview, onCheckedChange = { preview = it })
                    }
                }
                item {
                    val perRun = scenes.size * (seconds + 1)
                    val runsTotal = rounds * (if (b != null) 2 else 1)
                    val est = runsTotal * (perRun + 3) + (runsTotal - 1).coerceAtLeast(0) * pause
                    Button(
                        onClick = {
                            val req = SpeedRequest(a?.id ?: DriverInfo.SYSTEM_ID, b?.id, SpeedScenes.all.map { it.id }.filter { it in scenes },
                                rounds, seconds, pause, preview, settings.exportToDownloads)
                            AppGraph.speed.start(req)
                        },
                        enabled = scenes.isNotEmpty() && !busyElsewhere,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Icon(Icons.Filled.Speed, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (busyElsewhere) "A test run is in progress" else "Start · about ${Fmt.duration(est * 1000L)}", maxLines = 1)
                    }
                }
            }
            if (past.isNotEmpty()) {
                item { SectionTitle("Earlier speed tests") }
                items(past.take(30), key = { "p" + it.id }) { r ->
                    Card(
                        Modifier.fillMaxWidth().clickable { nav.navigate(route("speedresult", r.id)) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text(r.drivers.joinToString(" vs ") { it.name }, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${Fmt.dateTime(r.startedAt)} · ${r.request.scenes.size} scenes · ${r.request.rounds} rounds · ${r.state}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
    picking?.let { which ->
        AlertDialog(
            onDismissRequest = { picking = null },
            title = { Text("Driver $which") },
            text = {
                Column {
                    val choices: List<DriverInfo?> = if (which == 'B') listOf<DriverInfo?>(null) + drivers.filter { it.id != a?.id } else drivers
                    for (d in choices) {
                        val sel = if (which == 'A') d?.id == a?.id else (d?.id ?: "") == (b?.id ?: "")
                        Row(
                            Modifier.fillMaxWidth().selectable(sel) {
                                if (which == 'A') { driverA = d?.id ?: DriverInfo.SYSTEM_ID; if (driverB == driverA) driverB = "" } else driverB = d?.id.orEmpty()
                                picking = null
                            }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = sel, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(d?.name ?: "None", style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (d != null && d.env.isNotBlank()) Text(d.env.lines().filter { it.isNotBlank() }.joinToString("  "),
                                    style = MonoSmall, color = MaterialTheme.colorScheme.tertiary, maxLines = 2)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picking = null }) { Text("Close") } },
        )
    }
}

@Composable
private fun DriverPickRow(label: String, name: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(Modifier.width(12.dp))
            Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("Change", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun ChoiceRow(label: String, options: List<Int>, value: Int, text: (Int) -> String, onPick: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (o in options) FilterChip(value == o, { onPick(o) }, label = { Text(text(o)) })
        }
    }
}

@Composable
private fun SpeedLiveCard(l: SpeedManager.Live, nav: NavHostController) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp).animateContentSize()) {
            Text(
                when (l.phase) {
                    SpeedManager.Phase.STARTING -> "Starting…"
                    SpeedManager.Phase.RUNNING -> "Testing ${l.driverName}"
                    SpeedManager.Phase.PAUSE -> "Pause"
                    SpeedManager.Phase.DONE -> "Finished"
                    SpeedManager.Phase.STOPPED -> "Stopped"
                    SpeedManager.Phase.FAILED -> "Could not finish"
                },
                style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            val detail = listOfNotNull(
                if (l.active) "Round ${l.round} of ${l.rounds}" else null,
                l.scene?.takeIf { l.active }?.let { SpeedScenes.byId(it)?.title ?: it },
                l.message,
            ).joinToString(" · ")
            if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(progress = { l.fraction }, modifier = Modifier.fillMaxWidth().height(6.dp))
            AnimatedVisibility(l.active, enter = fadeIn(), exit = fadeOut()) {
                Column {
                    Spacer(Modifier.height(12.dp))
                    PreviewWindow(AppGraph.speed.previewFile)
                }
            }
            if (l.samples.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                for (s in l.samples.takeLast(6).reversed()) {
                    val d = AppGraph.drivers.get(s.driverId)?.name ?: s.driverId
                    Text(
                        "${SpeedScenes.byId(s.scene)?.title ?: s.scene} · $d · " + if (s.error.isNotEmpty()) s.error else SpeedMath.valueText(s.value, s.unit) +
                            if (s.check == "differs") " · picture looks wrong" else "",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            if (l.active) {
                OutlinedButton(onClick = { AppGraph.speed.stop() }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Filled.Stop, null); Spacer(Modifier.width(6.dp)); Text("Stop")
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (l.id.isNotEmpty() && AppGraph.speed.load(l.id) != null) {
                        Button(onClick = { nav.navigate(route("speedresult", l.id)) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) { Text("Results") }
                    }
                    OutlinedButton(onClick = { AppGraph.speed.dismiss() }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) { Text("New test") }
                }
            }
        }
    }
}

/** The small live picture: the program writes a downscaled frame to a file a few times a second. */
@Composable
private fun PreviewWindow(file: File) {
    val image by produceState<ImageBitmap?>(null, file) {
        var last = 0L
        while (true) {
            val t = file.lastModified()
            if (t != 0L && t != last) {
                last = t
                withContext(Dispatchers.IO) { readPreview(file) }?.let { value = it }
            }
            delay(200)
        }
    }
    Box(
        Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        val img = image
        if (img != null) Image(img, "What the driver draws", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        else Text("The picture appears when a drawing scene runs", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
    }
}

/** Header (uint32 LE width, height) + RGBA rows. */
private fun readPreview(f: File): ImageBitmap? = runCatching {
    val bytes = f.readBytes()
    if (bytes.size < 8) return null
    val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val w = bb.getInt(0)
    val h = bb.getInt(4)
    if (w !in 1..4096 || h !in 1..4096 || bytes.size < 8 + w * h * 4) return null
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    bmp.copyPixelsFromBuffer(ByteBuffer.wrap(bytes, 8, w * h * 4))
    bmp.asImageBitmap()
}.getOrNull()

@Composable
fun SpeedResultScreen(nav: NavHostController, id: String) {
    val context = LocalContext.current
    val result by produceState<SpeedResult?>(null, id) { value = withContext(Dispatchers.IO) { AppGraph.speed.load(id) } }
    var confirmDelete by remember { mutableStateOf(false) }
    val r = result
    Scaffold(topBar = {
        AppBar("Speed test", subtitle = r?.let { Fmt.dateTime(it.startedAt) }, onBack = { nav.popBackStack() }, actions = {
            val report = File(AppGraph.speed.dir(id), "report.txt")
            if (report.exists()) IconButton(onClick = { context.startActivity(AppGraph.sessions.shareIntent(listOf(report))) }) { Icon(Icons.Filled.Share, "Share") }
            IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete") }
        })
    }) { pad ->
        if (r == null) { LinearProgressIndicator(Modifier.fillMaxWidth().padding(pad)); return@Scaffold }
        val rows = remember(r) { SpeedMath.rows(r) }
        val letters = listOf("A", "B")
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                    Column(Modifier.padding(16.dp)) {
                        r.drivers.forEachIndexed { i, d ->
                            Text("${letters[i]}  ${d.name}", style = MaterialTheme.typography.titleSmall)
                            val sub = listOf(d.gpu, d.version).filter { it.isNotEmpty() }.joinToString(" · ")
                            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (d.env.isNotBlank()) Text(d.env.lines().filter { it.isNotBlank() }.joinToString("  "), style = MonoSmall, color = MaterialTheme.colorScheme.tertiary)
                            Spacer(Modifier.height(4.dp))
                        }
                        Text("${r.request.rounds} rounds · ${r.request.seconds} s per scene · ${r.request.pauseSec} s pause · ${r.state}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val t = r.temps.map { it.second }.filter { !it.isNaN() }
                        if (t.isNotEmpty()) Text(String.format(java.util.Locale.US, "Battery %.1f → %.1f °C (highest %.1f °C)", t.first(), t.last(), t.max()),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (r.message.isNotEmpty()) Text(r.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            items(rows, key = { it.scene }) { row ->
                val scene = SpeedScenes.byId(row.scene)
                Card(colors = CardDefaults.cardColors(containerColor = if (row.clear) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)) {
                    Column(Modifier.padding(14.dp)) {
                        Text(scene?.title ?: row.scene, style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        r.drivers.forEachIndexed { i, d ->
                            Row {
                                Text(letters[i], style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, modifier = Modifier.width(22.dp))
                                Text(SpeedMath.statText(row.stats[d.id]), style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (row.winner == d.id) FontWeight.Bold else FontWeight.Normal)
                            }
                        }
                        if (row.verdict.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text(row.verdict, style = MaterialTheme.typography.bodySmall,
                                color = if (row.clear) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item {
                Text("Each number is the middle value of the rounds; ±% is how much the rounds varied. A gap smaller than that, " +
                    "or under 3%, counts as no clear difference. The full numbers for every run are in the shared report.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this speed test?") },
            text = { Text("Removes it from DriverLab. A report already saved in Download/DriverLab stays there.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; AppGraph.speed.delete(id); nav.popBackStack() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
