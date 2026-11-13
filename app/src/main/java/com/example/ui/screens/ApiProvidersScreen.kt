package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.api.AiProvider
import com.example.api.ApiManagerState
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
