package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.NeonMagenta
import com.example.viewmodel.AssistantVisualState

@Composable
fun SubtitleOverlay(
    visualState: AssistantVisualState,
    userText: String,
    assistantText: String,
    showSubtitles: Boolean,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    AnimatedVisibility(
        visible = showSubtitles && (userText.isNotBlank() || assistantText.isNotBlank()),
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(colors.surface.copy(alpha = 0.92f))
                .border(1.dp, colors.outline, RoundedCornerShape(22.dp))
                .padding(horizontal = 18.dp, vertical = 14.dp)
                .testTag("subtitle_overlay_card")
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (userText.isNotBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(CyberCyan)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "YOU",
                            color = CyberCyan,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }
                    Text(
                        text = "\"$userText\"",
                        color = colors.onSurfaceVariant,
                        fontSize = 13.sp,
                        fontStyle = FontStyle.Italic,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = if (assistantText.isNotBlank()) 10.dp else 0.dp)
                    )
                }

                if (assistantText.isNotBlank()) {
                    if (userText.isNotBlank()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.5f)
                                .height(1.dp)
                                .background(colors.outline)
                                .padding(vertical = 4.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(NeonMagenta)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "MAHI",
                            color = NeonMagenta,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }

                    Text(
                        text = assistantText,
                        color = colors.onSurface,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        lineHeight = 22.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}
