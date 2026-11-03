package com.senzaproject.studio.core.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
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
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 1.2.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 0.8.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 0.8.sp),
)

private val SenzaShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
)

@Composable
fun SenzaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SenzaDarkScheme,
        typography = SenzaTypography,
        shapes = SenzaShapes,
        content = content,
    )
}
