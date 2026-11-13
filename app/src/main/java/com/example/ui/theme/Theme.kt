package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.example.preferences.AppThemeMode

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

private val MahiLightColorScheme = lightColorScheme(
    primary = LightPrimaryMagenta,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD9E6),
    onPrimaryContainer = Color(0xFF3E001A),
    secondary = LightSecondaryCyan,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCFFAFE),
    onSecondaryContainer = Color(0xFF00363A),
    tertiary = NeonViolet,
    onTertiary = Color.White,
    background = LightBackground,
    onBackground = LightTextPrimary,
    surface = LightSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightTextSecondary,
    outline = LightBorder
)

@Composable
fun MahiAiTheme(
    themeMode: AppThemeMode = AppThemeMode.DARK,
    content: @Composable () -> Unit
) {
    val useDark = when (themeMode) {
        AppThemeMode.DARK -> true
        AppThemeMode.LIGHT -> false
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (useDark) MahiDarkColorScheme else MahiLightColorScheme,
        typography = Typography,
        content = content
    )
}
