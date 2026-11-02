package com.driverlab.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.driverlab.R
import com.driverlab.core.Bucket

// DriverLab look: dark first, blue accent, sharp corners and thin outlines.
// Driver A is teal and driver B violet everywhere (see DriverColors).
private val Light = lightColorScheme(
    primary = Color(0xFF1F63E0),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE7FF),
    onPrimaryContainer = Color(0xFF07214F),
    secondary = Color(0xFF0E7C72),
    onSecondary = Color(0xFFFFFFFF),
    // Selected chips, tonal buttons and slider tracks: blue like the accent.
    secondaryContainer = Color(0xFFDCE7FF),
    onSecondaryContainer = Color(0xFF07214F),
    tertiary = Color(0xFF6A4FC8),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE6DEFF),
    onTertiaryContainer = Color(0xFF21005D),
    background = Color(0xFFF5F7FA),
    onBackground = Color(0xFF111722),
    surface = Color(0xFFF5F7FA),
    onSurface = Color(0xFF111722),
    surfaceVariant = Color(0xFFE6EBF2),
    onSurfaceVariant = Color(0xFF4A5566),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFFFFFFF),
    surfaceContainerHighest = Color(0xFFEDF1F6),
    outline = Color(0xFFB8C2D1),
    outlineVariant = Color(0xFFD9E0EA),
    error = Color(0xFFC62828),
    errorContainer = Color(0xFFFDE2DF),
    onErrorContainer = Color(0xFF410002),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF4C8DFF),
    onPrimary = Color(0xFF06142E),
    primaryContainer = Color(0xFF12284D),
    onPrimaryContainer = Color(0xFFCFE0FF),
    secondary = Color(0xFF5FE6D7),
    onSecondary = Color(0xFF00201D),
    // Selected chips, tonal buttons and slider tracks: blue like the accent.
    secondaryContainer = Color(0xFF13284A),
    onSecondaryContainer = Color(0xFFCFE0FF),
    tertiary = Color(0xFFC4B2FF),
    onTertiary = Color(0xFF21005D),
    tertiaryContainer = Color(0xFF2A2350),
    onTertiaryContainer = Color(0xFFE6DEFF),
    background = Color(0xFF080B10),
    onBackground = Color(0xFFE8EDF5),
    surface = Color(0xFF080B10),
    onSurface = Color(0xFFE8EDF5),
    surfaceVariant = Color(0xFF141A24),
    onSurfaceVariant = Color(0xFF8B96A8),
    surfaceContainerLowest = Color(0xFF06080C),
    surfaceContainerLow = Color(0xFF0B0F15),
    surfaceContainer = Color(0xFF0D1219),
    surfaceContainerHigh = Color(0xFF0F141C),
    surfaceContainerHighest = Color(0xFF161D28),
    outline = Color(0xFF2A3140),
    outlineVariant = Color(0xFF1E2532),
    error = Color(0xFFFF8A7A),
    onError = Color(0xFF3A0905),
    errorContainer = Color(0xFF3A1614),
    onErrorContainer = Color(0xFFFFD9D3),
)

/** Colours that mark driver A and driver B in comparisons. */
@Immutable
data class DriverColors(val a: Color, val b: Color)

val LocalDriverColors = staticCompositionLocalOf { DriverColors(Color(0xFF0E7C72), Color(0xFF6A4FC8)) }

@Immutable
data class StatusColors(
    val passed: Color,
    val failed: Color,
    val crashed: Color,
    val warning: Color,
    val skipped: Color,
) {
    operator fun get(b: Bucket): Color = when (b) {
        Bucket.PASSED -> passed
        Bucket.FAILED -> failed
        Bucket.CRASHED -> crashed
        Bucket.WARNING -> warning
        Bucket.SKIPPED -> skipped
    }
}

private val LightStatus = StatusColors(Color(0xFF17924F), Color(0xFFD9601A), Color(0xFFC62828), Color(0xFFB7791F), Color(0xFF7C8796))
private val DarkStatus = StatusColors(Color(0xFF4FD18B), Color(0xFFFF8A4C), Color(0xFFF2555A), Color(0xFFF2C46B), Color(0xFF8B96A8))

val LocalStatusColors = staticCompositionLocalOf { LightStatus }

/** JetBrains Mono (SIL OFL 1.1, assets/licenses/JetBrainsMono-OFL.txt), no-ligature build: numbers and test names. */
val Mono = FontFamily(
    Font(R.font.jbmono_regular, FontWeight.Normal),
    Font(R.font.jbmono_medium, FontWeight.Medium),
    Font(R.font.jbmono_semibold, FontWeight.SemiBold),
    Font(R.font.jbmono_bold, FontWeight.Bold),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(3.dp),
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(6.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(10.dp),
)

private val AppTypography = Typography().let { t ->
    t.copy(
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

val MonoSmall = TextStyle(fontFamily = Mono, fontSize = 12.sp, lineHeight = 16.sp)

@Composable
fun DriverLabTheme(themeMode: Int, dynamicColor: Boolean, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }
    val ctx = LocalContext.current
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> Dark
        else -> Light
    }
    androidx.compose.runtime.CompositionLocalProvider(
        LocalStatusColors provides if (dark) DarkStatus else LightStatus,
        LocalDriverColors provides if (dark) DriverColors(Color(0xFF5FE6D7), Color(0xFFC4B2FF)) else DriverColors(Color(0xFF0E7C72), Color(0xFF6A4FC8)),
    ) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes, content = content)
    }
}
