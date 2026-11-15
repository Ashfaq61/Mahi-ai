package com.example.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.api.AiProvider
import com.example.api.ApiManagerState
import com.example.api.SecureApiKeyStore
import com.example.api.isGeminiLiveAudioModel
import com.example.ui.components.ConnectionStatusPill
import com.example.ui.components.ProviderCard
import com.example.ui.theme.CyberBorderGlow
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.CyberDarkBackground
import com.example.ui.theme.CyberDarkSurface
import com.example.ui.theme.CyberDarkSurfaceVariant
import com.example.ui.theme.CyberEmerald
import com.example.ui.theme.NeonMagenta
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun ApiProvidersScreen(
    apiState: ApiManagerState,
    onBack: () -> Unit,
    onSetActiveProvider: (AiProvider) -> Unit,
    onAddKey: (provider: AiProvider, rawKey: String, label: String, testNow: Boolean) -> Unit,
    onUpdateKey: (provider: AiProvider, keyId: String, newRawKey: String?, newLabel: String, testNow: Boolean) -> Unit,
    onDeleteKey: (provider: AiProvider, keyId: String) -> Unit,
    onSelectKey: (provider: AiProvider, keyId: String) -> Unit,
    onTestKey: (provider: AiProvider, keyId: String) -> Unit,
    onRevealKey: (keyId: String) -> String?,
    onModelSelected: (provider: AiProvider, model: String) -> Unit,
    onBaseUrlChanged: (provider: AiProvider, baseUrl: String) -> Unit,
    remoteGroundingConfig: SecureApiKeyStore.RemoteGroundingConfig = SecureApiKeyStore.RemoteGroundingConfig(),
    remoteGroundingStatus: String? = null,
    onSaveRemoteGrounding: (enabled: Boolean, endpointUrl: String, bearerToken: String?) -> Unit = { _, _, _ -> },
    onTestRemoteGrounding: (endpointUrl: String, bearerToken: String?) -> Unit = { _, _ -> },
    onClearRemoteGrounding: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = CyberDarkBackground,
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .navigationBarsPadding(),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 680.dp)
            ) {
                // Top Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("api_providers_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TextPrimary
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "API KEYS & PROVIDERS",
                            color = CyberCyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = "Voice Brain Configuration",
                            color = TextPrimary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    ConnectionStatusPill(
                        status = apiState.activeConnectionStatus,
                        modifier = Modifier.testTag("api_screen_active_status")
                    )
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("api_providers_list"),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Active Summary + Security Banner
                    item {
                        ActiveProviderSummaryCard(apiState = apiState)
                    }

                    // All 5 Supported Providers
                    items(
                        items = AiProvider.entries,
                        key = { it.id }
                    ) { provider ->
                        ProviderCard(
                            provider = provider,
                            config = apiState.getConfig(provider),
                            keys = apiState.getKeys(provider),
                            isActiveProvider = apiState.activeProvider == provider,
                            initiallyExpanded = apiState.activeProvider == provider,
                            onSetActiveProvider = { onSetActiveProvider(provider) },
                            onAddKey = { rawKey, label, testNow ->
                                onAddKey(provider, rawKey, label, testNow)
                            },
                            onUpdateKey = { keyId, newRawKey, newLabel, testNow ->
                                onUpdateKey(provider, keyId, newRawKey, newLabel, testNow)
                            },
                            onDeleteKey = { keyId -> onDeleteKey(provider, keyId) },
                            onSelectKey = { keyId -> onSelectKey(provider, keyId) },
                            onTestKey = { keyId -> onTestKey(provider, keyId) },
                            onRevealKey = onRevealKey,
                            onModelSelected = { model -> onModelSelected(provider, model) },
                            onBaseUrlChanged = { url -> onBaseUrlChanged(provider, url) }
                        )
                    }

                    item {
                        RemoteGroundingSettingsCard(
                            config = remoteGroundingConfig,
                            statusMessage = remoteGroundingStatus,
                            onSave = onSaveRemoteGrounding,
                            onTest = onTestRemoteGrounding,
                            onClear = onClearRemoteGrounding
                        )
                    }

                    item { Spacer(modifier = Modifier.height(24.dp)) }
                }
            }
        }
    }
}

