package com.example.ui.screens

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.calls.ActiveCallState
import com.example.calls.AiCallAssistantPhase
import com.example.calls.CallAssistantVoice
import com.example.calls.CallAutomationPrefs
import com.example.calls.CallAutomationRecord
import com.example.calls.LiveCallStatus
import com.example.calls.TelecomCapabilityStatus
import com.example.calls.UnknownCallerPolicy
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.CyberEmerald
import com.example.ui.theme.NeonMagenta
import com.example.ui.theme.NeonViolet
import com.example.ui.theme.WarningAmber

@Composable
fun CallsAndSettingsScreen(
    callPrefs: CallAutomationPrefs,
    activeCall: ActiveCallState,
    callHistory: List<CallAutomationRecord>,
    capabilities: TelecomCapabilityStatus,
    importantAlert: String?,
    callFeedback: String?,
    prefilledDialNumber: String = "",
    onConsumePrefilledDial: () -> Unit = {},
    onRefreshCapabilities: () -> Unit,
    onPlaceCall: (String) -> Unit,
    onAnswerCall: (useSpeakerAi: Boolean) -> Unit,
    onRejectOrEndCall: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onManualTakeover: () -> Unit,
    onProcessCallerUtterance: (String) -> Unit,
    onUpdatePrefs: ((CallAutomationPrefs) -> CallAutomationPrefs) -> Unit,
    onAddTrusted: (name: String, phone: String) -> Unit,
    onRemoveTrusted: (id: String) -> Unit,
    onAddBlocked: (name: String, phone: String) -> Unit,
    onRemoveBlocked: (id: String) -> Unit,
    onClearHistory: () -> Unit,
    onDismissAlert: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme

    var dialInput by remember { mutableStateOf("") }
    var callerTestUtterance by remember { mutableStateOf("") }
    var newRuleName by remember { mutableStateOf("") }
    var newRulePhone by remember { mutableStateOf("") }

    LaunchedEffect(prefilledDialNumber) {
        if (prefilledDialNumber.isNotBlank()) {
            dialInput = prefilledDialNumber
            onConsumePrefilledDial()
        }
    }

    val permLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        onRefreshCapabilities()
    }

    val roleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        onRefreshCapabilities()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("calls_automation_screen"),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column {
            Text(
                text = "TELECOM & AI CALL ASSISTANT",
                color = CyberCyan,
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp
            )
            Text(
                text = "Call Automation & Console",
                color = colors.onBackground,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }

        if (!importantAlert.isNullOrBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(WarningAmber.copy(alpha = 0.16f))
                    .border(1.dp, WarningAmber, RoundedCornerShape(14.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = importantAlert,
                    color = colors.onSurface,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onDismissAlert) {
                    Text("Dismiss", fontSize = 11.sp)
                }
            }
        }

        if (!callFeedback.isNullOrBlank()) {
            Text(
                text = callFeedback,
                color = CyberCyan,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        // Telecom Roles & Permissions Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val allPermsGranted = capabilities.hasPhoneStatePermission &&
                capabilities.hasCallPhonePermission &&
                capabilities.hasAnswerCallsPermission &&
                capabilities.hasContactsPermission
            RoleStatusPill(
                label = if (allPermsGranted) "Call Perms: ON" else "Grant Call Permissions",
                active = allPermsGranted,
                onClick = {
                    val perms = buildList {
                        add(Manifest.permission.READ_PHONE_STATE)
                        add(Manifest.permission.CALL_PHONE)
                        add(Manifest.permission.READ_CONTACTS)
                        add(Manifest.permission.RECORD_AUDIO)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            add(Manifest.permission.ANSWER_PHONE_CALLS)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }.toTypedArray()
                    permLauncher.launch(perms)
                },
                tag = "grant_call_permissions_chip"
            )
            RoleStatusPill(
                label = if (capabilities.isDefaultDialer) "Default Dialer: ON" else "Set Default Dialer",
                active = capabilities.isDefaultDialer,
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val rm = context.getSystemService(Context.ROLE_SERVICE) as? RoleManager
                        val intent = rm?.createRequestRoleIntent(RoleManager.ROLE_DIALER)
                        if (intent != null) roleLauncher.launch(intent)
                    }
                },
                tag = "request_dialer_role_chip"
            )
            RoleStatusPill(
                label = if (capabilities.isCallScreeningApp) "Screening Role: ON" else "Enable Call Screening",
                active = capabilities.isCallScreeningApp,
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val rm = context.getSystemService(Context.ROLE_SERVICE) as? RoleManager
                        val intent = rm?.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)
                        if (intent != null) roleLauncher.launch(intent)
                    }
                },
                tag = "request_screening_role_chip"
            )
        }

        // Honest Android Cellular Audio Limitation Disclosure
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(colors.surfaceVariant)
                .border(1.dp, CyberCyan.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(Icons.Default.Info, contentDescription = null, tint = CyberCyan, modifier = Modifier.size(18.dp))
            Text(
                text = capabilities.audioLimitationExplanation,
                color = colors.onSurfaceVariant,
                fontSize = 11.sp
            )
        }

        // Live Call Console + ALWAYS AVAILABLE Manual Takeover
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(colors.surface)
                .border(
                    1.dp,
                    if (activeCall.status == LiveCallStatus.IDLE) colors.outline else NeonMagenta,
                    RoundedCornerShape(16.dp)
                )
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = if (activeCall.status == LiveCallStatus.IDLE) "No Active Telecom Call"
                        else "${activeCall.status.name}: ${activeCall.callerDisplayName}",
                        color = colors.onSurface,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (activeCall.phoneNumber.isNotBlank()) activeCall.phoneNumber else "Waiting for incoming or outgoing call",
                        color = colors.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                }
                Button(
                    onClick = onManualTakeover,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("call_manual_takeover_button")
                ) {
                    Icon(Icons.Default.PanTool, contentDescription = "Manual Takeover", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Manual Takeover", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }

            if (activeCall.status != LiveCallStatus.IDLE) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (activeCall.status == LiveCallStatus.RINGING_INCOMING) {
                        Button(
                            onClick = { onAnswerCall(false) },
                            colors = ButtonDefaults.buttonColors(containerColor = CyberEmerald),
                            modifier = Modifier.weight(1f).testTag("answer_call_button")
                        ) { Text("Answer", fontSize = 11.sp) }

                        Button(
                            onClick = { onAnswerCall(true) },
                            colors = ButtonDefaults.buttonColors(containerColor = NeonViolet),
                            modifier = Modifier.weight(1f).testTag("answer_ai_speaker_button")
                        ) { Text("AI (Speaker)", fontSize = 11.sp) }
                    }
                    Button(
                        onClick = onRejectOrEndCall,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                        modifier = Modifier.weight(1f).testTag("end_reject_call_button")
                    ) {
                        Icon(Icons.Default.CallEnd, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (activeCall.status == LiveCallStatus.RINGING_INCOMING) "Reject" else "End", fontSize = 11.sp)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(onClick = onToggleMute, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.MicOff, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (activeCall.isMuted) "Unmute" else "Mute", fontSize = 11.sp)
                    }
                    OutlinedButton(onClick = onToggleSpeaker, modifier = Modifier.weight(1f)) {
                        Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (activeCall.isSpeakerphone) "Speaker: ON" else "Speaker", fontSize = 11.sp)
                    }
                }
            }

            // AI Call Assistant Speakerphone Interaction & Privacy Guardrail Preview
            if (activeCall.aiDialogueLog.isNotEmpty() || callPrefs.aiCallAssistantEnabled) {
                Text(
                    text = "Disclosed AI Call Assistant (Never secretly records • Phase: ${activeCall.aiPhase.name})",
                    color = CyberCyan,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
                activeCall.aiDialogueLog.takeLast(4).forEach { line ->
                    Text(text = line, color = colors.onSurface, fontSize = 11.sp)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = callerTestUtterance,
                        onValueChange = { callerTestUtterance = it },
                        placeholder = { Text("Caller statement / speaker inquiry...", fontSize = 11.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            if (callerTestUtterance.isNotBlank()) {
                                onProcessCallerUtterance(callerTestUtterance.trim())
                                callerTestUtterance = ""
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = NeonMagenta),
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Text("Respond", fontSize = 11.sp)
                    }
                }
            }
        }

        // Outgoing Call Dialer
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(colors.surface)
                .border(1.dp, colors.outline, RoundedCornerShape(16.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Place Outgoing Call (Number or Contact Name)", color = CyberCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = dialInput,
                    onValueChange = { dialInput = it },
                    placeholder = { Text("e.g., +923001234567 or Ali", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("call_dial_input")
                )
                Button(
                    onClick = { if (dialInput.isNotBlank()) onPlaceCall(dialInput.trim()) },
                    enabled = dialInput.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = CyberEmerald),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("call_place_button")
                ) {
                    Icon(Icons.Default.Call, contentDescription = "Call", modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Call", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Dedicated Call Automation Settings Section (All autonomous modes default OFF)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(colors.surface)
                .border(1.dp, NeonViolet.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Call Automation Settings (Safe Defaults = OFF)",
                color = CyberCyan,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )

            CallSettingToggle(
                title = "Manual-Only Mode",
                subtitle = "Disable all automatic answering/rejection until turned off",
                checked = callPrefs.manualOnlyMode,
                tag = "call_manual_only_switch"
            ) { checked ->
                onUpdatePrefs {
                    if (checked) {
                        it.copy(manualOnlyMode = true, autoAnswerEnabled = false, aiCallAssistantEnabled = false)
                    } else {
                        it.copy(manualOnlyMode = false)
                    }
                }
            }

            CallSettingToggle(
                title = "Automatic Answering",
                subtitle = "Requires explicit enablement & non-manual mode",
                checked = callPrefs.autoAnswerEnabled,
                tag = "call_auto_answer_switch"
            ) { checked ->
                onUpdatePrefs { it.copy(autoAnswerEnabled = checked, manualOnlyMode = if (checked) false else it.manualOnlyMode) }
            }

            CallSettingToggle(
                title = "AI Call Assistant Conversation",
                subtitle = "Discloses AI participation, asks caller intent, alerts on urgency",
                checked = callPrefs.aiCallAssistantEnabled,
                tag = "call_ai_assistant_switch"
            ) { checked ->
                onUpdatePrefs { it.copy(aiCallAssistantEnabled = checked, manualOnlyMode = if (checked) false else it.manualOnlyMode) }
            }

            CallSettingToggle(
                title = "Speakerphone Bridge Consent",
                subtitle = "Required for aloud AI conversation since cellular streams are OS-restricted",
                checked = callPrefs.speakerphoneBridgeConsent,
                tag = "call_speaker_consent_switch"
            ) { checked ->
                onUpdatePrefs { it.copy(speakerphoneBridgeConsent = checked) }
            }

            CallSettingToggle(
                title = "Emergency Override",
                subtitle = "Repeated callers (3 min) & urgent calls bypass quiet hours/filters",
                checked = callPrefs.emergencyOverrideEnabled,
                tag = "call_emergency_override_switch"
            ) { checked ->
                onUpdatePrefs { it.copy(emergencyOverrideEnabled = checked) }
            }

            CallSettingToggle(
                title = "Important Caller Rules",
                subtitle = "High-priority alerts for trusted contacts & urgency keywords",
                checked = callPrefs.importantCallerRulesEnabled,
                tag = "call_important_rules_switch"
            ) { checked ->
                onUpdatePrefs { it.copy(importantCallerRulesEnabled = checked) }
            }

            CallSettingToggle(
                title = "Quiet Hours (${callPrefs.quietHoursStartHour}:00 – ${callPrefs.quietHoursEndHour}:00)",
                subtitle = "Silence non-emergency calls during quiet hours",
                checked = callPrefs.quietHoursEnabled,
                tag = "call_quiet_hours_switch"
            ) { checked ->
                onUpdatePrefs { it.copy(quietHoursEnabled = checked) }
            }

            Text(
                text = "Max AI Call Duration: ${callPrefs.maxAiConversationSeconds}s",
                color = colors.onSurface,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            Slider(
                value = callPrefs.maxAiConversationSeconds.toFloat(),
                onValueChange = { secs ->
                    onUpdatePrefs { it.copy(maxAiConversationSeconds = secs.toInt()) }
                },
                valueRange = 20f..180f
            )

            Text("Unknown Caller Handling", color = colors.onSurface, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                UnknownCallerPolicy.entries.forEach { policy ->
                    val selected = policy == callPrefs.unknownCallerPolicy
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) NeonMagenta else colors.surfaceVariant)
                            .border(1.dp, if (selected) NeonMagenta else colors.outline, RoundedCornerShape(10.dp))
                            .clickable { onUpdatePrefs { it.copy(unknownCallerPolicy = policy) } }
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Text(policy.displayName, color = if (selected) Color.White else colors.onSurface, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            Text("Call Assistant Voice", color = colors.onSurface, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CallAssistantVoice.entries.forEach { voice ->
                    val selected = voice == callPrefs.assistantVoice
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) NeonViolet else colors.surfaceVariant)
                            .border(1.dp, if (selected) NeonViolet else colors.outline, RoundedCornerShape(10.dp))
                            .clickable { onUpdatePrefs { it.copy(assistantVoice = voice) } }
                            .padding(vertical = 8.dp, horizontal = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(voice.displayName, color = if (selected) Color.White else colors.onSurface, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            // Trusted Contacts & Blocked Callers Manager
            Text("Trusted & Blocked Number Rules", color = CyberCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedTextField(
                    value = newRuleName,
                    onValueChange = { newRuleName = it },
                    placeholder = { Text("Name", fontSize = 11.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(0.9f)
                )
                OutlinedTextField(
                    value = newRulePhone,
                    onValueChange = { newRulePhone = it },
                    placeholder = { Text("Phone", fontSize = 11.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1.1f)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        if (newRulePhone.isNotBlank()) {
                            onAddTrusted(newRuleName, newRulePhone)
                            newRuleName = ""
                            newRulePhone = ""
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("+ Trusted", fontSize = 11.sp, color = CyberEmerald) }
                OutlinedButton(
                    onClick = {
                        if (newRulePhone.isNotBlank()) {
                            onAddBlocked(newRuleName, newRulePhone)
                            newRuleName = ""
                            newRulePhone = ""
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("+ Blocked", fontSize = 11.sp, color = Color(0xFFEF4444)) }
            }

            callPrefs.trustedContacts.forEach { contact ->
                RuleListRow("Trusted: ${contact.name} (${contact.phoneNumber})", CyberEmerald) {
                    onRemoveTrusted(contact.id)
                }
            }
            callPrefs.blockedCallers.forEach { contact ->
                RuleListRow("Blocked: ${contact.name} (${contact.phoneNumber})", Color(0xFFEF4444)) {
                    onRemoveBlocked(contact.id)
                }
            }
        }

        // Call Automation History
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
                Text("Call Automation History (${callHistory.size})", color = CyberCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                if (callHistory.isNotEmpty()) {
                    IconButton(onClick = onClearHistory, modifier = Modifier.minimumInteractiveComponentSize()) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Clear Call History", tint = Color(0xFFEF4444))
                    }
                }
            }
            if (callHistory.isEmpty()) {
                Text("No call events recorded yet.", color = colors.onSurfaceVariant, fontSize = 11.sp)
            } else {
                callHistory.take(10).forEach { rec ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(colors.surfaceVariant)
                            .padding(10.dp)
                    ) {
                        Text("${rec.direction} • ${rec.callerName} (${rec.phoneNumber})", color = colors.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text(rec.actionTaken, color = if (rec.wasUrgent) WarningAmber else CyberCyan, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun RoleStatusPill(label: String, active: Boolean, onClick: () -> Unit, tag: String) {
    val c = if (active) CyberEmerald else WarningAmber
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(c.copy(alpha = 0.14f))
            .border(1.dp, c.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .testTag(tag)
    ) {
        Icon(
            imageVector = if (active) Icons.Default.CheckCircle else Icons.Default.Security,
            contentDescription = label,
            tint = c,
            modifier = Modifier.size(15.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(label, color = c, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CallSettingToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    tag: String,
    onCheckedChange: (Boolean) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surfaceVariant)
            .padding(10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, color = colors.onSurface, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = colors.onSurfaceVariant, fontSize = 11.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(tag)
        )
    }
}

@Composable
private fun RuleListRow(label: String, accent: Color, onRemove: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Delete, contentDescription = "Remove rule", tint = colors.onSurfaceVariant, modifier = Modifier.size(16.dp))
        }
    }
}
