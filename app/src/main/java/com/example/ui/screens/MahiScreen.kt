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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
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
import com.example.ai.MahiMood
import com.example.data.ChatMessageEntity
import com.example.ui.components.LiveWaveformVisualizer
import com.example.ui.components.MahiOrb
import com.example.ui.components.MahiStatusBanner
import com.example.ui.components.OrbVisualState
import com.example.ui.components.QuickPromptChips
import com.example.ui.components.SubtitleOverlay
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.NeonMagenta
import com.example.ui.theme.NeonViolet

@Composable
fun MahiScreen(
    orbState: OrbVisualState,
    micLevel: Float,
    statusBannerText: String,
    liveSubtitleText: String,
    showSubtitles: Boolean,
    selectedMood: MahiMood,
    chatMessages: List<ChatMessageEntity>,
    onOrbClick: () -> Unit,
    onSelectMood: (MahiMood) -> Unit,
    onSendPrompt: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDoctor: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    var inputPrompt by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag("mahi_home_screen"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("MAHI AI", color = CyberCyan, fontSize = 18.sp, fontWeight = FontWeight.Black, letterSpacing = 1.2.sp)
                Text("Autonomous Voice & Screen Assistant", color = colors.onSurfaceVariant, fontSize = 11.sp)
            }
            Row {
                IconButton(
                    onClick = onOpenDoctor,
                    modifier = Modifier.minimumInteractiveComponentSize().testTag("open_doctor_button")
                ) {
                    Icon(Icons.Default.MedicalServices, contentDescription = "Screen Doctor", tint = CyberCyan)
                }
                IconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.minimumInteractiveComponentSize().testTag("open_settings_button")
                ) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings", tint = NeonMagenta)
                }
            }
        }

        MahiStatusBanner(statusText = statusBannerText, accent = orbState.accent)

        // Mood Chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MahiMood.entries.forEach { mood ->
                val sel = mood == selectedMood
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (sel) NeonViolet else colors.surfaceVariant)
                        .clickable { onSelectMood(mood) }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(mood.displayName, color = if (sel) Color.White else colors.onSurface, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // Cyber Glow Orb & Waveform
        MahiOrb(
            orbState = orbState,
            micLevel = micLevel,
            onClick = onOrbClick
        )
        LiveWaveformVisualizer(
            isActive = orbState == OrbVisualState.LISTENING || orbState == OrbVisualState.SPEAKING,
            micLevel = micLevel,
            accent = orbState.accent
        )

        if (showSubtitles && liveSubtitleText.isNotBlank()) {
            SubtitleOverlay(subtitle = liveSubtitleText)
        }

        QuickPromptChips(
            prompts = listOf(
                "Read Screen",
                "Battery status",
                "Summarize notifications",
                "Speech speed 1.35x",
                "Open YouTube"
            ),
            onPromptClick = onSendPrompt
        )

        // Conversation History Scroll Area
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(colors.surface)
                .border(1.dp, colors.outline, RoundedCornerShape(16.dp))
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (chatMessages.isEmpty()) {
                Text(
                    "Tap the Orb to talk via Gemini Live or SpeechRecognizer, or type a command below.",
                    color = colors.onSurfaceVariant,
                    fontSize = 12.sp
                )
            } else {
                chatMessages.takeLast(20).forEach { msg ->
                    val isUser = msg.role == "user"
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isUser) NeonViolet.copy(alpha = 0.16f) else colors.surfaceVariant)
                            .padding(10.dp)
                    ) {
                        Text(
                            text = if (isUser) "You" else "Mahi ${if (msg.modelUsed.isNotBlank()) "(${msg.modelUsed})" else ""}",
                            color = if (isUser) NeonMagenta else CyberCyan,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(msg.text, color = colors.onSurface, fontSize = 12.sp)
                    }
                }
            }
        }

        // Text Input Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = inputPrompt,
                onValueChange = { inputPrompt = it },
                placeholder = { Text("Ask Mahi or issue a command…", fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier.weight(1f).testTag("home_prompt_input")
            )
            Spacer(Modifier.width(6.dp))
            IconButton(
                onClick = {
                    val trimmed = inputPrompt.trim()
                    if (trimmed.isNotBlank()) {
                        inputPrompt = ""
                        onSendPrompt(trimmed)
                    }
                },
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .clip(RoundedCornerShape(12.dp))
                    .background(CyberCyan)
                    .testTag("home_send_button")
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = Color(0xFF070B16))
            }
        }
        Spacer(Modifier.height(2.dp))
    }
}
