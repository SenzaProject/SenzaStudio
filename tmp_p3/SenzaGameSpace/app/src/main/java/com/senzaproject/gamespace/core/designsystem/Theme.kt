package com.senzaproject.gamespace.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val GameMasterColors = darkColorScheme(
    primary = Color(0xFF00E5FF),
    onPrimary = Color(0xFF00363D),
    secondary = Color(0xFF7C4DFF),
    tertiary = Color(0xFF69F0AE),
    background = Color(0xFF0B0F1A),
    onBackground = Color(0xFFE6EAF2),
    surface = Color(0xFF141A2B),
    onSurface = Color(0xFFE6EAF2),
    surfaceVariant = Color(0xFF1C2438),
    onSurfaceVariant = Color(0xFF9AA6C0),
    outline = Color(0xFF2B3550),
    error = Color(0xFFFF5252),
)

object StatusColors {
    val implemented = Color(0xFF69F0AE)
    val partial = Color(0xFFFFC107)
    val planned = Color(0xFF9AA6C0)
}

@Composable
fun GameMasterTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = GameMasterColors,
        typography = Typography(),
        content = content,
    )
}
