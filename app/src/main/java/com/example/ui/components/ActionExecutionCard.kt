package com.example.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tools.ToolExecutionResult

@Composable
fun ActionExecutionCard(
    result: ToolExecutionResult?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme

    AnimatedVisibility(
        visible = result != null,
        enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
        modifier = modifier
    ) {
        if (result != null) {
            val icon = when (result.actionName.lowercase()) {
                "openwebsite", "openapp" -> Icons.Default.Language
                "searchweb" -> Icons.Default.Search
                "settimer" -> Icons.Default.Alarm
                "toggleflashlight" -> Icons.Default.FlashlightOn
                else -> Icons.Default.Star
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(scheme.surfaceVariant.copy(alpha = 0.96f))
                    .border(1.dp, scheme.primary.copy(alpha = 0.6f), RoundedCornerShape(20.dp))
                    .padding(14.dp)
                    .testTag("action_execution_card")
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(scheme.primary.copy(alpha = 0.15f))
                                .border(1.dp, scheme.primary.copy(alpha = 0.4f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = result.actionName,
                                tint = scheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "FUNCTION EXECUTED",
                                    color = scheme.primary,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 0.8.sp
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "• Instant",
                                    color = scheme.onSurfaceVariant,
                                    fontSize = 10.sp
                                )
                            }
                            Text(
                                text = result.displayTitle,
                                color = scheme.onSurface,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = result.message,
                                color = scheme.onSurfaceVariant,
                                fontSize = 12.sp,
                                maxLines = 1
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!result.linkUrl.isNullOrBlank()) {
                            IconButton(
                                onClick = {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(result.linkUrl)).apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    context.startActivity(intent)
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.OpenInNew,
                                    contentDescription = "Open Link",
                                    tint = scheme.secondary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Dismiss",
                                tint = scheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
