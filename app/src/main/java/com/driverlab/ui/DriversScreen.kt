package com.driverlab.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.driverlab.AppGraph
import com.driverlab.core.DriverInfo
import com.driverlab.core.DriverRepository
import com.driverlab.core.Fmt
import com.driverlab.ui.theme.LocalStatusColors
import com.driverlab.ui.theme.MonoSmall
import kotlinx.coroutines.launch

@Composable
fun DriversScreen(nav: NavHostController) {
    val drivers by AppGraph.drivers.drivers.collectAsStateWithLifecycle()
    val probing by AppGraph.drivers.probing.collectAsStateWithLifecycle()
    val settings by AppGraph.settings.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<DriverInfo?>(null) }
    var deleting by remember { mutableStateOf<DriverInfo?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            importing = true
            val r = AppGraph.drivers.import(uri)
            importing = false
            r.onSuccess { d ->
                Toast.makeText(context, "Imported ${d.name}. Checking it…", Toast.LENGTH_SHORT).show()
                AppGraph.drivers.probe(d.id)
            }.onFailure { Toast.makeText(context, "Import failed: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }
    Scaffold(
        topBar = { AppBar("Drivers", subtitle = "System driver and imported Vulkan drivers") },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { picker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream", "application/x-sharedlib", "*/*")) },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("Import driver") },
            )
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (importing) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            item {
                Text(
                    "Import a Vulkan driver as an AdrenoTools zip or a bare .so: Turnip, or a Qualcomm driver taken from " +
                        "another phone together with its own libraries. DriverLab loads it only inside its own test program; " +
                        "nothing on the phone changes.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(drivers, key = { it.id }) { d ->
                DriverCard(
                    d, inUse = d.id == settings.driverId, checking = d.id in probing,
                    onUse = { AppGraph.settings.update { it.copy(driverId = d.id) } },
                    onCheck = { scope.launch { AppGraph.drivers.probe(d.id) } },
                    onOptions = { editing = d },
                    onDelete = { deleting = d },
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
    editing?.let { d ->
        var text by remember(d.id) { mutableStateOf(d.env) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Options for ${d.name}") },
            text = {
                Column {
                    Text("Environment variables for the test program, one per line, for example TU_DEBUG=sysmem.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 140.dp), textStyle = MonoSmall,
                        placeholder = { Text("TU_DEBUG=sysmem", style = MonoSmall) })
                }
            },
            confirmButton = { TextButton(onClick = { AppGraph.drivers.updateEnv(d.id, text); editing = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
    deleting?.let { d ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Remove ${d.name}?") },
            text = { Text("The driver file is removed from DriverLab. Test sessions made with it are kept.") },
            confirmButton = {
                TextButton(onClick = {
                    if (settings.driverId == d.id) AppGraph.settings.update { it.copy(driverId = DriverInfo.SYSTEM_ID) }
                    AppGraph.drivers.delete(d.id); deleting = null
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DriverCard(
    d: DriverInfo,
    inUse: Boolean,
    checking: Boolean,
    onUse: () -> Unit,
    onCheck: () -> Unit,
    onOptions: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val status = LocalStatusColors.current
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = if (inUse) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.padding(16.dp).animateContentSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(if (d.isSystem) Icons.Filled.Memory else Icons.Filled.DeveloperBoard, MaterialTheme.colorScheme.primary, size = 44.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(d.name, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    val sub = listOf(d.vendor, d.version, d.author).filter { it.isNotBlank() }.joinToString(" · ")
                    if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (inUse) Icon(Icons.Filled.CheckCircle, "In use", tint = MaterialTheme.colorScheme.primary)
            }
            if (d.description.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(d.description, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(10.dp))
            when {
                checking -> Column {
                    Text("Checking the driver…", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                d.probe == null -> Text("Not checked yet", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                d.probe.ok -> Column {
                    InfoLine("GPU", d.probe.deviceName)
                    InfoLine("Vulkan", d.probe.apiVersion)
                    InfoLine("Driver ver.", d.probe.driverVersion)
                    InfoLine("IDs", "${d.probe.vendorId} / ${d.probe.deviceId}", mono = true)
                    if (!d.isSystem) InfoLine("Library", d.libName, mono = true)
                }
                else -> Column {
                    var expanded by remember(d.probe.checkedAt) { mutableStateOf(false) }
                    Text("Could not load this driver", style = MaterialTheme.typography.bodyMedium, color = status.failed)
                    Text(
                        d.probe.message,
                        style = MonoSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (expanded) Int.MAX_VALUE else 6,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { expanded = !expanded },
                    )
                    if (!expanded && d.probe.message.length > 240) {
                        Text("Tap to see all", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { expanded = true })
                    }
                }
            }
            if (!d.isSystem && (d.needsNewerAndroid || d.fileCount > 1)) {
                Spacer(Modifier.height(6.dp))
                val parts = buildList {
                    if (d.fileCount > 1) add("${d.fileCount} files")
                    if (d.minApi > 0) add("made for Android ${DriverRepository.androidName(d.minApi)}+")
                }
                Text(parts.joinToString(" · "), style = MaterialTheme.typography.labelSmall,
                    color = if (d.needsNewerAndroid) status.failed else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (d.env.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text("Options: " + d.env.lines().filter { it.isNotBlank() }.joinToString("  "), style = MonoSmall, color = MaterialTheme.colorScheme.tertiary)
            }
            if (d.probe != null && d.probe.checkedAt > 0) {
                Text("Checked ${Fmt.dateTime(d.probe.checkedAt)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!inUse) FilledTonalButton(onClick = onUse) { Text("Use") } else AssistChip(onClick = {}, label = { Text("In use") })
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onCheck, enabled = !checking) { Icon(Icons.Filled.Refresh, "Check again") }
                IconButton(onClick = onOptions) { Icon(Icons.Filled.Tune, "Options") }
                if (!d.isSystem) IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Remove") }
            }
        }
    }
}
