package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.api.AiProvider
import com.example.api.ApiKeyEntry
import com.example.api.ConnectionStatus
import com.example.api.ProviderConfig
import com.example.api.isGeminiLiveAudioModel
import com.example.ui.theme.CyberBorderGlow
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.CyberDarkBackground
import com.example.ui.theme.CyberDarkSurface
import com.example.ui.theme.CyberDarkSurfaceVariant
import com.example.ui.theme.CyberEmerald
import com.example.ui.theme.NeonMagenta
import com.example.ui.theme.NeonViolet
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.WarningAmber

@Composable
fun ProviderCard(
    provider: AiProvider,
    config: ProviderConfig,
    keys: List<ApiKeyEntry>,
    isActiveProvider: Boolean,
    initiallyExpanded: Boolean,
    onSetActiveProvider: () -> Unit,
    onAddKey: (rawKey: String, label: String, testNow: Boolean) -> Unit,
    onUpdateKey: (keyId: String, newRawKey: String?, newLabel: String, testNow: Boolean) -> Unit,
    onDeleteKey: (keyId: String) -> Unit,
    onSelectKey: (keyId: String) -> Unit,
    onTestKey: (keyId: String) -> Unit,
    onRevealKey: (keyId: String) -> String?,
    onModelSelected: (String) -> Unit,
    onBaseUrlChanged: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember(provider.id, initiallyExpanded) { mutableStateOf(initiallyExpanded) }
    var showAddForm by remember(provider.id) { mutableStateOf(keys.isEmpty()) }
    var editingKeyId by remember(provider.id) { mutableStateOf<String?>(null) }

    var keyInput by remember(provider.id) { mutableStateOf("") }
    var labelInput by remember(provider.id) { mutableStateOf("") }
    var showInputPlaintext by remember(provider.id) { mutableStateOf(false) }

    var showModelMenu by remember { mutableStateOf(false) }
    var customModelDraft by remember(config.selectedModel) { mutableStateOf(config.selectedModel) }
    var showCustomModelInput by remember { mutableStateOf(false) }
    var baseUrlDraft by remember(config.customBaseUrl) { mutableStateOf(config.customBaseUrl) }

    LaunchedEffect(keys.map { it.id }) {
        val currentEditId = editingKeyId
        if (currentEditId != null && keys.none { it.id == currentEditId }) {
            editingKeyId = null
            keyInput = ""
            labelInput = ""
        }
        if (keys.isEmpty()) {
            showAddForm = true
        }
    }

    val selectedKey = keys.firstOrNull { it.id == config.selectedKeyId } ?: keys.firstOrNull()
    val providerStatus = selectedKey?.status ?: ConnectionStatus.DISCONNECTED

    val borderColor = when {
        isActiveProvider -> NeonMagenta
        providerStatus == ConnectionStatus.CONNECTED -> CyberEmerald.copy(alpha = 0.6f)
        providerStatus == ConnectionStatus.ERROR -> Color(0xFFEF4444).copy(alpha = 0.65f)
        else -> CyberBorderGlow
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(CyberDarkSurface)
            .border(if (isActiveProvider) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(20.dp))
            .padding(16.dp)
            .testTag("provider_card_${provider.id}")
    ) {
        // Card Header Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = provider.displayName,
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (isActiveProvider) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(NeonMagenta.copy(alpha = 0.2f))
                                .border(1.dp, NeonMagenta.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                                .testTag("active_badge_${provider.id}")
                        ) {
                            Text(
                                text = "ACTIVE",
                                color = NeonMagenta,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 0.6.sp
                            )
                        }
                    }
                    if (provider.supportsLiveAudio && isGeminiLiveAudioModel(config.selectedModel)) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(CyberCyan.copy(alpha = 0.14f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.GraphicEq,
                                    contentDescription = "Live Audio",
                                    tint = CyberCyan,
                                    modifier = Modifier.size(11.dp)
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = "LIVE",
                                    color = CyberCyan,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = provider.description,
                    color = TextSecondary,
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                ConnectionStatusPill(
                    status = providerStatus,
                    modifier = Modifier.testTag("provider_status_${provider.id}")
                )
                IconButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Collapse ${provider.displayName}" else "Expand ${provider.displayName}",
                        tint = TextSecondary
                    )
                }
            }
        }

        // Quick Action Row: Active Provider Selector + Quick Test
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!isActiveProvider) {
                OutlinedButton(
                    onClick = onSetActiveProvider,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("activate_provider_${provider.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = CyberCyan,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Set Active Provider", color = CyberCyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(NeonMagenta.copy(alpha = 0.12f))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.RadioButtonChecked,
                        contentDescription = null,
                        tint = NeonMagenta,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Selected for Voice",
                        color = NeonMagenta,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            if (selectedKey != null) {
                OutlinedButton(
                    onClick = { onTestKey(selectedKey.id) },
                    enabled = selectedKey.status != ConnectionStatus.TESTING,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("test_connection_button_${provider.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.NetworkCheck,
                        contentDescription = "Test Connection",
                        tint = CyberEmerald,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Test", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // Expanded Configuration Body
        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.padding(top = 14.dp)) {
                // 1. Model Selection Section
                Text(
                    text = "Model",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(CyberDarkSurfaceVariant)
                                .border(1.dp, CyberBorderGlow, RoundedCornerShape(12.dp))
                                .clickable { showModelMenu = true }
                                .padding(horizontal = 12.dp, vertical = 12.dp)
                                .testTag("model_selector_${provider.id}"),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = config.selectedModel,
                                color = TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                imageVector = Icons.Default.ExpandMore,
                                contentDescription = "Choose model",
                                tint = CyberCyan,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        DropdownMenu(
                            expanded = showModelMenu,
                            onDismissRequest = { showModelMenu = false },
                            modifier = Modifier.background(CyberDarkSurfaceVariant)
                        ) {
                            config.availableModels.forEach { modelOption ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = modelOption,
                                            color = if (modelOption == config.selectedModel) NeonMagenta else TextPrimary,
                                            fontSize = 13.sp
                                        )
                                    },
                                    onClick = {
                                        onModelSelected(modelOption)
                                        customModelDraft = modelOption
                                        showModelMenu = false
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "+ Enter custom model ID...",
                                        color = CyberCyan,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                },
                                onClick = {
                                    showCustomModelInput = true
                                    showModelMenu = false
                                }
                            )
                        }
                    }

                    TextButton(
                        onClick = { showCustomModelInput = !showCustomModelInput },
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Text(
                            text = if (showCustomModelInput) "Done" else "Custom",
                            color = CyberCyan,
                            fontSize = 12.sp
                        )
                    }
                }

                AnimatedVisibility(visible = showCustomModelInput) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = customModelDraft,
                            onValueChange = { customModelDraft = it },
                            label = { Text("Custom Model ID", fontSize = 12.sp) },
                            singleLine = true,
                            colors = apiFieldColors(),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("custom_model_input_${provider.id}")
                        )
                        Button(
                            onClick = {
                                if (customModelDraft.isNotBlank()) {
                                    onModelSelected(customModelDraft.trim())
                                    showCustomModelInput = false
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = NeonViolet),
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Text("Apply", fontSize = 12.sp)
                        }
                    }
                }

                // 2. Base URL (Always editable for Custom OpenAI-compatible, or collapsible)
                if (provider == AiProvider.CUSTOM_OPENAI) {
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = baseUrlDraft,
                        onValueChange = {
                            baseUrlDraft = it
                            onBaseUrlChanged(it)
                        },
                        label = { Text("Base URL (OpenAI-compatible)", fontSize = 12.sp) },
                        placeholder = { Text(provider.defaultBaseUrl, color = TextSecondary) },
                        singleLine = true,
                        colors = apiFieldColors(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("base_url_input_${provider.id}")
                    )
                }

                // 3. Saved API Keys List (Multiple Keys Support)
                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Saved API Keys (${keys.size})",
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    TextButton(
                        onClick = {
                            editingKeyId = null
                            keyInput = ""
                            labelInput = ""
                            showAddForm = !showAddForm
                        },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("add_key_toggle_${provider.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add Key",
                            tint = CyberCyan,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (showAddForm && editingKeyId == null) "Cancel" else "Add Key",
                            color = CyberCyan,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (keys.isEmpty()) {
                    Text(
                        text = "No keys saved for ${provider.shortName} yet. Add a key below to connect.",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        keys.forEach { entry ->
                            SavedApiKeyRow(
                                entry = entry,
                                isSelected = entry.id == selectedKey?.id,
                                onSelect = { onSelectKey(entry.id) },
                                onTest = { onTestKey(entry.id) },
                                onEdit = {
                                    editingKeyId = entry.id
                                    labelInput = entry.label
                                    keyInput = onRevealKey(entry.id).orEmpty()
                                    showAddForm = true
                                },
                                onDelete = { onDeleteKey(entry.id) },
                                onRevealKey = { onRevealKey(entry.id) }
                            )
                        }
                    }
                }

                // 4. Add / Edit API Key Input Form
                AnimatedVisibility(visible = showAddForm) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(CyberDarkBackground.copy(alpha = 0.7f))
                            .border(1.dp, NeonViolet.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                            .padding(12.dp)
                    ) {
                        Text(
                            text = if (editingKeyId != null) "Edit API Key" else "Add ${provider.shortName} API Key",
                            color = CyberCyan,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = labelInput,
                            onValueChange = { labelInput = it },
                            label = { Text("Key Label (optional)", fontSize = 12.sp) },
                            placeholder = { Text("e.g., Personal, Work, Backup", color = TextSecondary, fontSize = 12.sp) },
                            singleLine = true,
                            colors = apiFieldColors(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("key_label_input_${provider.id}")
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = keyInput,
                            onValueChange = { keyInput = it },
                            label = { Text("API Key", fontSize = 12.sp) },
                            placeholder = { Text(provider.keyHint, color = TextSecondary, fontSize = 12.sp) },
                            singleLine = true,
                            visualTransformation = if (showInputPlaintext) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(
                                    onClick = { showInputPlaintext = !showInputPlaintext },
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("toggle_key_visibility_${provider.id}")
                                ) {
                                    Icon(
                                        imageVector = if (showInputPlaintext) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = if (showInputPlaintext) "Hide API Key" else "Show API Key",
                                        tint = CyberCyan
                                    )
                                }
                            },
                            colors = apiFieldColors(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("api_key_input_${provider.id}")
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val trimmedKey = keyInput.trim()
                                    val targetEditId = editingKeyId
                                    if (targetEditId != null) {
                                        onUpdateKey(targetEditId, trimmedKey.ifBlank { null }, labelInput, false)
                                        editingKeyId = null
                                        keyInput = ""
                                        labelInput = ""
                                        showAddForm = false
                                    } else if (trimmedKey.isNotEmpty()) {
                                        onAddKey(trimmedKey, labelInput, false)
                                        keyInput = ""
                                        labelInput = ""
                                        showAddForm = false
                                    }
                                },
                                enabled = keyInput.isNotBlank() || editingKeyId != null,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .minimumInteractiveComponentSize()
                                    .testTag("save_key_button_${provider.id}")
                            ) {
                                Text("Save Key", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }

                            Button(
                                onClick = {
                                    val trimmedKey = keyInput.trim()
                                    val targetEditId = editingKeyId
                                    if (targetEditId != null) {
                                        onUpdateKey(targetEditId, trimmedKey.ifBlank { null }, labelInput, true)
                                        editingKeyId = null
                                        keyInput = ""
                                        labelInput = ""
                                        showAddForm = false
                                    } else if (trimmedKey.isNotEmpty()) {
                                        onAddKey(trimmedKey, labelInput, true)
                                        keyInput = ""
                                        labelInput = ""
                                        showAddForm = false
                                    }
                                },
                                enabled = keyInput.isNotBlank() || editingKeyId != null,
                                colors = ButtonDefaults.buttonColors(containerColor = NeonMagenta),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .minimumInteractiveComponentSize()
                                    .testTag("save_and_test_button_${provider.id}")
                            ) {
                                Text("Save & Test", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SavedApiKeyRow(
    entry: ApiKeyEntry,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onTest: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onRevealKey: () -> String?
) {
    var revealedValue by remember(entry.id, entry.maskedKey) { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) Color(0xFF211640) else CyberDarkSurfaceVariant)
            .border(
                width = 1.dp,
                color = if (isSelected) CyberCyan.copy(alpha = 0.65f) else CyberBorderGlow,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable { onSelect() }
            .padding(10.dp)
            .testTag("saved_key_row_${entry.id}")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = isSelected,
                onClick = onSelect,
                colors = RadioButtonDefaults.colors(
                    selectedColor = CyberCyan,
                    unselectedColor = TextSecondary
                )
            )

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = entry.label,
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    ConnectionStatusPill(status = entry.status)
                }

                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = revealedValue ?: entry.maskedKey,
                    color = if (revealedValue != null) CyberCyan else TextSecondary,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Action icons row (minimum 48dp touch targets)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {
                    revealedValue = if (revealedValue == null) onRevealKey() else null
                },
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("reveal_saved_key_${entry.id}")
            ) {
                Icon(
                    imageVector = if (revealedValue != null) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (revealedValue != null) "Hide saved key" else "Show saved key",
                    tint = CyberCyan,
                    modifier = Modifier.size(18.dp)
                )
            }

            IconButton(
                onClick = onTest,
                enabled = entry.status != ConnectionStatus.TESTING,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("test_saved_key_${entry.id}")
            ) {
                if (entry.status == ConnectionStatus.TESTING) {
                    CircularProgressIndicator(
                        color = CyberCyan,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.NetworkCheck,
                        contentDescription = "Test key connection",
                        tint = CyberEmerald,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            IconButton(
                onClick = onEdit,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("edit_saved_key_${entry.id}")
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "Edit key",
                    tint = WarningAmber,
                    modifier = Modifier.size(18.dp)
                )
            }

            IconButton(
                onClick = onDelete,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("delete_saved_key_${entry.id}")
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete key",
                    tint = Color(0xFFEF4444),
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // Usage or Error details
        if (!entry.usageInfo.isNullOrBlank()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(CyberEmerald.copy(alpha = 0.12f))
                    .padding(horizontal = 8.dp, vertical = 5.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = CyberEmerald,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = entry.usageInfo,
                    color = CyberEmerald,
                    fontSize = 11.sp
                )
            }
        }

        if (!entry.lastError.isNullOrBlank()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFEF4444).copy(alpha = 0.14f))
                    .padding(horizontal = 8.dp, vertical = 5.dp)
                    .testTag("key_error_banner_${entry.id}")
            ) {
                Icon(
                    imageVector = Icons.Default.ErrorOutline,
                    contentDescription = "Error",
                    tint = Color(0xFFFCA5A5),
                    modifier = Modifier.size(13.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = entry.lastError,
                    color = Color(0xFFFCA5A5),
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
fun ConnectionStatusPill(
    status: ConnectionStatus,
    modifier: Modifier = Modifier
) {
    val (color, label) = when (status) {
        ConnectionStatus.CONNECTED -> CyberEmerald to "Connected"
        ConnectionStatus.TESTING -> CyberCyan to "Testing..."
        ConnectionStatus.ERROR -> Color(0xFFEF4444) to "Error"
        ConnectionStatus.DISCONNECTED -> TextSecondary to "Disconnected"
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = label,
            color = color,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun apiFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = CyberCyan,
    unfocusedBorderColor = CyberBorderGlow,
    focusedLabelColor = CyberCyan,
    unfocusedLabelColor = TextSecondary,
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    cursorColor = NeonMagenta
)
