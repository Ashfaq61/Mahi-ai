package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val DarkCyberColorScheme = darkColorScheme(
    primary = CyberCyan,
    onPrimary = DeepSpace,
    secondary = NeonViolet,
    onSecondary = Color.White,
    tertiary = NeonMagenta,
    onTertiary = Color.White,
    background = DeepSpace,
    onBackground = InkSoftLight,
    surface = MidnightNavy,
    onSurface = InkSoftLight,
    surfaceVariant = CyberSlate,
    onSurfaceVariant = InkMutedDark,
    outline = CyberOutline,
    error = ErrorCrimson,
    onError = Color.White
)

private val LightCyberColorScheme = lightColorScheme(
    primary = Color(0xFF0891B2),
    onPrimary = Color.White,
    secondary = Color(0xFF7C3AED),
    onSecondary = Color.White,
    tertiary = Color(0xFFDB2777),
    onTertiary = Color.White,
    background = LightCloud,
    onBackground = InkDark,
    surface = LightCard,
    onSurface = InkDark,
    surfaceVariant = LightCardAlt,
    onSurfaceVariant = InkMuted,
    outline = LightBorder,
    error = ErrorCrimson,
    onError = Color.White
)

private val CyberShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp)
)

@Composable
fun MahiAiTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val scheme = if (darkTheme) DarkCyberColorScheme else LightCyberColorScheme
    MaterialTheme(
        colorScheme = scheme,
        typography = Typography,
        shapes = CyberShapes,
        content = content
    )
}
