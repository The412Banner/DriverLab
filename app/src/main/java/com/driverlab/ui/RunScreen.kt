package com.driverlab.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.driverlab.AppGraph
import com.driverlab.core.Bucket
import com.driverlab.core.Fmt
import com.driverlab.core.RunManager
import com.driverlab.ui.theme.LocalStatusColors
import com.driverlab.ui.theme.Mono
import kotlinx.coroutines.delay

@Composable
fun RunScreen(nav: NavHostController) {
    val live by AppGraph.runs.live.collectAsStateWithLifecycle()
    val settings by AppGraph.settings.state.collectAsStateWithLifecycle()
    val l = live
    val view = LocalView.current
    DisposableEffect(l?.active, settings.keepScreenOn) {
        view.keepScreenOn = settings.keepScreenOn && l?.active == true
        onDispose { view.keepScreenOn = false }
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(l?.active) {
        while (l?.active == true) { now = System.currentTimeMillis(); delay(500) }
        now = System.currentTimeMillis()
    }

    Scaffold(topBar = { AppBar(if (l?.active == true) "Running tests" else "Run", subtitle = l?.driverName, onBack = { nav.popBackStack() }) }) { pad ->
        if (l == null) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { Text("No run yet") }
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    ProgressRing(l.counts, l.fraction, l.active, Modifier.size(230.dp), stroke = 16.dp) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            AnimatedContent(
                                targetState = (l.fraction * 100).toInt(),
                                transitionSpec = { (slideInVertically { it / 3 } + fadeIn()) togetherWith (slideOutVertically { -it / 3 } + fadeOut()) },
                                label = "pct",
                            ) { pct -> Text("$pct%", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold) }
                            Text("${Fmt.count(l.done)} of ${Fmt.count(l.total)}", style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    AnimatedContent(targetState = l.phase, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "phase") { ph ->
                        Text(
                            when (ph) {
                                RunManager.Phase.STARTING -> "Starting…"
                                RunManager.Phase.RUNNING -> "Testing ${l.driverName}"
                                RunManager.Phase.FINISHING -> "Saving results and logs…"
                                RunManager.Phase.DONE -> "Finished"
                                RunManager.Phase.STOPPED -> "Stopped"
                                RunManager.Phase.FAILED -> "Could not finish"
                            },
                            style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
                        )
                    }
                    l.message?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    }
                    val parts = listOfNotNull(
                        if (l.steps > 1) "Driver ${l.step} of ${l.steps}" else null,
                        if (l.rounds > 1) "Run ${l.round} of ${l.rounds}" else null,
                        when {
                            l.gpuCheck == "on" -> if (l.gpuErrorLines == 0) "No GPU errors" else "GPU errors: ${l.gpuErrorLines}"
                            l.gpuCheck.isNotEmpty() -> "GPU error check off: ${l.gpuCheck}"
                            else -> null
                        },
                    )
                    if (parts.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(parts.joinToString(" · "), style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center,
                            color = if (l.gpuErrorLines > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary)
                    }
                }
            }
            item { StatGrid(l) }
            item {
                val elapsed = now - l.startedAt
                val rate = if (elapsed > 2000 && l.done > 0) l.done * 1000.0 / elapsed else 0.0
                val eta = if (rate > 0 && l.active) ((l.total - l.done) / rate * 1000).toLong() else 0L
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), border = cardLine()) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Metric("Elapsed", Fmt.duration(elapsed))
                        Metric("Speed", if (rate >= 1) String.format(java.util.Locale.US, "%.1f/s", rate) else if (rate > 0) String.format(java.util.Locale.US, "%.0f/min", rate * 60) else "—")
                        Metric("Left", if (eta > 0) Fmt.duration(eta) else "—")
                    }
                }
            }
            item {
                AnimatedVisibility(l.active && l.current != null, enter = fadeIn(), exit = fadeOut()) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), border = cardLine()) {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(if (l.running > 1) "Now running · ${l.running} at once" else "Now running", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.weight(1f))
                                if (l.record) {
                                    Icon(Icons.Filled.FiberManualRecord, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.error)
                                    Spacer(Modifier.width(4.dp))
                                    Text("recording", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            AnimatedContent(
                                targetState = l.current.orEmpty(),
                                transitionSpec = { (slideInVertically(tween(200)) { it } + fadeIn(tween(200))) togetherWith (slideOutVertically(tween(160)) { -it } + fadeOut(tween(160))) },
                                label = "current",
                            ) { name ->
                                Text(name.removePrefix("dEQP-VK."), fontFamily = Mono, style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            }
                            if (l.currentSince > 0) {
                                Text(Fmt.duration((now - l.currentSince).coerceAtLeast(0)), style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer)
                            }
                        }
                    }
                }
            }
            item {
              Column {
                AnimatedVisibility(!l.active && l.pairIds.size == 2, enter = fadeIn() + scaleIn(initialScale = 0.95f)) {
                    Button(shape = BtnShape, onClick = { nav.navigate(route("compare", l.pairIds[0], l.pairIds[1])) }, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp), shape = RoundedCornerShape(6.dp)) {
                        Icon(Icons.AutoMirrored.Filled.CompareArrows, null); Spacer(Modifier.width(6.dp)); Text("Compare the two drivers")
                    }
                }
                AnimatedVisibility(!l.active, enter = fadeIn() + scaleIn(initialScale = 0.95f)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(shape = BtnShape, onClick = { if (l.sessionId.isNotEmpty()) nav.navigate(route("session", l.sessionId)) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(6.dp)) {
                            Icon(Icons.Filled.Assessment, null); Spacer(Modifier.width(6.dp)); Text("Results")
                        }
                        OutlinedButton(shape = BtnShape, onClick = { if (l.sessionId.isNotEmpty()) nav.navigate(route("folder", l.sessionId, ".")) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(6.dp)) {
                            Icon(Icons.Filled.Folder, null); Spacer(Modifier.width(6.dp)); Text("Files")
                        }
                    }
                }
                AnimatedVisibility(l.active, enter = fadeIn(), exit = fadeOut()) {
                    OutlinedButton(shape = BtnShape, onClick = { AppGraph.runs.stop() }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(6.dp)) {
                        Icon(Icons.Filled.Stop, null); Spacer(Modifier.width(6.dp)); Text("Stop run")
                    }
                }
              }
            }
            if (l.recent.isNotEmpty()) item { SectionTitle("Latest results") }
            items(l.recent, key = { it.name + it.finishedAt }) { r ->
                Row(
                    Modifier.fillMaxWidth().animateItem().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusDot(r.status.bucket)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(r.name.removePrefix("dEQP-VK."), fontFamily = Mono, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (r.message.isNotEmpty() && r.status.bucket != Bucket.PASSED) {
                            Text(r.message, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(Fmt.duration(r.durationMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item { Spacer(Modifier.navigationBarsPadding()) }
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StatGrid(l: RunManager.Live) {
    val colors = LocalStatusColors.current
    val cells = listOf(Bucket.PASSED, Bucket.FAILED, Bucket.CRASHED, Bucket.SKIPPED)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for (b in cells) {
            Card(Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = colors[b].copy(alpha = 0.13f)), border = cardLine()) {
                Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    AnimatedNumber(l.counts[b].toLong(), MaterialTheme.typography.titleLarge, colors[b])
                    Text(b.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }
    }
}
