package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val MahiDarkColorScheme = darkColorScheme(
    primary = NeonMagenta,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF381024),
    onPrimaryContainer = NeonMagentaLight,
    secondary = CyberCyan,
    onSecondary = Color(0xFF00363A),
    secondaryContainer = Color(0xFF004F55),
    onSecondaryContainer = Color(0xFF70F5FF),
    tertiary = NeonViolet,
    onTertiary = Color.White,
    background = CyberDarkBackground,
    onBackground = TextPrimary,
    surface = CyberDarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = CyberDarkSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    outline = CyberBorderGlow
)

@Composable
fun MahiAiTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = MahiDarkColorScheme,
        typography = Typography,
        content = content
    )
}