@Composable
private fun ActiveProviderSummaryCard(apiState: ApiManagerState) {
    val activeProvider = apiState.activeProvider
    val activeConfig = apiState.activeConfig
    val activeKey = apiState.activeKey
    val isLiveAudio = activeProvider == AiProvider.GEMINI && isGeminiLiveAudioModel(activeConfig.selectedModel)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(CyberDarkSurfaceVariant)
            .border(1.dp, CyberBorderGlow, RoundedCornerShape(18.dp))
            .padding(14.dp)
            .testTag("active_provider_summary_card")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.VpnKey,
                    contentDescription = null,
                    tint = NeonMagenta,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "Active: ${activeProvider.displayName}",
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Model: ${activeConfig.selectedModel}",
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                }
            }

            if (activeKey != null) {
                Text(
                    text = activeKey.maskedKey,
                    color = CyberCyan,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            } else {
                Text(
                    text = "No key saved",
                    color = NeonMagenta,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = "Encrypted Storage",
                tint = CyberEmerald,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = "Keys are encrypted at rest with Android Keystore AES-256-GCM.",
                color = CyberEmerald,
                fontSize = 11.sp
            )
        }

        if (isLiveAudio) {
            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.GraphicEq,
                    contentDescription = "Gemini Live",
                    tint = CyberCyan,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Gemini Live Audio-to-Audio active (16kHz mic -> 24kHz voice, no silent text fallback).",
                    color = CyberCyan,
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun RemoteGroundingSettingsCard(
    config: SecureApiKeyStore.RemoteGroundingConfig,
    statusMessage: String?,
    onSave: (enabled: Boolean, endpointUrl: String, bearerToken: String?) -> Unit,
    onTest: (endpointUrl: String, bearerToken: String?) -> Unit,
    onClear: () -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(config.enabled) }
    var enabled by remember(config.enabled) { mutableStateOf(config.enabled) }
    var urlInput by remember(config.endpointUrl) { mutableStateOf(config.endpointUrl) }
    var tokenInput by remember(config.maskedToken) { mutableStateOf("") }
    var revealToken by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(CyberDarkSurface)
            .border(1.dp, CyberBorderGlow, RoundedCornerShape(18.dp))
            .padding(14.dp)
            .testTag("remote_grounding_card")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp)
                .testTag("remote_grounding_header"),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Advanced: GUI grounding server",
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (config.enabled && config.endpointUrl.isNotBlank()) {
                        "Custom endpoint active • Gemini fallback ready"
                    } else {
                        "OFF (Gemini Vision grounding is default)"
                    },
                    color = if (config.enabled) CyberEmerald else TextSecondary,
                    fontSize = 11.sp
                )
            }
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = CyberCyan
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Use hosted grounding server",
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = enabled,
                        onCheckedChange = { checked ->
                            enabled = checked
                            onSave(checked, urlInput, tokenInput.takeIf { it.isNotBlank() })
                        },
                        colors = SwitchDefaults.colors(checkedTrackColor = CyberCyan),
                        modifier = Modifier.testTag("remote_grounding_switch")
                    )
                }

                Text(
                    text = "Optional HTTP server (e.g., UI-TARS, Qwen-VL, OmniParser) returning {\"point\":[y,x]} coordinates. Tokens are AES-256-GCM encrypted.",
                    color = TextSecondary,
                    fontSize = 11.sp
                )

                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    label = { Text("Endpoint URL", fontSize = 12.sp) },
                    placeholder = { Text("https://your-host/ground", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("remote_grounding_url_input")
                )

                OutlinedTextField(
                    value = tokenInput,
                    onValueChange = { tokenInput = it },
                    label = {
                        Text(
                            text = if (config.hasToken) "Bearer Token (${config.maskedToken})" else "Bearer Token (optional)",
                            fontSize = 12.sp
                        )
                    },
                    placeholder = { Text("Leave blank to keep saved token", fontSize = 12.sp) },
                    singleLine = true,
                    visualTransformation = if (revealToken) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { revealToken = !revealToken }) {
                            Icon(
                                imageVector = if (revealToken) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (revealToken) "Hide token" else "Show token",
                                tint = CyberCyan
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("remote_grounding_token_input")
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            onSave(enabled, urlInput, tokenInput.takeIf { it.isNotBlank() })
                            tokenInput = ""
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = CyberCyan, contentColor = CyberDarkBackground),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("remote_grounding_save_button")
                    ) {
                        Text("Save", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            onTest(urlInput, tokenInput.takeIf { it.isNotBlank() })
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("remote_grounding_test_button")
                    ) {
                        Text("Test", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            enabled = false
                            urlInput = ""
                            tokenInput = ""
                            onClear()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("remote_grounding_clear_button")
                    ) {
                        Text("Clear", fontSize = 12.sp)
                    }
                }

                if (!statusMessage.isNullOrBlank()) {
                    Text(
                        text = statusMessage,
                        color = if (statusMessage.startsWith("Error", ignoreCase = true) ||
                            statusMessage.contains("failed", ignoreCase = true)
                        ) {
                            NeonMagenta
                        } else {
                            CyberEmerald
                        },
                        fontSize = 11.sp,
                        modifier = Modifier.testTag("remote_grounding_status_text")
                    )
                }
            }
        }
    }
}
