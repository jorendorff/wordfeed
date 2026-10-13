package com.jorendorff.wordfeed

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Keep these in sync with the CSS variables in assets/reader.css.
const val ReaderLightBg = 0xFFFBF8F2
const val ReaderDarkBg = 0xFF15130F

private val LightColors = lightColorScheme(
    primary = Color(0xFF8A3B12),
    onPrimary = Color.White,
    secondaryContainer = Color(0xFFF0E2D6),
    onSecondaryContainer = Color(0xFF3A1A08),
    background = Color(ReaderLightBg),
    onBackground = Color(0xFF1F1B16),
    surface = Color(ReaderLightBg),
    onSurface = Color(0xFF1F1B16),
    surfaceVariant = Color(0xFFF3EEE4),
    onSurfaceVariant = Color(0xFF6B6257),
    surfaceContainer = Color(0xFFF3EEE4),
    surfaceContainerHigh = Color(0xFFEDE7DC),
    outline = Color(0xFFB5AA99),
    outlineVariant = Color(0xFFE4DCCF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFEAA47C),
    onPrimary = Color(0xFF3A1A08),
    secondaryContainer = Color(0xFF3A2A1E),
    onSecondaryContainer = Color(0xFFF3D9C8),
    background = Color(ReaderDarkBg),
    onBackground = Color(0xFFECE6DA),
    surface = Color(ReaderDarkBg),
    onSurface = Color(0xFFECE6DA),
    surfaceVariant = Color(0xFF1E1B16),
    onSurfaceVariant = Color(0xFFA89E8E),
    surfaceContainer = Color(0xFF1E1B16),
    surfaceContainerHigh = Color(0xFF26221C),
    outline = Color(0xFF6E6558),
    outlineVariant = Color(0xFF302B24),
)

@Composable
fun isDark(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun WordfeedTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
}
