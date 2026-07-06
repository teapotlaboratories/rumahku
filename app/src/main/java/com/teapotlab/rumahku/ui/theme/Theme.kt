package com.teapotlab.rumahku.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * App theme. Uses Android 12+ "dynamic color" (wallpaper-derived palette) when
 * available — a nice touch on the S25 — and falls back to a fixed palette
 * otherwise. Kept intentionally small for Phase 1.
 */

private val FallbackDark = darkColorScheme(
    primary = Color(0xFF7FCFB6),
    secondary = Color(0xFF9CC7FF),
)

private val FallbackLight = lightColorScheme(
    primary = Color(0xFF00695C),
    secondary = Color(0xFF2E5B9E),
)

@Composable
fun RumahkuTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)

        darkTheme -> FallbackDark
        else -> FallbackLight
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
