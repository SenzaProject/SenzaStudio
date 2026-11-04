package com.senzaproject.studio.core.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Senza Studio design tokens: graphite surfaces, restrained electric-blue accent. */
object SenzaColors {
    val Background = Color(0xFF0A0B0E)
    val Surface = Color(0xFF141922)
    val SurfaceRaised = Color(0xFF1B212B)
    val Border = Color(0xFF2A3140)
    val Accent = Color(0xFF5BA4FF)
    val AccentCyan = Color(0xFF4FD8E8)
    val TextPrimary = Color(0xFFF2F5FA)
    val TextMuted = Color(0xFF8F9BB0)
    val Success = Color(0xFF6FD6A5)
    val Warning = Color(0xFFF2B84B)
    val Danger = Color(0xFFFF6B6B)
}

object SenzaSpacing {
    val Screen = 20.dp
    val Card = 18.dp
    val Gap = 16.dp
    val Tight = 8.dp
}

private val SenzaDarkScheme = darkColorScheme(
    primary = SenzaColors.Accent,
    onPrimary = Color(0xFF04101F),
    secondary = SenzaColors.AccentCyan,
    background = SenzaColors.Background,
    onBackground = SenzaColors.TextPrimary,
    surface = SenzaColors.Surface,
    onSurface = SenzaColors.TextPrimary,
    surfaceVariant = SenzaColors.SurfaceRaised,
    onSurfaceVariant = SenzaColors.TextMuted,
    outline = SenzaColors.Border,
    error = SenzaColors.Danger,
)

private val SenzaTypography = Typography(
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 0.8.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 0.3.sp),
)

private val SenzaShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
)

/** Largest system font scale the layouts are designed for (larger values still scale, up to this cap). */
private const val MAX_FONT_SCALE = 1.15f

@Composable
fun SenzaTheme(content: @Composable () -> Unit) {
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, fontScale = density.fontScale.coerceAtMost(MAX_FONT_SCALE)),
    ) {
        MaterialTheme(
            colorScheme = SenzaDarkScheme,
            typography = SenzaTypography,
            shapes = SenzaShapes,
            content = content,
        )
    }
}
