package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.automation.AgentPlanStep
import com.example.automation.AutomationHistoryEntry
import com.example.automation.CapturedNotification
import com.example.automation.DeviceControlHelper
import com.example.automation.MahiAccessibilityService
import com.example.automation.MahiNotificationService
import com.example.automation.PendingSensitiveConfirmation
import com.example.automation.ScreenSnapshot
import com.example.automation.StepExecutionStatus
import com.example.automation.SwipeDirection
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.CyberEmerald
import com.example.ui.theme.NeonMagenta
import com.example.ui.theme.NeonViolet
import com.example.ui.theme.WarningAmber

@Composable
fun AgentAutomationScreen(
    deviceControl: DeviceControlHelper,
    screenSnapshot: ScreenSnapshot,
    notifications: List<CapturedNotification>,
    planSteps: List<AgentPlanStep>,
    isAgentRunning: Boolean,
    pendingConfirmation: PendingSensitiveConfirmation?,
    history: List<AutomationHistoryEntry>,
    screenAiAnalysis: String,
    onRunGoal: (String) -> Unit,
    onStopAgent: () -> Unit,
    onRespondConfirmation: (Boolean) -> Unit,
    onAnalyzeCurrentScreenWithAi: () -> Unit,
    onSendAuthorizedWhatsAppReply: (notificationKey: String, sender: String, replyText: String) -> Unit,
    onClearHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme

    val accessibilityEnabled = MahiAccessibilityService.isServiceEnabled(context)
    val notificationsEnabled = MahiNotificationService.isListenerEnabled(context)
    val canWriteSettings = deviceControl.canWriteSystemSettings()

    var goalInput by remember { mutableStateOf("") }
    var tapTargetInput by remember { mutableStateOf("") }
    var youtubeQueryInput by remember { mutableStateOf("") }
    var volumeSlider by remember { mutableFloatStateOf(deviceControl.getMediaVolumePercent().toFloat()) }
    var brightnessSlider by remember { mutableFloatStateOf(deviceControl.getBrightnessPercent().toFloat()) }
    var replyDialogTarget by remember { mutableStateOf<CapturedNotification?>(null) }
    var replyDraft by remember { mutableStateOf("") }
    var automationFeedback by remember { mutableStateOf<String?>(null) }

    // Sensitive Action Permission Dialog (Requirement 2 & 6)
    if (pendingConfirmation != null) {
        AlertDialog(
            onDismissRequest = { onRespondConfirmation(false) },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        tint = WarningAmber
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Approve Sensitive Action?", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Category: ${pendingConfirmation.step.sensitiveCategory.label}", fontWeight = FontWeight.SemiBold)
                    Text("Step: ${pendingConfirmation.step.title}")
                    Text(pendingConfirmation.reason, fontSize = 12.sp)
                }
            },
            confirmButton = {
                Button(
                    onClick = { onRespondConfirmation(true) },
                    colors = ButtonDefaults.buttonColors(containerColor = NeonMagenta),
                    modifier = Modifier.testTag("confirm_sensitive_action_button")
                ) {
                    Text("Approve")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { onRespondConfirmation(false) },
                    modifier = Modifier.testTag("deny_sensitive_action_button")
                ) {
                    Text("Deny")
                }
            }
        )
    }

    // WhatsApp Authorized Reply Draft Dialog
    if (replyDialogTarget != null) {
        val target = replyDialogTarget!!
        AlertDialog(
            onDismissRequest = { replyDialogTarget = null },
            title = { Text("Reply to ${target.senderTitle}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Message: ${target.messageText}", fontSize = 12.sp)
                    OutlinedTextField(
                        value = replyDraft,
                        onValueChange = { replyDraft = it },
                        label = { Text("Authorized Reply") },
                        singleLine = false
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (replyDraft.isNotBlank()) {
                            onSendAuthorizedWhatsAppReply(target.key, target.senderTitle, replyDraft.trim())
                            replyDialogTarget = null
                            replyDraft = ""
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NeonMagenta)
                ) {
                    Text("Review & Send")
                }
            },
            dismissButton = {
                TextButton(onClick = { replyDialogTarget = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("agent_automation_screen"),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column {
            Text(
                text = "AUTONOMOUS AGENT & SCREEN AI",
                color = CyberCyan,
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp
            )
            Text(
                text = "Device Control & Screen Intelligence",
                color = colors.onBackground,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // System Permissions Checklist
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PermissionChip(
                title = if (accessibilityEnabled) "Accessibility: ON" else "Enable Accessibility",
                active = accessibilityEnabled,
                icon = Icons.Default.AccessibilityNew,
                onClick = { MahiAccessibilityService.openAccessibilitySettings(context) },
                testTag = "enable_accessibility_chip"
            )
            PermissionChip(
                title = if (notificationsEnabled) "Notifications: ON" else "Enable Notifications",
                active = notificationsEnabled,
                icon = Icons.Default.NotificationsActive,
                onClick = { MahiNotificationService.openNotificationListenerSettings(context) },
                testTag = "enable_notifications_chip"
            )
            PermissionChip(
                title = if (canWriteSettings) "System Write: ON" else "Allow Brightness Write",
                active = canWriteSettings,
                icon = Icons.Default.BrightnessHigh,
                onClick = { deviceControl.openWriteSettingsPermission() },
                testTag = "enable_write_settings_chip"
            )
        }

        if (!automationFeedback.isNullOrBlank()) {
            Text(
                text = automationFeedback.orEmpty(),
                color = CyberCyan,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        // 1. Intelligent Autonomous Agent Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(colors.surface)
                .border(1.dp, NeonViolet.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Autonomous Goal Planner",
                color = CyberCyan,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
            OutlinedTextField(
                value = goalInput,
                onValueChange = { goalInput = it },
                label = { Text("Enter multi-step goal", fontSize = 12.sp) },
                placeholder = { Text("e.g., Search lo-fi on YouTube and set volume 35", fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("agent_goal_input")
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { if (goalInput.isNotBlank()) onRunGoal(goalInput.trim()) },
                    enabled = !isAgentRunning && goalInput.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = NeonMagenta),
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("agent_run_goal_button")
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Plan & Execute", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = onStopAgent,
                    enabled = isAgentRunning,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("agent_stop_button")
                ) {
                    Icon(Icons.Default.Stop, contentDescription = "Stop Automation", modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Stop", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            if (planSteps.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    planSteps.forEach { step ->
                        val statusColor = when (step.status) {
                            StepExecutionStatus.VERIFIED_SUCCESS -> CyberEmerald
                            StepExecutionStatus.FAILED,
                            StepExecutionStatus.DENIED_BY_USER -> Color(0xFFEF4444)
                            StepExecutionStatus.AWAITING_CONFIRMATION -> WarningAmber
                            StepExecutionStatus.RUNNING -> CyberCyan
                            else -> colors.onSurfaceVariant
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(colors.surfaceVariant)
                                .padding(10.dp)
                        ) {
                            Text(
                                text = "${step.stepNumber}. ${step.title}",
                                color = colors.onSurface,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${step.status.name}${if (step.verificationNote.isNotBlank()) " — ${step.verificationNote}" else ""}",
                                color = statusColor,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        }

        // 2. Advanced Screen Intelligence & Accessibility Gestures
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(colors.surface)
                .border(1.dp, colors.outline, RoundedCornerShape(16.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Screen Intelligence (${screenSnapshot.packageName.ifBlank { "Idle" }})",
                    color = CyberCyan,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                IconButton(
                    onClick = { MahiAccessibilityService.refreshScreenSnapshot() },
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh Screen Scan", tint = CyberCyan)
                }
            }

            Text(
                text = screenSnapshot.readableSummary,
                color = colors.onSurfaceVariant,
                fontSize = 11.sp
            )

            if (screenAiAnalysis.isNotBlank()) {
                Text(
                    text = "Screen AI: $screenAiAnalysis",
                    color = colors.onSurface,
                    fontSize = 12.sp
                )
            }

            OutlinedButton(
                onClick = onAnalyzeCurrentScreenWithAi,
                modifier = Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .testTag("analyze_screen_ai_button")
            ) {
                Text("Understand Active App & Screen", fontSize = 12.sp)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = tapTargetInput,
                    onValueChange = { tapTargetInput = it },
                    placeholder = { Text("Button/text to tap...", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = {
                        val ok = MahiAccessibilityService.tapElementByText(tapTargetInput)
                        automationFeedback = if (ok) "Tapped '$tapTargetInput'" else "Enable Accessibility or element not found"
                    },
                    enabled = tapTargetInput.isNotBlank(),
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(Icons.Default.TouchApp, contentDescription = "Tap", modifier = Modifier.size(16.dp))
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedButton(
                    onClick = { automationFeedback = if (MahiAccessibilityService.scrollScreen(true)) "Scrolled Down" else "Accessibility required" },
                    modifier = Modifier.weight(1f)
                ) { Text("Scroll ↓", fontSize = 11.sp) }
                OutlinedButton(
                    onClick = { automationFeedback = if (MahiAccessibilityService.scrollScreen(false)) "Scrolled Up" else "Accessibility required" },
                    modifier = Modifier.weight(1f)
                ) { Text("Scroll ↑", fontSize = 11.sp) }
                OutlinedButton(
                    onClick = { automationFeedback = if (MahiAccessibilityService.swipeScreen(SwipeDirection.LEFT)) "Swiped Left" else "Accessibility required" },
                    modifier = Modifier.weight(1f)
                ) { Text("Swipe ←", fontSize = 11.sp) }
            }
        }

        // 3. Smart Android Automation (Volume, Brightness, YouTube, Settings, WhatsApp)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(colors.surface)
                .border(1.dp, colors.outline, RoundedCornerShape(16.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Smart Device & Media Controls",
                color = CyberCyan,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.VolumeUp, contentDescription = "Volume", tint = NeonMagenta, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Media Volume: ${volumeSlider.toInt()}%", color = colors.onSurface, fontSize = 12.sp)
            }
            Slider(
                value = volumeSlider,
                onValueChange = {
                    volumeSlider = it
                    deviceControl.setMediaVolumePercent(it.toInt())
                },
                valueRange = 0f..100f
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.BrightnessHigh, contentDescription = "Brightness", tint = CyberCyan, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Brightness: ${brightnessSlider.toInt()}%", color = colors.onSurface, fontSize = 12.sp)
            }
            Slider(
                value = brightnessSlider,
                onValueChange = { brightnessSlider = it },
                onValueChangeFinished = {
                    val res = deviceControl.setBrightnessPercent(brightnessSlider.toInt())
                    automationFeedback = res.message
                },
                valueRange = 5f..100f
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = youtubeQueryInput,
                    onValueChange = { youtubeQueryInput = it },
                    placeholder = { Text("YouTube search...", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = {
                        if (youtubeQueryInput.isNotBlank()) {
                            automationFeedback = deviceControl.searchYouTube(youtubeQueryInput).message
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NeonMagenta),
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Text("YouTube", fontSize = 12.sp)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedButton(onClick = { deviceControl.openSettingsScreen("wifi") }, modifier = Modifier.weight(1f)) {
                    Text("Wi-Fi", fontSize = 11.sp)
                }
                OutlinedButton(onClick = { deviceControl.openSettingsScreen("bluetooth") }, modifier = Modifier.weight(1f)) {
                    Text("Bluetooth", fontSize = 11.sp)
                }
                OutlinedButton(onClick = { deviceControl.launchAppByName("WhatsApp") }, modifier = Modifier.weight(1f)) {
                    Text("WhatsApp", fontSize = 11.sp)
                }
            }

            // WhatsApp & Active Notifications
            val whatsappMessages = notifications.filter { it.isWhatsApp }
            Text(
                text = "WhatsApp Notifications (${whatsappMessages.size})",
                color = CyberEmerald,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            if (whatsappMessages.isEmpty()) {
                Text(
                    text = "No recent WhatsApp messages captured yet.",
                    color = colors.onSurfaceVariant,
                    fontSize = 11.sp
                )
            } else {
                whatsappMessages.take(4).forEach { msg ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(colors.surfaceVariant)
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(msg.senderTitle, color = colors.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text(msg.messageText, color = colors.onSurfaceVariant, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        if (msg.canReply) {
                            TextButton(onClick = { replyDialogTarget = msg }) {
                                Text("Reply", color = NeonMagenta, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        // 4. Automation Task History
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(colors.surface)
                .border(1.dp, colors.outline, RoundedCornerShape(16.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Automation Task History (${history.size})",
                    color = CyberCyan,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                if (history.isNotEmpty()) {
                    IconButton(onClick = onClearHistory, modifier = Modifier.minimumInteractiveComponentSize()) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Clear History", tint = Color(0xFFEF4444))
                    }
                }
            }
            if (history.isEmpty()) {
                Text("No automation tasks executed yet.", color = colors.onSurfaceVariant, fontSize = 11.sp)
            } else {
                history.take(8).forEach { entry ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(colors.surfaceVariant)
                            .padding(10.dp)
                    ) {
                        Text(entry.goal, color = colors.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text("${entry.stepCount} steps • ${entry.statusSummary}", color = CyberEmerald, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionChip(
    title: String,
    active: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    testTag: String
) {
    val color = if (active) CyberEmerald else WarningAmber
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .testTag(testTag)
    ) {
        Icon(
            imageVector = if (active) Icons.Default.CheckCircle else icon,
            contentDescription = title,
            tint = color,
            modifier = Modifier.size(15.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = title,
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
