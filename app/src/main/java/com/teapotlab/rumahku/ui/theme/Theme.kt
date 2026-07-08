package com.teapotlab.rumahku.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * rumahku brand theme — a bright, friendly consumer look (see docs/UX.md).
 * A fixed light palette (dynamic/wallpaper color intentionally off, so the brand
 * stays consistent): warm coral primary + teal secondary on a warm off-white.
 * The fullscreen viewer paints its own black background — it stays immersive.
 */
private val Brand = lightColorScheme(
    primary = Color(0xFFFF6B5E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD3),
    onPrimaryContainer = Color(0xFF5A1408),
    secondary = Color(0xFF12B5A6),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFB4EFE8),
    onSecondaryContainer = Color(0xFF00382F),
    tertiary = Color(0xFFF4A340),
    background = Color(0xFFFFF7F3),
    onBackground = Color(0xFF2B2422),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF2B2422),
    surfaceVariant = Color(0xFFF5E7E1),
    onSurfaceVariant = Color(0xFF7A6A64),
    outlineVariant = Color(0xFFEBDAD3),
)

@Composable
fun RumahkuTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Brand, content = content)
}
