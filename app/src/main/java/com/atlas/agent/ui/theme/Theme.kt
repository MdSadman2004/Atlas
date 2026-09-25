package com.atlas.agent.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val AtlasBlue = Color(0xFF4FA3FF)
val AtlasBg = Color(0xFF0B0E14)
val AtlasSurface = Color(0xFF12161E)
val AtlasSurfaceVar = Color(0xFF1A2029)
val AtlasMuted = Color(0xFF8A94A6)
val AtlasGreen = Color(0xFF7BD88F)
val AtlasRed = Color(0xFFFF7A7A)
val AtlasAmber = Color(0xFFFFC66D)

private val colors = darkColorScheme(
    primary = AtlasBlue,
    onPrimary = Color(0xFF00131F),
    primaryContainer = Color(0xFF11334F),
    onPrimaryContainer = Color(0xFFD5E9FF),
    secondary = AtlasGreen,
    onSecondary = Color(0xFF00210C),
    background = AtlasBg,
    onBackground = Color(0xFFE7EBF3),
    surface = AtlasSurface,
    onSurface = Color(0xFFE7EBF3),
    surfaceVariant = AtlasSurfaceVar,
    onSurfaceVariant = Color(0xFFB6C0CE),
    outline = Color(0xFF2B3441),
    error = AtlasRed,
    onError = Color(0xFF2A0505),
)

@Composable
fun AtlasTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}
