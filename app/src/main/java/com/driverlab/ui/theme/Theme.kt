package com.driverlab.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.driverlab.core.Bucket

private val Light = lightColorScheme(
    primary = Color(0xFF6A3FC8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE9DDFF),
    onPrimaryContainer = Color(0xFF22005D),
    secondary = Color(0xFF2E7D4F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFB4F1C8),
    onSecondaryContainer = Color(0xFF002111),
    tertiary = Color(0xFF00796B),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFF9EF2E4),
    onTertiaryContainer = Color(0xFF00201C),
    background = Color(0xFFFBF8FF),
    onBackground = Color(0xFF1C1A22),
    surface = Color(0xFFFBF8FF),
    onSurface = Color(0xFF1C1A22),
    surfaceVariant = Color(0xFFE7E0EC),
    onSurfaceVariant = Color(0xFF49454E),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF6F2FB),
    surfaceContainer = Color(0xFFF0ECF6),
    surfaceContainerHigh = Color(0xFFEAE6F0),
    surfaceContainerHighest = Color(0xFFE4E0EA),
    outline = Color(0xFF7A757F),
    outlineVariant = Color(0xFFCAC4CF),
    error = Color(0xFFBA1A1A),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFCFBCFF),
    onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378B),
    onPrimaryContainer = Color(0xFFEADDFF),
    secondary = Color(0xFF8FD8A8),
    onSecondary = Color(0xFF003920),
    secondaryContainer = Color(0xFF135233),
    onSecondaryContainer = Color(0xFFB4F1C8),
    tertiary = Color(0xFF7FD8C7),
    onTertiary = Color(0xFF003731),
    tertiaryContainer = Color(0xFF005048),
    onTertiaryContainer = Color(0xFF9EF2E4),
    background = Color(0xFF111018),
    onBackground = Color(0xFFE6E1E9),
    surface = Color(0xFF111018),
    onSurface = Color(0xFFE6E1E9),
    surfaceVariant = Color(0xFF49454F),
    onSurfaceVariant = Color(0xFFCAC4D0),
    surfaceContainerLowest = Color(0xFF0C0B12),
    surfaceContainerLow = Color(0xFF19171F),
    surfaceContainer = Color(0xFF1D1B24),
    surfaceContainerHigh = Color(0xFF28252F),
    surfaceContainerHighest = Color(0xFF33303A),
    outline = Color(0xFF948F99),
    outlineVariant = Color(0xFF49454F),
    error = Color(0xFFFFB4AB),
)

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

private val LightStatus = StatusColors(Color(0xFF1E9E50), Color(0xFFD32F2F), Color(0xFFC2185B), Color(0xFFD99A00), Color(0xFF7C8796))
private val DarkStatus = StatusColors(Color(0xFF5BD38A), Color(0xFFFF6B6B), Color(0xFFFF6FA3), Color(0xFFFFC94D), Color(0xFF9EA8B6))

val LocalStatusColors = staticCompositionLocalOf { LightStatus }

val Mono = FontFamily.Monospace

private val AppTypography = Typography().let { t ->
    t.copy(
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

val MonoSmall = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp)

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
    androidx.compose.runtime.CompositionLocalProvider(LocalStatusColors provides if (dark) DarkStatus else LightStatus) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
    }
}
