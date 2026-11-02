package com.driverlab.ui

import android.widget.Toast
import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.driverlab.AppGraph
import com.driverlab.core.DriverInfo
import com.driverlab.core.Fmt
import com.driverlab.core.GameFeatures
import com.driverlab.ui.theme.LocalStatusColors
import com.driverlab.ui.theme.MonoSmall
import kotlinx.coroutines.launch

/** What DXVK / VKD3D-Proton need from a driver, for one driver or two side by side (see [GameFeatures]). */
@Composable
fun GameFeaturesScreen(nav: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drivers by AppGraph.drivers.drivers.collectAsStateWithLifecycle()
    val reports by AppGraph.features.reports.collectAsStateWithLifecycle()
    val checking by AppGraph.features.checking.collectAsStateWithLifecycle()
    val errors by AppGraph.features.errors.collectAsStateWithLifecycle()
    val settings by AppGraph.settings.state.collectAsStateWithLifecycle()
    var a by rememberSaveable { mutableStateOf(settings.driverId) }
    var b by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val shown = listOfNotNull(drivers.firstOrNull { it.id == a } ?: drivers.firstOrNull(), b?.let { id -> drivers.firstOrNull { it.id == id } })

    Scaffold(topBar = {
        AppBar("Game features", subtitle = "What Windows game translation layers need from a driver", onBack = { nav.popBackStack() })
    }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("Driver", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    drivers.forEach { d -> FilterChip(d.id == shown.firstOrNull()?.id, { a = d.id; if (b == d.id) b = null }, label = { Text(d.name, maxLines = 1) }) }
                }
                Spacer(Modifier.height(6.dp))
                Text("Compare with", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(b == null, { b = null }, label = { Text("None") })
                    drivers.filter { it.id != shown.firstOrNull()?.id }.forEach { d ->
                        FilterChip(b == d.id, { b = if (b == d.id) null else d.id }, label = { Text(d.name, maxLines = 1) })
                    }
                }
            }
            items(shown, key = { "check-" + it.id }) { d ->
                CheckRow(d, reports[d.id], d.id in checking, errors[d.id]) { AppGraph.features.check(d.id) }
            }
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            items(GameFeatures.PROFILES, key = { it.id }) { p ->
                ProfileCard(p, shown, reports) {
                    if (busy) return@ProfileCard
                    busy = true
                    scope.launch {
                        try {
                            val r = AppGraph.selection.loadList(p.title, context.cacheDir) { sink ->
                                for (t in p.tests) for (n in AppGraph.catalog.leavesUnder(t.group, t.contains)) sink(n)
                            }
                            Toast.makeText(context, "Selected ${Fmt.count(r.tests)} tests for ${p.title}.", Toast.LENGTH_SHORT).show()
                            nav.goTab("tests", fresh = true)
                        } finally {
                            busy = false
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckRow(d: DriverInfo, rep: GameFeatures.Report?, checking: Boolean, error: String?, onCheck: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), border = cardLine(), shape = RoundedCornerShape(6.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(d.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        when {
                            checking -> "Asking the driver what it supports…"
                            rep != null -> "Checked ${Fmt.dateTime(rep.checkedAt)} · ${rep.extensions.size} extensions, ${rep.features.size} feature values"
                            else -> "Not checked yet (about 10 seconds)"
                        },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!checking) {
                    if (rep == null) FilledTonalButton(shape = BtnShape, onClick = onCheck) { Text("Check") } else OutlinedButton(shape = BtnShape, onClick = onCheck) { Text("Again") }
                }
            }
            if (checking) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = LocalStatusColors.current.failed) }
        }
    }
}

@Composable
private fun ProfileCard(p: GameFeatures.Profile, shown: List<DriverInfo>, reports: Map<String, GameFeatures.Report>, onSelect: () -> Unit) {
    val status = LocalStatusColors.current
    var open by rememberSaveable(p.id) { mutableStateOf(false) }
    Card(
        Modifier.fillMaxWidth().clickable { open = !open },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), border = cardLine(),
        shape = RoundedCornerShape(6.dp),
    ) {
        Column(Modifier.padding(16.dp).animateContentSize()) {
            Text(p.title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(p.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            shown.forEach { d ->
                val rep = reports[d.id]
                val (text, color) = if (rep == null) "Not checked" to status.skipped else {
                    val v = GameFeatures.verdict(p, rep)
                    when {
                        v.missing > 0 -> ("${v.missing} required missing" + if (v.unknown > 0) " · ${v.unknown} not reported" else "") to status.failed
                        v.unknown > 0 -> "${v.unknown} not reported" to status.warning
                        else -> "Has everything required" to status.passed
                    } .let { (t, c) -> (t + if (v.optionalMissing > 0) " · ${v.optionalMissing} optional missing" else "") to c }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(d.name, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(text, style = MaterialTheme.typography.labelMedium, color = color)
                }
            }
            if (open) {
                Spacer(Modifier.height(10.dp))
                p.reqs.forEach { r ->
                    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text(r.label + if (r.optional) "  (optional)" else "", style = MonoSmall)
                            if (r.note.isNotEmpty()) Text(r.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        shown.forEach { d ->
                            Spacer(Modifier.width(8.dp))
                            ReqMark(reports[d.id]?.let { GameFeatures.status(r, it) }, r.optional)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Source: ${p.source}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Tests: " + p.tests.joinToString(", ") { it.group.removePrefix("dEQP-VK.") + (it.contains?.let { c -> " (*$c*)" } ?: "") },
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text("Tap for details", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(shape = BtnShape, onClick = onSelect) { Text("Select its tests") }
        }
    }
}

@Composable
private fun ReqMark(s: GameFeatures.Status?, optional: Boolean) {
    val status = LocalStatusColors.current
    when (s) {
        GameFeatures.Status.YES -> Icon(Icons.Filled.CheckCircle, "Yes", Modifier.size(18.dp), tint = status.passed)
        GameFeatures.Status.NO -> Icon(Icons.Filled.Cancel, "No", Modifier.size(18.dp), tint = if (optional) status.warning else status.failed)
        GameFeatures.Status.UNKNOWN -> Icon(Icons.AutoMirrored.Filled.HelpOutline, "Not reported", Modifier.size(18.dp), tint = status.skipped)
        null -> Icon(Icons.AutoMirrored.Filled.HelpOutline, "Not checked", Modifier.size(18.dp), tint = Color.Gray)
    }
}
