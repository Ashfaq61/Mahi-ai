package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.agents.AgentBusMessage
import com.example.agents.AgentNodeInfo
import com.example.automation.AgentExecutionHistory
import com.example.automation.AgentStepItem
import com.example.automation.CapturedNotification
import com.example.automation.DeviceControlHelper
import com.example.automation.PendingConfirmationRequest
import com.example.automation.ScreenSnapshot
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.CyberEmerald
import com.example.ui.theme.NeonMagenta
import com.example.ui.theme.WarningAmber

@Composable
fun AgentAutomationScreen(
    deviceControl: DeviceControlHelper,
    screenSnapshot: ScreenSnapshot,
    notifications: List<CapturedNotification>,
    planSteps: List<AgentStepItem>,
    isAgentRunning: Boolean,
    isControllingScreen: Boolean,
    screenAgentStatus: String,
    pendingConfirmation: PendingConfirmationRequest?,
    history: List<AgentExecutionHistory>,
    screenAiAnalysis: String,
    onRunGoal: (String) -> Unit,
    onStopAgent: () -> Unit,
    onRespondConfirmation: (Boolean) -> Unit,
    onAnalyzeCurrentScreenWithAi: () -> Unit,
    onSendAuthorizedWhatsAppReply: (String, String, String) -> Unit,
    onClearHistory: () -> Unit,
    agentNodes: List<AgentNodeInfo> = emptyList(),
    agentBusLogs: List<AgentBusMessage> = emptyList(),
    onRunHeartbeatSweep: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    var goalText by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("agent_automation_screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("AUTONOMOUS SCREEN & MULTI-AGENT BUS", color = CyberCyan, fontSize = 11.sp, fontWeight = FontWeight.Black)
                Text("Mahi Autopilot & 17 Agents", color = colors.onBackground, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
            if (isControllingScreen || isAgentRunning) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "CONTROLLING",
                        color = WarningAmber,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(WarningAmber.copy(alpha = 0.16f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .testTag("mahi_controlling_screen_chip")
                    )
                    Button(
                        onClick = onStopAgent,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                        modifier = Modifier.testTag("controlling_screen_stop_button")
                    ) {
                        Text("STOP", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        if (screenAgentStatus.isNotBlank()) {
            Text(screenAgentStatus, color = CyberCyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }

        if (pendingConfirmation != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(WarningAmber.copy(alpha = 0.15f))
                    .border(1.dp, WarningAmber, RoundedCornerShape(14.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Confirm Sensitive Action: ${pendingConfirmation.title}", color = colors.onSurface, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(pendingConfirmation.detail, color = colors.onSurfaceVariant, fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onRespondConfirmation(true) }, colors = ButtonDefaults.buttonColors(containerColor = CyberEmerald), modifier = Modifier.weight(1f)) {
                        Text("Approve")
                    }
                    OutlinedButton(onClick = { onRespondConfirmation(false) }, modifier = Modifier.weight(1f)) {
                        Text("Deny")
                    }
                }
            }
        }

        // Goal Runner
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(colors.surface)
                .border(1.dp, colors.outline, RoundedCornerShape(14.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = goalText,
                onValueChange = { goalText = it },
                placeholder = { Text("e.g., YouTube kholo aur Mahi AI search karo") },
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { if (goalText.isNotBlank()) onRunGoal(goalText.trim()) },
                    colors = ButtonDefaults.buttonColors(containerColor = NeonMagenta),
                    modifier = Modifier.weight(1f)
                ) { Text("Run Task") }
                OutlinedButton(onClick = onAnalyzeCurrentScreenWithAi, modifier = Modifier.weight(1f)) {
                    Text("Inspect Screen")
                }
            }
            if (screenAiAnalysis.isNotBlank()) {
                Text(screenAiAnalysis, color = CyberEmerald, fontSize = 11.sp)
            }
        }

        // Accessibility Snapshot Summary
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(colors.surface)
                .border(1.dp, colors.outline, RoundedCornerShape(14.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("Live Screen Nodes (${screenSnapshot.elements.size})", color = CyberCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(screenSnapshot.readableSummary.take(400), color = colors.onSurfaceVariant, fontSize = 10.sp)
        }

        // 17-Agent Collaboration Network
        if (agentNodes.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.surface)
                    .border(1.dp, colors.outline, RoundedCornerShape(14.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("17-Agent Bus (${agentNodes.size} nodes)", color = CyberCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    OutlinedButton(onClick = onRunHeartbeatSweep) { Text("Heartbeat Sweep", fontSize = 10.sp) }
                }
                agentNodes.take(6).forEach { node ->
                    Text("${node.displayName} [${node.status.name}] • msgs=${node.messagesHandled}", color = colors.onSurfaceVariant, fontSize = 11.sp)
                }
                agentBusLogs.take(4).forEach { msg ->
                    Text("• ${msg.fromAgentId} -> ${msg.toAgentId}: ${msg.summary}", color = CyberEmerald, fontSize = 10.sp)
                }
            }
        }

        // Notifications
        if (notifications.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.surface)
                    .border(1.dp, colors.outline, RoundedCornerShape(14.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("Redacted Notifications (${notifications.size})", color = CyberCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                notifications.take(4).forEach { n ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("${n.appLabel}: ${n.sender} — ${n.bodyRedacted}", color = colors.onSurface, fontSize = 11.sp, modifier = Modifier.weight(1f))
                        if (n.hasReplyAction) {
                            Spacer(Modifier.width(6.dp))
                            OutlinedButton(onClick = { onSendAuthorizedWhatsAppReply(n.key, n.sender, "Noted, thank you!") }) {
                                Text("Quick Reply", fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        }

        // Execution History
        if (history.isNotEmpty()) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Recent Agent Runs (${history.size})", color = CyberCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                OutlinedButton(onClick = onClearHistory) { Text("Clear", fontSize = 10.sp) }
            }
            history.take(5).forEach { item ->
                Text("• ${item.goal} (${item.stepsExecuted} steps): ${item.summary}", color = colors.onSurfaceVariant, fontSize = 11.sp)
            }
        }
    }
}
