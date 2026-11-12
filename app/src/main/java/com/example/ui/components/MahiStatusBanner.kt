package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ai.MahiMood
import com.example.ui.theme.CyberBorderGlow
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.CyberDarkSurface
import com.example.ui.theme.NeonMagenta
import com.example.ui.theme.NeonViolet
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.viewmodel.AssistantVisualState

@Composable
fun MahiStatusBanner(
    visualState: AssistantVisualState,
    currentMood: MahiMood,
    isContinuousMode: Boolean,
    isMuted: Boolean,
    onMoodClick: () -> Unit,
    onContinuousToggle: () -> Unit,
    onMuteToggle: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "StatusDotTransition")

    val (statusText, statusColor) = when (visualState) {
        AssistantVisualState.DISCONNECTED -> "STANDBY" to Color(0xFF94A3B8)
        AssistantVisualState.CONNECTING -> "CONNECTING" to CyberCyan
        AssistantVisualState.LISTENING -> "LISTENING" to CyberCyan
        AssistantVisualState.THINKING -> "THINKING" to NeonViolet
        AssistantVisualState.SPEAKING -> "SPEAKING" to NeonMagenta
        AssistantVisualState.ERROR -> "DISCONNECTED" to Color(0xFFEF4444)
    }

    val animatedDotColor by animateColorAsState(
        targetValue = statusColor,
        animationSpec = tween(300),
        label = "DotColor"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(CyberDarkSurface.copy(alpha = 0.85f))
            .border(1.dp, CyberBorderGlow, RoundedCornerShape(24.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left: Branding and status indicator
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Pulsing live status dot
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(animatedDotColor)
            )

            Spacer(modifier = Modifier.width(10.dp))

            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "MAHI",
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "LIVE",
                        color = NeonMagenta,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                }
                Text(
                    text = statusText,
                    color = animatedDotColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.8.sp
                )
            }
        }

        // Center: Interactive Mood Pill
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF20163B))
                .border(1.dp, NeonViolet.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                .clickable { onMoodClick() }
                .padding(horizontal = 10.dp, vertical = 6.dp)
                .testTag("mood_selector_pill")
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = currentMood.emoji,
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = currentMood.displayName,
                    color = NeonMagenta,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Right: Control actions
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Continuous Voice Mode Toggle
            IconButton(
                onClick = onContinuousToggle,
                modifier = Modifier
                    .size(36.dp)
                    .testTag("continuous_mode_button")
            ) {
                Icon(
                    imageVector = Icons.Default.AllInclusive,
                    contentDescription = "Toggle Continuous Voice",
                    tint = if (isContinuousMode) CyberCyan else TextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Mute / Unmute Audio Toggle
            IconButton(
                onClick = onMuteToggle,
                modifier = Modifier
                    .size(36.dp)
                    .testTag("mute_audio_button")
            ) {
                Icon(
                    imageVector = if (isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                    contentDescription = "Toggle Mute",
                    tint = if (isMuted) Color(0xFFEF4444) else TextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Audio & Persona Settings
            IconButton(
                onClick = onSettingsClick,
                modifier = Modifier
                    .size(36.dp)
                    .testTag("settings_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Tune,
                    contentDescription = "Settings",
                    tint = TextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
