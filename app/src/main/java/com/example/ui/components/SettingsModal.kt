package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.audio.MahiSpeechSynthesizer
import com.example.audio.WakeModelState
import com.example.audio.WakeModelStatus
import com.example.memory.UserMemoryProfile
import com.example.preferences.AppLanguage
import com.example.preferences.MahiUserPreferences
import com.example.preferences.MahiVoiceGender
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.CyberEmerald
import com.example.ui.theme.NeonMagenta
import com.example.ui.theme.NeonViolet
import java.util.Locale

@Composable
fun SettingsModal(
    prefs: MahiUserPreferences,
    wakeModelState: WakeModelState,
    learnedLabelCount: Int,
    memoryProfile: UserMemoryProfile,
    onLanguageChange: (AppLanguage) -> Unit,
    onVoiceGenderChange: (MahiVoiceGender) -> Unit,
    onVoicePitchChange: (Float) -> Unit,
    onVoiceSpeedChange: (Float) -> Unit,
    onTtsClarityChange: (Boolean) -> Unit,
    onTestVoiceSpeed: () -> Unit,
    onContinuousModeChange: (Boolean) -> Unit,
    onShowSubtitlesChange: (Boolean) -> Unit,
    onDarkThemeChange: (Boolean) -> Unit,
    onAutopilotChange: (Boolean) -> Unit,
    onWakeDetectionChange: (Boolean) -> Unit,
    onPreferGeminiLiveChange: (Boolean) -> Unit,
    onDownloadWakeModel: () -> Unit,
    onClearLearnedLabels: () -> Unit,
    onMemoryConsentChange: (Boolean) -> Unit,
    onForgetMemory: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val effective = MahiSpeechSynthesizer.computeEffectiveTtsParams(
        requestedSpeed = prefs.voiceSpeed,
        requestedPitch = prefs.voicePitch,
        clarityCompensation = prefs.ttsClarityCompensation
    )

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = colors.surface,
            tonalElevation = 8.dp,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, CyberCyan.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                .testTag("settings_modal")
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Mahi Voice & App Settings", color = CyberCyan, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    TextButton(onClick = onDismiss) { Text("Done") }
                }

                // TTS Speech Speed Section with Clarity Compensation
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.surfaceVariant)
                        .border(1.dp, NeonMagenta.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "TTS Speech Speed: ${String.format(Locale.US, "%.2fx", prefs.voiceSpeed)}",
                            color = colors.onSurface,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.testTag("tts_speed_label")
                        )
                        Text(
                            text = "Eff Pitch ${String.format(Locale.US, "%.2f", effective.pitch)}",
                            color = CyberCyan,
                            fontSize = 11.sp
                        )
                    }
                    Slider(
                        value = prefs.voiceSpeed,
                        onValueChange = { onVoiceSpeedChange((it * 20f).toInt() / 20f) },
                        valueRange = MahiSpeechSynthesizer.MIN_SPEED..MahiSpeechSynthesizer.MAX_SPEED,
                        steps = 29,
                        modifier = Modifier.testTag("tts_speed_slider")
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        listOf(0.75f to "0.75x", 1.0f to "1.0x", 1.35f to "1.35x", 1.75f to "1.75x", 2.0f to "2.0x").forEach { (spd, lbl) ->
                            val selected = kotlin.math.abs(prefs.voiceSpeed - spd) < 0.04f
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (selected) NeonMagenta else colors.surface)
                                    .clickable { onVoiceSpeedChange(spd) }
                                    .padding(vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(lbl, color = if (selected) Color.White else colors.onSurface, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    SettingsToggleRow(
                        title = "High-Speed Clarity Balance",
                        subtitle = "Compensates pitch above 1.30x so fast speech remains natural & crisp",
                        checked = prefs.ttsClarityCompensation,
                        tag = "tts_clarity_switch",
                        onCheckedChange = onTtsClarityChange
                    )
                    Button(
                        onClick = onTestVoiceSpeed,
                        colors = ButtonDefaults.buttonColors(containerColor = CyberCyan),
                        modifier = Modifier.fillMaxWidth().testTag("tts_test_voice_button")
                    ) {
                        Text("Test Speech Speed Now", color = Color(0xFF070B16), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }

                // Pitch Slider
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.surfaceVariant)
                        .padding(12.dp)
                ) {
                    Text(
                        text = "Base Voice Pitch: ${String.format(Locale.US, "%.2f", prefs.voicePitch)}",
                        color = colors.onSurface,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Slider(
                        value = prefs.voicePitch,
                        onValueChange = onVoicePitchChange,
                        valueRange = 0.65f..1.5f
                    )
                }

                // Voice Gender
                Text("Voice Persona", color = CyberCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    MahiVoiceGender.entries.forEach { g ->
                        val sel = g == prefs.voiceGender
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (sel) NeonViolet else colors.surfaceVariant)
                                .clickable { onVoiceGenderChange(g) }
                                .padding(10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(g.displayName, color = if (sel) Color.White else colors.onSurface, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                // Language Selector
                Text("Language", color = CyberCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    AppLanguage.entries.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            pair.forEach { lang ->
                                val sel = lang == prefs.language
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (sel) CyberCyan else colors.surfaceVariant)
                                        .clickable { onLanguageChange(lang) }
                                        .padding(10.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(lang.displayName, color = if (sel) Color(0xFF070B16) else colors.onSurface, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }

                // Toggles
                SettingsToggleRow("Gemini Live Bidirectional Voice", "Use native WebSocket voice when Gemini key is set", prefs.preferGeminiLiveVoice, "prefer_live_switch", onPreferGeminiLiveChange)
                SettingsToggleRow("Continuous Listening Mode", "Automatically keep voice loop ready", prefs.isContinuousMode, "continuous_mode_switch", onContinuousModeChange)
                SettingsToggleRow("Live Subtitles Overlay", "Display live user & Mahi transcript banner", prefs.showSubtitles, "subtitles_switch", onShowSubtitlesChange)
                SettingsToggleRow("Dark Cyber Glow Theme", "Switch between Midnight Dark and Clean Light", prefs.darkTheme, "dark_theme_switch", onDarkThemeChange)
                SettingsToggleRow("Allow Screen Autopilot (Safe Default = OFF)", "Must be ON before Mahi can tap external apps", prefs.autopilotEnabled, "autopilot_switch", onAutopilotChange)
                SettingsToggleRow("Wake Up Detection (\"Hey Mahi\")", "Opt-in background trigger with single-mic arbitration", prefs.wakeDetectionEnabled, "wake_detection_switch", onWakeDetectionChange)

                if (wakeModelState.status != WakeModelStatus.READY) {
                    OutlinedButton(onClick = onDownloadWakeModel, modifier = Modifier.fillMaxWidth()) {
                        Text("Download Offline Wake Model (${wakeModelState.sizeLabel}) • ${wakeModelState.progressPercent}%", fontSize = 11.sp)
                    }
                } else {
                    Text("Offline Wake Model Ready", color = CyberEmerald, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }

                // Memory & Labels
                SettingsToggleRow("Consented User Memory (${memoryProfile.facts.size} facts)", "Inject preferred facts into AI context only when ON", prefs.memoryConsentGranted, "memory_consent_switch", onMemoryConsentChange)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = onClearLearnedLabels, modifier = Modifier.weight(1f)) {
                        Text("Clear Labels ($learnedLabelCount)", fontSize = 11.sp)
                    }
                    OutlinedButton(onClick = onForgetMemory, modifier = Modifier.weight(1f)) {
                        Text("Forget Memory", fontSize = 11.sp, color = Color(0xFFEF4444))
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun SettingsToggleRow(
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
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, color = colors.onSurface, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = colors.onSurfaceVariant, fontSize = 10.sp)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, modifier = Modifier.testTag(tag))
    }
}
