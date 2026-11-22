package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.api.AiProvider
import com.example.api.ApiManagerState
import com.example.api.RegisteredModelInfo
import com.example.api.SecureApiKeyStore
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.CyberEmerald
import com.example.ui.theme.NeonMagenta
import com.example.ui.theme.NeonViolet

@Composable
fun ApiProvidersScreen(
    apiState: ApiManagerState,
    discoveredModels: List<RegisteredModelInfo>,
    remoteGroundingConfig: SecureApiKeyStore.RemoteGroundingConfig,
    isTestingConnection: Boolean,
    registryStatus: String,
    onSelectProvider: (AiProvider) -> Unit,
    onAddKeySlot: (AiProvider, String, String, String) -> Unit,
    onSelectSlot: (AiProvider, String) -> Unit,
    onDeleteSlot: (AiProvider, String) -> Unit,
    onTestConnection: (AiProvider) -> Unit,
    onRefreshModels: () -> Unit,
    onSelectModel: (AiProvider, String) -> Unit,
    onSaveRemoteGrounding: (Boolean, String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val activeProvider = apiState.activeProvider
    var labelInput by remember { mutableStateOf("Primary") }
    var rawKeyInput by remember { mutableStateOf("") }
    var baseUrlInput by remember(activeProvider) {
        mutableStateOf(apiState.customBaseUrls[activeProvider] ?: activeProvider.defaultBaseUrl)
    }
    var groundingExpanded by remember { mutableStateOf(false) }
    var groundingEnabled by remember(remoteGroundingConfig) { mutableStateOf(remoteGroundingConfig.enabled) }
    var groundingUrl by remember(remoteGroundingConfig) { mutableStateOf(remoteGroundingConfig.endpointUrl) }
    var groundingToken by remember(remoteGroundingConfig) { mutableStateOf(if (remoteGroundingConfig.hasToken) "••••••••" else "") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("api_providers_screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("AI BRAIN • ENCRYPTED API & MODEL REGISTRY", color = CyberCyan, fontSize = 11.sp, fontWeight = FontWeight.Black)
        Text("Multi-Provider Key Manager", color = colors.onBackground, fontSize = 17.sp, fontWeight = FontWeight.Bold)

        // Provider Selector Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AiProvider.entries.forEach { prov ->
                val sel = prov == activeProvider
                val hasKey = apiState.slotsByProvider[prov].orEmpty().isNotEmpty()
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (sel) NeonViolet else colors.surfaceVariant)
                        .border(1.dp, if (hasKey) CyberEmerald else colors.outline, RoundedCornerShape(12.dp))
                        .clickable { onSelectProvider(prov) }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .testTag("provider_tab_${prov.name.lowercase()}")
                ) {
                    Text(
                        text = if (hasKey) "✓ ${prov.displayName}" else prov.displayName,
                        color = if (sel) Color.White else colors.onSurface,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // Add Encrypted Key Slot
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(colors.surface)
                .border(1.dp, colors.outline, RoundedCornerShape(16.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Add Key Slot (${activeProvider.displayName})", color = CyberCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = labelInput,
                onValueChange = { labelInput = it },
                label = { Text("Slot Label (e.g., Personal / Backup)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = rawKeyInput,
                onValueChange = { rawKeyInput = it },
                label = { Text("API Key (Stored AES-GCM encrypted, never logged)") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("api_key_input")
            )
            OutlinedTextField(
                value = baseUrlInput,
                onValueChange = { baseUrlInput = it },
                label = { Text("HTTPS Base URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        if (rawKeyInput.isNotBlank()) {
                            onAddKeySlot(activeProvider, labelInput, rawKeyInput.trim(), baseUrlInput.trim())
                            rawKeyInput = ""
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CyberEmerald),
                    modifier = Modifier.weight(1f).testTag("save_api_key_button")
                ) {
                    Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Save Encrypted")
                }
                OutlinedButton(
                    onClick = { onTestConnection(activeProvider) },
                    enabled = !isTestingConnection,
                    modifier = Modifier.weight(1f).testTag("test_connection_button")
                ) {
                    Text(if (isTestingConnection) "Testing…" else "Test Connection")
                }
            }
            Text(apiState.lastConnectionSummary, color = CyberCyan, fontSize = 11.sp)
        }

        // Saved Key Slots
        val slots = apiState.slotsByProvider[activeProvider].orEmpty()
        if (slots.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.surface)
                    .border(1.dp, colors.outline, RoundedCornerShape(16.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Saved ${activeProvider.displayName} Slots (${slots.size})", color = CyberCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                slots.forEach { slot ->
                    val isSelected = apiState.selectedSlotIds[activeProvider] == slot.id || slots.firstOrNull()?.id == slot.id
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) NeonViolet.copy(alpha = 0.16f) else colors.surfaceVariant)
                            .clickable { onSelectSlot(activeProvider, slot.id) }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("${slot.label} • ${slot.maskedPreview}", color = colors.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text(slot.lastTestMessage, color = if (slot.lastTestOk == true) CyberEmerald else colors.onSurfaceVariant, fontSize = 10.sp)
                        }
                        if (isSelected) {
                            Icon(Icons.Default.CheckCircle, contentDescription = "Active", tint = CyberEmerald, modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = { onDeleteSlot(activeProvider, slot.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete Slot", tint = Color(0xFFEF4444), modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }

        // Model Registry (Discovered & Capability Badges)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(colors.surface)
                .border(1.dp, CyberCyan.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("ModelRegistry & Live Capabilities", color = CyberCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(registryStatus, color = colors.onSurfaceVariant, fontSize = 10.sp)
                }
                OutlinedButton(onClick = onRefreshModels) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Detect", fontSize = 11.sp)
                }
            }

            val modelsToShow = discoveredModels.filter { it.provider == activeProvider }
                .ifEmpty {
                    activeProvider.defaultModels.map {
                        RegisteredModelInfo(activeProvider, it, it, emptySet(), "Default preset")
                    }
                }
            modelsToShow.take(10).forEach { m ->
                val chosen = apiState.selectedModels[activeProvider] == m.modelId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (chosen) CyberCyan.copy(alpha = 0.16f) else colors.surfaceVariant)
                        .clickable { onSelectModel(activeProvider, m.modelId) }
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(m.modelId, color = colors.onSurface, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        val caps = m.capabilities.joinToString(" • ") { it.key.uppercase() }.ifBlank { "PRESET" }
                        Text("$caps | ${m.pricingSummary}", color = CyberCyan, fontSize = 10.sp)
                    }
                    if (chosen) Text("SELECTED", color = CyberEmerald, fontSize = 10.sp, fontWeight = FontWeight.Black)
                }
            }
        }

        // Collapsible Remote Grounding Config (Set-of-Mark HTTP Grounder)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(colors.surface)
                .border(1.dp, colors.outline, RoundedCornerShape(16.dp))
                .padding(14.dp)
                .testTag("remote_grounding_card")
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { groundingExpanded = !groundingExpanded }
                    .testTag("remote_grounding_toggle"),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Remote Visual Grounding (Optional SoM)", color = CyberCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Icon(
                    imageVector = if (groundingExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = "Toggle Remote Grounding",
                    tint = colors.onSurface
                )
            }
            AnimatedVisibility(visible = groundingExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Enable Remote Grounder", color = colors.onSurface, fontSize = 12.sp)
                        Switch(checked = groundingEnabled, onCheckedChange = { groundingEnabled = it })
                    }
                    OutlinedTextField(
                        value = groundingUrl,
                        onValueChange = { groundingUrl = it },
                        label = { Text("HTTPS Grounding Endpoint") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = groundingToken,
                        onValueChange = { groundingToken = it },
                        label = { Text("Bearer Token") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = { onSaveRemoteGrounding(groundingEnabled, groundingUrl.trim(), groundingToken.trim()) },
                        colors = ButtonDefaults.buttonColors(containerColor = NeonMagenta),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Save Encrypted Grounding Config")
                    }
                }
            }
        }
    }
}
