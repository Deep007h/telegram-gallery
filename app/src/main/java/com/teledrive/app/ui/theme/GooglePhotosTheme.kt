package com.teledrive.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// ── Brand palette (Google Photos inspired AMOLED Deep Black) ────────
val GoogleDarkBackground = Color(0xFF000000)       // Pure Deepest AMOLED Black
val GoogleDarkSurface = Color(0xFF0D0E11)          // Deep Black Surface
val GoogleDarkCard = Color(0xFF131418)             // Elevated Card Background
val GoogleDarkCardElevated = Color(0xFF1C1D22)     // High-Elevation Container
val GooglePillSurface = Color(0xFF1C1D22)          // Filter / Button Pill Surface
val GooglePillSelected = Color(0xFFE8EAED)
val GooglePrimaryAccent = Color(0xFFA8C7FA)        // Google Photos light blue
val GoogleSecondaryAccent = Color(0xFF7FCFFF)      // Crisp Cyan-Blue Accent (No Purple)
val GoogleTertiaryAccent = Color(0xFFFFD8A8)        // Warm orange for highlights
val GoogleDanger = Color(0xFFFF7A7A)
val GoogleOnDarkText = Color(0xFFF1F3F4)           // Google High-Contrast White
val GoogleOnDarkTextMuted = Color(0xFFBDC1C6)      // Google Muted Gray
val GoogleOnDarkTextSubtle = Color(0xFF80868B)     // Google Subtle Gray

// Light palette
val GoogleLightBackground = Color(0xFFF7F8FB)
val GoogleLightSurface = Color(0xFFFFFFFF)
val GoogleLightCard = Color(0xFFF1F2F6)
val GoogleOnLightText = Color(0xFF1B1B1F)

private val DarkColorScheme = darkColorScheme(
    primary = GooglePrimaryAccent,
    onPrimary = Color(0xFF003063),
    primaryContainer = Color(0xFF004A77),
    onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = GoogleSecondaryAccent,
    onSecondary = Color(0xFF00363D),
    secondaryContainer = Color(0xFF004F58),
    onSecondaryContainer = Color(0xFF97F0FF),
    tertiary = GoogleTertiaryAccent,
    background = GoogleDarkBackground,
    onBackground = GoogleOnDarkText,
    surface = GoogleDarkBackground,
    onSurface = GoogleOnDarkText,
    surfaceVariant = GoogleDarkCard,
    onSurfaceVariant = GoogleOnDarkTextMuted,
    surfaceTint = GooglePrimaryAccent,
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF090A0D),
    surfaceContainer = Color(0xFF0D0E11),
    surfaceContainerHigh = Color(0xFF131418),
    surfaceContainerHighest = Color(0xFF1C1D22),
    outline = Color(0xFF282A30),
    outlineVariant = Color(0xFF1A1B20),
    error = GoogleDanger,
    onError = Color(0xFF690005)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF1A73E8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E3FD),
    onPrimaryContainer = Color(0xFF001A41),
    secondary = Color(0xFF6750A4),
    onSecondary = Color.White,
    background = GoogleLightBackground,
    onBackground = GoogleOnLightText,
    surface = GoogleLightSurface,
    onSurface = GoogleOnLightText,
    surfaceVariant = GoogleLightCard,
    onSurfaceVariant = Color(0xFF46464F),
    error = Color(0xFFB3261E),
    onError = Color.White
)

@Composable
fun GooglePhotosTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            val insets = WindowCompat.getInsetsController(window, view)
            insets.isAppearanceLightStatusBars = !darkTheme
            insets.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
