package com.driverlab.ui

import android.widget.Toast
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.driverlab.AppGraph
import com.driverlab.core.Bucket
import com.driverlab.core.FlagCheck
import com.driverlab.core.Fmt
import com.driverlab.ui.theme.MonoSmall

/** Runs one failing test again with each Mesa debug setting (see [FlagCheck]). */
@Composable
fun FlagCheckScreen(nav: NavHostController, sessionId: String, test: String) {
    val context = LocalContext.current
    val state by AppGraph.flagCheck.state.collectAsStateWithLifecycle()
    val live by AppGraph.runs.live.collectAsStateWithLifecycle()
    val meta = AppGraph.sessions.get(sessionId)
    val driver = meta?.let { AppGraph.drivers.get(it.driverId) }
    val mine = state?.takeIf { it.sessionId == sessionId && it.test == test }
    val running = AppGraph.flagCheck.active
    val runBusy = live?.active == true
    Scaffold(topBar = {
        AppBar("Find a setting that fixes it", subtitle = test.removePrefix("dEQP-VK."), onBack = { nav.popBackStack() })
    }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "Runs this test again on ${driver?.name ?: "the driver"}: once with its own options, then once with each " +
                                "Mesa debug setting. If a setting makes it pass, that part of the driver is the likely cause " +
                                "(from Mesa's freedreno guide to narrowing down problems). Only Turnip drivers use these settings.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(12.dp))
                        when {
                            driver == null -> Text("The driver of this run is no longer installed.", color = MaterialTheme.colorScheme.error)
                            !FlagCheck.isTurnip(driver) -> Text("This driver doesn't look like Turnip, so these settings would do nothing.", color = MaterialTheme.colorScheme.error)
                            runBusy -> Text("A test run is going on. Start this when it has finished.", color = MaterialTheme.colorScheme.error)
                            running && mine != null -> OutlinedButton(onClick = { AppGraph.flagCheck.stop() }) { Text("Stop") }
                            running -> Text("Another check is running.", color = MaterialTheme.colorScheme.error)
                            else -> Button(onClick = { AppGraph.flagCheck.start(sessionId, test, driver.id) }) {
                                Text(if (mine == null) "Start (${FlagCheck.FLAGS.size + 1} runs)" else "Run again")
                            }
                        }
                    }
                }
            }
            if (mine != null) {
                if (mine.finished) item {
                    val base = mine.rows.firstOrNull()?.status
                    val fixes = mine.fixes
                    val text = when {
                        mine.stopped -> "Stopped."
                        base?.bucket == Bucket.PASSED -> "The test passes with the driver's own options now, so no setting is needed (the failure may come and go)."
                        fixes.isEmpty() -> "No single setting makes it pass."
                        else -> "Passes with: " + fixes.joinToString(", ") { it.label }
                    }
                    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 4.dp))
                }
                itemsIndexed(mine.rows) { _, row ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), shape = RoundedCornerShape(14.dp)) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(row.flag?.label ?: "Driver's own options", style = MonoSmall)
                                    row.flag?.let { Text(it.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                }
                                Spacer(Modifier.width(8.dp))
                                when {
                                    row.running -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                    row.status != null -> Column(horizontalAlignment = Alignment.End) {
                                        StatusPill(row.status)
                                        Text(Fmt.duration(row.ms), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                            if (row.message.isNotEmpty() && row.status?.bucket != Bucket.PASSED) {
                                Text(row.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
                            }
                            val flag = row.flag
                            if (flag != null && driver != null && mine.finished && flag in mine.fixes) {
                                TextButton(onClick = {
                                    AppGraph.drivers.updateEnv(driver.id, FlagCheck.withFlag(driver.env, flag))
                                    Toast.makeText(context, "Added ${flag.label} to ${driver.name}'s options.", Toast.LENGTH_SHORT).show()
                                }) { Text("Use for this driver") }
                            }
                        }
                    }
                }
            }
        }
    }
}
