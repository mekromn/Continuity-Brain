package com.mekromn.continuitybrain.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

val BrainBlack = Color(0xFF050507)
val BrainSurface = Color(0xFF0D0D12)
val BrainSurfaceRaised = Color(0xFF15151D)
val BrainPurple = Color(0xFF9B87F5)
val BrainCyan = Color(0xFF53D7FF)
val BrainGreen = Color(0xFF67E8A7)
val BrainAmber = Color(0xFFFFC857)
val BrainRed = Color(0xFFFF6B7D)
val BrainText = Color(0xFFF7F7FB)
val BrainTextMuted = Color(0xFFA6A6B3)

private val ContinuityBrainColors = darkColorScheme(
    primary = BrainPurple,
    onPrimary = Color(0xFF110E1E),
    secondary = BrainCyan,
    onSecondary = Color(0xFF061419),
    tertiary = BrainGreen,
    background = BrainBlack,
    onBackground = BrainText,
    surface = BrainSurface,
    onSurface = BrainText,
    surfaceVariant = BrainSurfaceRaised,
    onSurfaceVariant = BrainTextMuted,
    error = BrainRed,
    onError = Color.Black,
    outline = Color(0xFF343442),
    outlineVariant = Color(0xFF23232E),
)

@Composable
fun ContinuityBrainTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        val window = (view.context as? Activity)?.window
        if (window != null) {
            window.statusBarColor = BrainBlack.toArgb()
            window.navigationBarColor = BrainBlack.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }
    MaterialTheme(
        colorScheme = ContinuityBrainColors,
        content = content,
    )
}
