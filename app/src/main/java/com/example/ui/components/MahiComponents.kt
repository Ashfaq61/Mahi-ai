package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.CyberEmerald
import com.example.ui.theme.NeonMagenta
import com.example.ui.theme.NeonViolet
import com.example.ui.theme.WarningAmber

enum class OrbVisualState(val label: String, val accent: Color) {
    IDLE("Tap Orb or Speak", CyberCyan),
    LISTENING("Listening", CyberEmerald),
    THINKING("Thinking", NeonViolet),
    SPEAKING("Speaking", NeonMagenta),
    CONTROLLING("Controlling Screen", WarningAmber)
}

@Composable
fun MahiOrb(
    orbState: OrbVisualState,
    micLevel: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val inf = rememberInfiniteTransition(label = "orb_pulse")
    val pulse by inf.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1300, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    val levelBoost = 1f + (micLevel.coerceIn(0f, 1f) * 0.14f)
    val radiusScale = pulse * levelBoost

    Box(
        modifier = modifier
            .size(164.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .testTag("mahi_orb_button"),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(164.dp)) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val baseR = size.minDimension * 0.34f * radiusScale
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        orbState.accent.copy(alpha = 0.42f),
                        NeonViolet.copy(alpha = 0.16f),
                        Color.Transparent
                    ),
                    center = c,
                    radius = baseR * 1.48f
                ),
                radius = baseR * 1.48f,
                center = c
            )
            drawCircle(
                brush = Brush.linearGradient(
                    colors = listOf(orbState.accent, NeonViolet, NeonMagenta)
                ),
                radius = baseR,
                center = c
            )
        }
        Icon(
            imageVector = when (orbState) {
                OrbVisualState.LISTENING -> Icons.Default.Stop
                OrbVisualState.SPEAKING -> Icons.Default.GraphicEq
                else -> Icons.Default.Mic
            },
            contentDescription = orbState.label,
            tint = Color.White,
            modifier = Modifier.size(34.dp)
        )
    }
}

@Composable
fun LiveWaveformVisualizer(
    isActive: Boolean,
    micLevel: Float,
    accent: Color = CyberCyan,
    modifier: Modifier = Modifier
) {
    val inf = rememberInfiniteTransition(label = "wave")
    val phase by inf.animateFloat(
        initialValue = 0f,
        targetValue = 6.28f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Restart),
        label = "wave_phase"
    )
    Row(
        modifier = modifier
            .height(28.dp)
            .fillMaxWidth()
            .padding(horizontal = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (i in 0 until 14) {
            val wave = if (isActive) {
                val amp = (0.28f + kotlin.math.abs(kotlin.math.sin(phase + i * 0.55f)) * 0.55f + micLevel * 0.4f).coerceIn(0.15f, 1f)
                (26f * amp).dp
            } else {
                4.dp
            }
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(wave)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (i % 2 == 0) accent else NeonMagenta)
            )
        }
    }
}

@Composable
fun MahiStatusBanner(
    statusText: String,
    accent: Color = CyberCyan,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface)
            .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(accent)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = statusText,
            color = colors.onSurface,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2
        )
    }
}

@Composable
fun QuickPromptChips(
    prompts: List<String>,
    onPromptClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        prompts.forEach { prompt ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(colors.surfaceVariant)
                    .border(1.dp, CyberCyan.copy(alpha = 0.4f), RoundedCornerShape(18.dp))
                    .clickable { onPromptClick(prompt) }
                    .minimumInteractiveComponentSize()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = prompt,
                    color = colors.onSurface,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
fun SubtitleOverlay(
    subtitle: String,
    modifier: Modifier = Modifier
) {
    if (subtitle.isBlank()) return
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface.copy(alpha = 0.92f))
            .border(1.dp, NeonViolet.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = subtitle,
            color = colors.onSurface,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.Medium
        )
    }
}
