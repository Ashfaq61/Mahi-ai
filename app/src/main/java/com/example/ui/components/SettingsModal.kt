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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ai.MahiMood
import com.example.api.AiProvider
import com.example.api.ConnectionStatus
import com.example.preferences.AppLanguage
import com.example.preferences.AppThemeMode
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.NeonMagenta
import com.example.ui.theme.NeonViolet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsModal(
    currentMood: MahiMood,
    currentLanguage: AppLanguage = AppLanguage.ENGLISH,
    currentThemeMode: AppThemeMode = AppThemeMode.DARK,
    isContinuousMode: Boolean,
    showSubtitles: Boolean,
    voicePitch: Float,
    voiceSpeed: Float,
    activeProvider: AiProvider = AiProvider.GEMINI,
    activeModel: String = activeProvider.defaultModels.first(),
    connectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    onOpenApiSettings: () -> Unit = {},
    onLanguageSelected: (AppLanguage) -> Unit = {},
    onThemeSelected: (AppThemeMode) -> Unit = {},
    onMoodSelected: (MahiMood) -> Unit,
    onContinuousToggle: () -> Unit,
    onSubtitlesToggle: () -> Unit,
    onPitchChanged: (Float) -> Unit,
    onSpeedChanged: (Float) -> Unit,
    onTestVoice: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val colors = MaterialTheme.colorScheme

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surface,
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "MAHI PREFERENCES",
                        color = CyberCyan,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "Voice, Language & Appearance",
                        color = colors.onSurface,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = colors.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // API Keys & Providers Navigation Card
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.surfaceVariant)
                    .border(1.dp, CyberCyan.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
                    .clickable {
                        onDismiss()
                        onOpenApiSettings()
                    }
                    .padding(14.dp)
                    .testTag("settings_api_providers_button"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(NeonMagenta.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.VpnKey,
                            contentDescription = "API Keys & Providers",
                            tint = NeonMagenta,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "API Keys & Providers",
                            color = colors.onSurface,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${activeProvider.shortName} • $activeModel",
                            color = colors.onSurfaceVariant,
                            fontSize = 11.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        ConnectionStatusPill(status = connectionStatus)
                    }
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Open API Keys & Providers",
                    tint = CyberCyan
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Language Selector (English, Roman Urdu, Urdu)
            Text(
                text = "Assistant & Vision Language",
                color = colors.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AppLanguage.entries.forEach { lang ->
                    val selected = lang == currentLanguage
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (selected) NeonMagenta else colors.surfaceVariant)
                            .border(
                                1.dp,
                                if (selected) NeonMagenta else colors.outline,
                                RoundedCornerShape(12.dp)
                            )
                            .clickable { onLanguageSelected(lang) }
                            .padding(vertical = 10.dp)
                            .testTag("settings_lang_${lang.id}"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = lang.displayName,
                            color = if (selected) Color.White else colors.onSurface,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Theme Mode Selector (Dark, Light, System)
            Text(
                text = "Theme Appearance",
                color = colors.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AppThemeMode.entries.forEach { mode ->
                    val selected = mode == currentThemeMode
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (selected) NeonViolet else colors.surfaceVariant)
                            .border(
                                1.dp,
                                if (selected) NeonViolet else colors.outline,
                                RoundedCornerShape(12.dp)
                            )
                            .clickable { onThemeSelected(mode) }
                            .padding(vertical = 10.dp)
                            .testTag("settings_theme_${mode.id}"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = mode.displayName,
                            color = if (selected) Color.White else colors.onSurface,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Mood Selector
            Text(
                text = "Persona Mood",
                color = colors.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(10.dp))

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MahiMood.values().forEach { mood ->
                    val isSelected = mood == currentMood
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (isSelected) NeonMagenta.copy(alpha = 0.14f) else colors.surfaceVariant)
                            .border(
                                width = 1.dp,
                                color = if (isSelected) NeonMagenta else colors.outline,
                                shape = RoundedCornerShape(14.dp)
                            )
                            .clickable { onMoodSelected(mood) }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = mood.emoji, fontSize = 20.sp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = mood.displayName,
                                    color = if (isSelected) NeonMagenta else colors.onSurface,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = mood.description,
                                    color = colors.onSurfaceVariant,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Continuous Voice Mode Switch
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.surfaceVariant)
                    .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Continuous Voice Loop",
                        color = colors.onSurface,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Keep mic active after Mahi finishes speaking",
                        color = colors.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                }
                Switch(
                    checked = isContinuousMode,
                    onCheckedChange = { onContinuousToggle() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = NeonMagenta,
                        uncheckedTrackColor = colors.outline
                    ),
                    modifier = Modifier.testTag("continuous_mode_switch")
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Subtitles Toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.surfaceVariant)
                    .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Live Holographic Subtitles",
                        color = colors.onSurface,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Show speech captions in real-time",
                        color = colors.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                }
                Switch(
                    checked = showSubtitles,
                    onCheckedChange = { onSubtitlesToggle() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = CyberCyan,
                        uncheckedTrackColor = colors.outline
                    )
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Voice Pitch Slider
            Text(
                text = "Voice Pitch: ${String.format("%.2f", voicePitch)}x",
                color = colors.onSurface,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            Slider(
                value = voicePitch,
                onValueChange = onPitchChanged,
                valueRange = 0.85f..1.45f,
                colors = SliderDefaults.colors(
                    thumbColor = NeonMagenta,
                    activeTrackColor = NeonMagenta,
                    inactiveTrackColor = colors.outline
                )
            )

            // Voice Speed Slider
            Text(
                text = "Speech Cadence: ${String.format("%.2f", voiceSpeed)}x",
                color = colors.onSurface,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            Slider(
                value = voiceSpeed,
                onValueChange = onSpeedChanged,
                valueRange = 0.85f..1.35f,
                colors = SliderDefaults.colors(
                    thumbColor = CyberCyan,
                    activeTrackColor = CyberCyan,
                    inactiveTrackColor = colors.outline
                )
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Test Voice Button
            Button(
                onClick = onTestVoice,
                colors = ButtonDefaults.buttonColors(containerColor = NeonViolet),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Test Voice",
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Test Mahi's Voice", fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}
