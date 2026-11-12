package com.example.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.NeonMagenta
import com.example.ui.theme.NeonViolet
import com.example.viewmodel.AssistantVisualState
import kotlin.math.max

@Composable
fun LiveWaveformVisualizer(
    bars: List<Float>,
    visualState: AssistantVisualState,
    modifier: Modifier = Modifier,
    height: Dp = 72.dp
) {
    val barCount = bars.size.coerceAtLeast(1)

    // Animated bars for smooth interpolation
    val animatedBars = remember(barCount) {
        List(barCount) { Animatable(0.08f) }
    }

    LaunchedEffect(bars) {
        bars.forEachIndexed { index, targetVal ->
            if (index < animatedBars.size) {
                animatedBars[index].animateTo(
                    targetValue = targetVal.coerceIn(0.05f, 1.0f),
                    animationSpec = tween(durationMillis = 50)
                )
            }
        }
    }

    val gradientBrush = when (visualState) {
        AssistantVisualState.SPEAKING -> Brush.verticalGradient(
            colors = listOf(NeonMagenta, NeonViolet, CyberCyan)
        )
        AssistantVisualState.LISTENING -> Brush.verticalGradient(
            colors = listOf(CyberCyan, ElectricBlue(), NeonMagenta)
        )
        AssistantVisualState.THINKING -> Brush.verticalGradient(
            colors = listOf(NeonViolet, CyberCyan)
        )
        else -> Brush.verticalGradient(
            colors = listOf(Color(0xFF4C1D95), Color(0xFF1E1B4B))
        )
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
    ) {
        val totalWidth = size.width
        val maxHeight = size.height
        val barSpacing = 4.dp.toPx()
        val totalSpacing = barSpacing * (barCount - 1)
        val barWidth = max(2.dp.toPx(), (totalWidth - totalSpacing) / barCount)

        val centerY = maxHeight / 2f

        for (i in 0 until barCount) {
            val normalizedHeight = animatedBars[i].value
            val currentBarHeight = max(6.dp.toPx(), maxHeight * normalizedHeight)
            val left = i * (barWidth + barSpacing)
            val top = centerY - (currentBarHeight / 2f)

            // Draw bar with rounded pill corners
            drawRoundRect(
                brush = gradientBrush,
                topLeft = Offset(left, top),
                size = Size(barWidth, currentBarHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )

            // Add small ambient glow dot on top of peak bars
            if (normalizedHeight > 0.65f) {
                drawCircle(
                    color = Color.White.copy(alpha = 0.8f),
                    radius = barWidth * 0.4f,
                    center = Offset(left + barWidth / 2f, top)
                )
            }
        }
    }
}

@Composable
private fun ElectricBlue() = Color(0xFF38BDF8)
