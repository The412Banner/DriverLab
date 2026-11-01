package com.driverlab.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.outlined.Info
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.driverlab.R
import com.driverlab.core.Bucket
import com.driverlab.core.Counts
import com.driverlab.core.TestStatus
import com.driverlab.ui.theme.LocalStatusColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppBar(title: String, subtitle: String? = null, onBack: (() -> Unit)? = null, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = {
            Column {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        },
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        },
        actions = { actions() },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
    )
}

@Composable
fun Logo(size: Dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.28f))
            .background(Brush.linearGradient(listOf(Color(0xFF142033), Color(0xFF2A3B5C)))),
        contentAlignment = Alignment.Center,
    ) {
        Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.fillMaxSize())
    }
}

/** Animated ring: one arc per bucket, sized by count, swept to [progress]. */
@Composable
fun ProgressRing(counts: Counts, progress: Float, running: Boolean, modifier: Modifier = Modifier, stroke: Dp = 14.dp, content: @Composable () -> Unit) {
    val colors = LocalStatusColors.current
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val sweep by animateFloatAsState(progress, tween(600, easing = FastOutSlowInEasing), label = "sweep")
    val infinite = rememberInfiniteTransition(label = "ring")
    val glow by infinite.animateFloat(0.25f, 0.6f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Reverse), label = "glow")
    val spin by infinite.animateFloat(0f, 360f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "spin")
    val primary = MaterialTheme.colorScheme.primary
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = stroke.toPx()
            val d = size.minDimension - w
            val tl = Offset((size.width - d) / 2, (size.height - d) / 2)
            val sz = Size(d, d)
            drawArc(track, 0f, 360f, false, tl, sz, style = Stroke(w))
            val total = counts.total.coerceAtLeast(1)
            var start = -90f
            val full = 360f * sweep
            for (b in listOf(Bucket.PASSED, Bucket.WARNING, Bucket.SKIPPED, Bucket.FAILED, Bucket.CRASHED)) {
                val part = full * counts[b] / total
                if (part > 0.05f) {
                    drawArc(colors[b], start, part, false, tl, sz, style = Stroke(w, cap = StrokeCap.Butt))
                    start += part
                }
            }
            if (running) {
                drawArc(primary.copy(alpha = glow), spin - 90f, 40f, false, tl, sz, style = Stroke(w * 0.45f, cap = StrokeCap.Round))
            }
        }
        content()
    }
}

/** Number that slides when it changes. */
@Composable
fun AnimatedNumber(value: Long, style: androidx.compose.ui.text.TextStyle, color: Color = Color.Unspecified) {
    AnimatedContent(
        targetState = value,
        transitionSpec = {
            (slideInVertically(tween(220)) { it / 2 } + fadeIn(tween(220))) togetherWith
                (slideOutVertically(tween(180)) { -it / 2 } + fadeOut(tween(180)))
        },
        label = "num",
    ) { v -> Text(com.driverlab.core.Fmt.count(v), style = style, color = color) }
}

/** Horizontal bar split by bucket. */
@Composable
fun SegmentBar(counts: Counts, modifier: Modifier = Modifier, height: Dp = 8.dp) {
    val colors = LocalStatusColors.current
    val total = counts.total
    Row(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height / 2)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        if (total > 0) {
            for (b in listOf(Bucket.PASSED, Bucket.WARNING, Bucket.SKIPPED, Bucket.FAILED, Bucket.CRASHED)) {
                val n = counts[b]
                if (n > 0) {
                    androidx.compose.runtime.key(b) {
                        val f by animateFloatAsState(n.toFloat() / total, tween(500), label = "seg")
                        Box(Modifier.weight(f.coerceAtLeast(0.001f)).fillMaxSize().background(colors[b]))
                    }
                }
            }
        }
    }
}

@Composable
fun StatusPill(status: TestStatus, modifier: Modifier = Modifier) {
    val c = LocalStatusColors.current[status.bucket]
    Surface(color = c.copy(alpha = 0.16f), shape = RoundedCornerShape(50), modifier = modifier) {
        Text(status.label, color = c, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp), maxLines = 1)
    }
}

@Composable
fun StatusDot(bucket: Bucket, size: Dp = 10.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(LocalStatusColors.current[bucket]))
}

@Composable
fun CountsRow(counts: Counts, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        for (b in Bucket.entries) {
            val n = counts[b]
            if (n == 0 && b != Bucket.PASSED && b != Bucket.FAILED) continue
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(b, 8.dp)
                Spacer(Modifier.width(5.dp))
                Text(com.driverlab.core.Fmt.count(n.toLong()), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold, modifier = modifier.padding(start = 4.dp, top = 8.dp, bottom = 6.dp))
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier, action: @Composable () -> Unit = {}) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        action()
    }
}

@Composable
fun InfoLine(label: String, value: String, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(110.dp))
        Text(value.ifEmpty { "—" }, style = if (mono) com.driverlab.ui.theme.MonoSmall else MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
fun IconBadge(icon: ImageVector, tint: Color, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    Box(modifier.size(size).clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(size * 0.55f))
    }
}

/** Small (i) button that opens a plain-language explanation. */
@Composable
fun InfoButton(title: String, text: String, extra: String? = null) {
    var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    IconButton(onClick = { open = true }, modifier = Modifier.size(36.dp)) {
        Icon(
            androidx.compose.material.icons.Icons.Outlined.Info, "What is this?",
            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp),
        )
    }
    if (open) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                Column {
                    Text(text, style = MaterialTheme.typography.bodyMedium)
                    if (extra != null) {
                        Spacer(Modifier.height(12.dp))
                        Text(extra, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { open = false }) { Text("OK") } },
        )
    }
}

/** Explanation for a test, group or root, using the nearest described parent. */
@Composable
fun ExplainButton(path: String, isLeaf: Boolean, tests: Int) {
    val catalog = com.driverlab.AppGraph.catalog
    val d = catalog.describe(path)
    val short = path.removePrefix("dEQP-VK.").removePrefix("fd-gles.")
    val text = when {
        d == null -> "No explanation for this one yet. Its name lists what it covers: ${short.replace(".", " › ")}."
        d.first == path -> d.second
        else -> {
            val group = d.first.removePrefix("dEQP-VK.").ifEmpty { d.first }
            "Part of \"$group\": ${d.second}" +
                if (isLeaf) "\n\nThis test's name lists the exact case: ${short.replace(".", " › ")}." else ""
        }
    }
    val extra = (if (isLeaf) "One test" else "${com.driverlab.core.Fmt.count(tests.toLong())} tests") + " · " + path
    InfoButton(if (path == com.driverlab.core.GLES_ROOT) "OpenGL ES tests (freedreno)" else short.substringAfterLast('.').ifEmpty { path }, text, extra)
}

@Composable
fun PresetChips(
    presets: List<com.driverlab.core.Catalog.Preset>,
    selectedTitle: String?,
    onPick: (com.driverlab.core.Catalog.Preset) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
        for (p in presets) {
            androidx.compose.material3.FilterChip(
                selected = selectedTitle == p.title,
                onClick = { onPick(p) },
                label = { Text(p.title) },
            )
            InfoButton(p.title, p.description, "Contains: " + p.patterns.joinToString(", ") {
                it.removePrefix("dEQP-VK.").removeSuffix(".*").removePrefix("fd-gles.")
            }.let { if (it.length > 400) it.take(400) + "…" else it })
            Spacer(Modifier.width(4.dp))
        }
    }
}
