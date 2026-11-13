package com.example.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.NeonMagenta
import com.example.ui.theme.NeonViolet
import com.example.viewmodel.AssistantVisualState
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun MahiOrb(
    visualState: AssistantVisualState,
    amplitude: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    orbSize: Dp = 220.dp
) {
    val infiniteTransition = rememberInfiniteTransition(label = "OrbInfiniteTransition")

    // Continuous rotation for holographic vortex
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 6000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "OrbRotation"
    )

    // Breathing pulse animation
    val breathingPulse by infiniteTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = when (visualState) {
                    AssistantVisualState.THINKING -> 800
                    AssistantVisualState.SPEAKING -> 1200
                    AssistantVisualState.LISTENING -> 1500
                    else -> 2600
                },
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "OrbBreathing"
    )

    // Extra dynamic scale driven by live audio amplitude
    val audioScale = remember { Animatable(1f) }
    LaunchedEffect(amplitude) {
        val target = 1.0f + (amplitude.coerceIn(0f, 1f) * 0.35f)
        audioScale.animateTo(target, tween(60))
    }

    val totalScale = breathingPulse * audioScale.value

    val primaryGlowColor = when (visualState) {
        AssistantVisualState.DISCONNECTED -> Color(0xFF6B21A8)
        AssistantVisualState.CONNECTING -> CyberCyan
        AssistantVisualState.LISTENING -> CyberCyan
        AssistantVisualState.THINKING -> NeonViolet
        AssistantVisualState.SPEAKING -> NeonMagenta
        AssistantVisualState.RECONNECTING -> Color(0xFFF59E0B)
        AssistantVisualState.ERROR -> Color(0xFFEF4444)
    }

    val secondaryGlowColor = when (visualState) {
        AssistantVisualState.DISCONNECTED -> Color(0xFF3B0764)
        AssistantVisualState.CONNECTING -> NeonMagenta
        AssistantVisualState.LISTENING -> NeonMagenta
        AssistantVisualState.THINKING -> CyberCyan
        AssistantVisualState.SPEAKING -> NeonViolet
        AssistantVisualState.RECONNECTING -> CyberCyan
        AssistantVisualState.ERROR -> Color(0xFFB91C1C)
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(orbSize)
            .testTag("mahi_central_orb")
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = false, radius = orbSize / 2),
                onClick = onClick
            )
    ) {
        // Outer radiant aura & ripple rings
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val baseRadius = size.minDimension / 2f

            // Ripple Ring 1
            val ring1Radius = baseRadius * 0.95f * totalScale
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(primaryGlowColor.copy(alpha = 0.35f), Color.Transparent),
                    center = center,
                    radius = ring1Radius
                ),
                radius = ring1Radius,
                center = center
            )

            // Animated segmented orbit ring
            if (visualState != AssistantVisualState.DISCONNECTED) {
                val orbitRadius = baseRadius * 0.88f
                val strokeWidth = 3.dp.toPx()
                val sweepAngle = 75f

                for (i in 0..2) {
                    val startAngle = rotationAngle + (i * 120f)
                    drawArc(
                        brush = Brush.sweepGradient(
                            listOf(primaryGlowColor, secondaryGlowColor, Color.Transparent)
                        ),
                        startAngle = startAngle,
                        sweepAngle = sweepAngle,
                        useCenter = false,
                        topLeft = Offset(center.x - orbitRadius, center.y - orbitRadius),
                        size = androidx.compose.ui.geometry.Size(orbitRadius * 2, orbitRadius * 2),
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                    )
                }

                // Orbiting spark particles
                val rad = Math.toRadians(rotationAngle.toDouble() * 1.5)
                val sparkX = center.x + (orbitRadius * cos(rad)).toFloat()
                val sparkY = center.y + (orbitRadius * sin(rad)).toFloat()
                drawCircle(
                    color = Color.White,
                    radius = 4.dp.toPx(),
                    center = Offset(sparkX, sparkY)
                )
            }
        }

        // Central glowing core orb
        val coreSize = orbSize * 0.65f
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(coreSize)
                .scale(totalScale)
                .shadow(
                    elevation = 24.dp,
                    shape = CircleShape,
                    ambientColor = primaryGlowColor,
                    spotColor = secondaryGlowColor
                )
                .clip(CircleShape)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.9f),
                            primaryGlowColor,
                            secondaryGlowColor,
                            Color(0xFF0F0A1E)
                        )
                    )
                )
        ) {
            // Icon at center reflecting current visual state
            val icon = when (visualState) {
                AssistantVisualState.DISCONNECTED -> Icons.Default.PowerSettingsNew
                AssistantVisualState.CONNECTING -> Icons.Default.Mic
                AssistantVisualState.LISTENING -> Icons.Default.Mic
                AssistantVisualState.THINKING -> Icons.Default.Mic
                AssistantVisualState.SPEAKING -> Icons.Default.VolumeUp
                AssistantVisualState.RECONNECTING -> Icons.Default.Mic
                AssistantVisualState.ERROR -> Icons.Default.MicOff
            }

            val iconColor = when (visualState) {
                AssistantVisualState.DISCONNECTED -> Color.White.copy(alpha = 0.6f)
                else -> Color.White
            }

            Icon(
                imageVector = icon,
                contentDescription = "Mahi Assistant Action",
                tint = iconColor,
                modifier = Modifier
                    .size(coreSize * 0.42f)
                    .scale(if (visualState == AssistantVisualState.SPEAKING) audioScale.value else 1.0f)
            )
        }
    }
}
