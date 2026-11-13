package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.api.ConnectionStatus
import com.example.ui.components.ActionExecutionCard
import com.example.ui.components.LiveWaveformVisualizer
import com.example.ui.components.MahiOrb
import com.example.ui.components.MahiStatusBanner
import com.example.ui.components.QuickPromptChips
import com.example.ui.components.SettingsModal
import com.example.ui.components.SubtitleOverlay
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.CyberEmerald
import com.example.ui.theme.NeonMagenta
import com.example.ui.theme.NeonViolet
import com.example.ui.theme.WarningAmber
import com.example.viewmodel.AssistantVisualState
import com.example.viewmodel.MahiAssistantViewModel
import kotlin.random.Random

enum class MahiHomeTab(val label: String, val tag: String) {
    VOICE("Voice", "nav_tab_voice"),
    LENS("Lens", "nav_tab_lens"),
    RESEARCH("Research", "nav_tab_research"),
    AGENT("Agent", "nav_tab_agent"),
    CALLS("Calls", "nav_tab_calls")
}

@Composable
fun MahiScreen(
    viewModel: MahiAssistantViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val apiState by viewModel.apiManagerState.collectAsStateWithLifecycle()
    val userPrefs by viewModel.userPrefs.collectAsStateWithLifecycle()

    val liveSnapshot by viewModel.liveVisionSnapshot.collectAsStateWithLifecycle()
    val visionAnswer by viewModel.visionAnswer.collectAsStateWithLifecycle()
    val isVisionLoading by viewModel.isVisionLoading.collectAsStateWithLifecycle()
    val visionError by viewModel.visionError.collectAsStateWithLifecycle()

    val isResearchLoading by viewModel.isResearchLoading.collectAsStateWithLifecycle()
    val researchProgress by viewModel.researchProgress.collectAsStateWithLifecycle()
    val aiSearchResult by viewModel.aiSearchResult.collectAsStateWithLifecycle()
    val deepReport by viewModel.deepResearchReport.collectAsStateWithLifecycle()
    val exportedPdf by viewModel.exportedPdf.collectAsStateWithLifecycle()

    val screenSnapshot by viewModel.screenSnapshot.collectAsStateWithLifecycle()
    val notifications by viewModel.activeNotifications.collectAsStateWithLifecycle()
    val agentPlanSteps by viewModel.agentPlanSteps.collectAsStateWithLifecycle()
    val isAgentRunning by viewModel.isAgentRunning.collectAsStateWithLifecycle()
    val pendingConfirmation by viewModel.pendingSensitiveConfirmation.collectAsStateWithLifecycle()
    val automationHistory by viewModel.automationHistory.collectAsStateWithLifecycle()
    val screenAiAnalysis by viewModel.screenAiAnalysis.collectAsStateWithLifecycle()
    val installedApps by viewModel.installedApps.collectAsStateWithLifecycle()
    val gestureStatus by viewModel.gestureStatus.collectAsStateWithLifecycle()
    val isContinuousScrolling by viewModel.isContinuousScrolling.collectAsStateWithLifecycle()
    val gestureHistory by viewModel.gestureHistory.collectAsStateWithLifecycle()

    val callPrefs by viewModel.callPrefs.collectAsStateWithLifecycle()
    val activeCall by viewModel.activeCall.collectAsStateWithLifecycle()
    val callHistory by viewModel.callHistory.collectAsStateWithLifecycle()
    val callCapabilities by viewModel.callCapabilities.collectAsStateWithLifecycle()
    val importantCallAlert by viewModel.importantCallAlert.collectAsStateWithLifecycle()
    val callFeedback by viewModel.callFeedback.collectAsStateWithLifecycle()
    val requestedTab by viewModel.requestedTab.collectAsStateWithLifecycle()
    val pendingDialNumber by viewModel.pendingDialNumber.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val isDarkSurface = colors.background.luminance() < 0.5f

    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasMicPermission = isGranted
        if (isGranted) {
            viewModel.startSession()
        }
    }

    var selectedTab by remember { mutableStateOf(MahiHomeTab.VOICE) }
    var showSettingsModal by remember { mutableStateOf(false) }
    var showApiSettingsScreen by remember { mutableStateOf(false) }
    var showTextInput by remember { mutableStateOf(false) }
    var typedQuery by remember { mutableStateOf("") }

    LaunchedEffect(requestedTab) {
        if (requestedTab == "CALLS") {
            selectedTab = MahiHomeTab.CALLS
            viewModel.consumeRequestedTab()
        }
    }

    // Auto-request mic permission on first launch if not granted
    LaunchedEffect(Unit) {
        if (!hasMicPermission) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    if (showApiSettingsScreen) {
        BackHandler { showApiSettingsScreen = false }
        ApiProvidersScreen(
            apiState = apiState,
            onBack = { showApiSettingsScreen = false },
            onSetActiveProvider = { viewModel.setActiveProvider(it) },
            onAddKey = { provider, rawKey, label, testNow ->
                viewModel.addAndOptionallyTestKey(provider, rawKey, label, testNow)
            },
            onUpdateKey = { provider, keyId, newRawKey, newLabel, testNow ->
                viewModel.updateApiKey(provider, keyId, newRawKey, newLabel, testNow)
            },
            onDeleteKey = { provider, keyId -> viewModel.deleteApiKey(provider, keyId) },
            onSelectKey = { provider, keyId -> viewModel.selectApiKey(provider, keyId) },
            onTestKey = { provider, keyId -> viewModel.testApiKeyConnection(provider, keyId) },
            onRevealKey = { keyId -> viewModel.revealDecryptedKey(keyId) },
            onModelSelected = { provider, model -> viewModel.setProviderModel(provider, model) },
            onBaseUrlChanged = { provider, url -> viewModel.setProviderBaseUrl(provider, url) },
            modifier = modifier
        )
        return
    }

    if (selectedTab != MahiHomeTab.VOICE) {
        BackHandler { selectedTab = MahiHomeTab.VOICE }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = colors.background,
        bottomBar = {
            NavigationBar(
                containerColor = colors.surface,
                tonalElevation = 4.dp
            ) {
                MahiHomeTab.entries.forEach { tab ->
                    val icon = when (tab) {
                        MahiHomeTab.VOICE -> Icons.Default.GraphicEq
                        MahiHomeTab.LENS -> Icons.Default.CenterFocusStrong
                        MahiHomeTab.RESEARCH -> Icons.Default.Search
                        MahiHomeTab.AGENT -> Icons.Default.SmartToy
                        MahiHomeTab.CALLS -> Icons.Default.Call
                    }
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = { Icon(imageVector = icon, contentDescription = tab.label) },
                        label = {
                            Text(
                                text = tab.label,
                                fontSize = 11.sp,
                                fontWeight = if (selectedTab == tab) FontWeight.Bold else FontWeight.Medium
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = NeonMagenta,
                            selectedTextColor = NeonMagenta,
                            indicatorColor = NeonMagenta.copy(alpha = 0.14f),
                            unselectedIconColor = colors.onSurfaceVariant,
                            unselectedTextColor = colors.onSurfaceVariant
                        ),
                        modifier = Modifier.testTag(tab.tag)
                    )
                }
            }
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                MahiHomeTab.VOICE -> {
                    // Ambient starfield & nebula background canvas
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val center = Offset(size.width / 2f, size.height * 0.45f)

                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    NeonViolet.copy(alpha = if (isDarkSurface) 0.16f else 0.10f),
                                    NeonMagenta.copy(alpha = if (isDarkSurface) 0.08f else 0.06f),
                                    Color.Transparent
                                ),
                                center = center,
                                radius = size.width * 0.85f
                            ),
                            radius = size.width * 0.85f,
                            center = center
                        )

                        if (isDarkSurface) {
                            val rand = Random(42)
                            for (i in 0..45) {
                                val x = rand.nextFloat() * size.width
                                val y = rand.nextFloat() * size.height
                                val starAlpha = 0.15f + rand.nextFloat() * 0.5f
                                val starRadius = 1f + rand.nextFloat() * 2f
                                drawCircle(
                                    color = Color.White.copy(alpha = starAlpha),
                                    radius = starRadius,
                                    center = Offset(x, y)
                                )
                            }
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .testTag("voice_home_tab"),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        // 1. Top Section: Status Banner & Quick Provider Pill
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            MahiStatusBanner(
                                visualState = uiState.visualState,
                                currentMood = uiState.currentMood,
                                isContinuousMode = uiState.isContinuousMode,
                                isMuted = uiState.isMuted,
                                connectionStatus = apiState.activeConnectionStatus,
                                onMoodClick = { showSettingsModal = true },
                                onContinuousToggle = { viewModel.toggleContinuousMode() },
                                onMuteToggle = { viewModel.toggleMute() },
                                onApiSettingsClick = { showApiSettingsScreen = true },
                                onSettingsClick = { showSettingsModal = true }
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            val activeKeyPresent = apiState.activeKey != null
                            val pillColor = when {
                                !activeKeyPresent -> NeonMagenta
                                apiState.activeConnectionStatus == ConnectionStatus.CONNECTED -> CyberEmerald
                                apiState.activeConnectionStatus == ConnectionStatus.ERROR -> Color(0xFFEF4444)
                                else -> CyberCyan
                            }
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(colors.surface.copy(alpha = 0.9f))
                                    .border(1.dp, pillColor.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                                    .clickable { showApiSettingsScreen = true }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                                    .testTag("home_provider_status_pill"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.VpnKey,
                                    contentDescription = "Manage API Keys",
                                    tint = pillColor,
                                    modifier = Modifier.size(13.dp)
                                )
                                Text(
                                    text = if (activeKeyPresent) {
                                        "${apiState.activeProvider.shortName} • ${apiState.activeConfig.selectedModel} • ${userPrefs.language.shortBadge}"
                                    } else {
                                        "${apiState.activeProvider.shortName} • Tap to Add API Key"
                                    },
                                    color = colors.onSurface,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            if (!uiState.backgroundMicNotice.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = uiState.backgroundMicNotice.orEmpty(),
                                    color = WarningAmber,
                                    fontSize = 11.sp,
                                    textAlign = TextAlign.Center
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            ActionExecutionCard(
                                result = uiState.lastExecutedTool,
                                onDismiss = { viewModel.dismissToolCard() }
                            )

                            if (uiState.visualState == AssistantVisualState.ERROR && !uiState.errorMessage.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(Color(0xFF2B101A))
                                        .border(1.dp, Color(0xFFEF4444).copy(alpha = 0.7f), RoundedCornerShape(14.dp))
                                        .padding(12.dp)
                                        .testTag("home_error_banner")
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.ErrorOutline,
                                            contentDescription = "API Error",
                                            tint = Color(0xFFFCA5A5),
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = uiState.errorMessage.orEmpty(),
                                            color = Color(0xFFFEE2E2),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Button(
                                        onClick = { showApiSettingsScreen = true },
                                        colors = ButtonDefaults.buttonColors(containerColor = NeonMagenta),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.testTag("error_open_api_settings_button")
                                    ) {
                                        Text("Open API Keys & Providers", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }

                        // 2. Middle Section: Central Interactive Mahi Orb & Status Prompt
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(vertical = 8.dp)
                        ) {
                            val averageAmplitude = remember(uiState.waveformBars) {
                                uiState.waveformBars.average().toFloat()
                            }

                            MahiOrb(
                                visualState = uiState.visualState,
                                amplitude = averageAmplitude,
                                onClick = {
                                    if (!hasMicPermission) {
                                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    } else {
                                        viewModel.toggleSession()
                                    }
                                },
                                orbSize = 186.dp
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            val (statusLabel, statusDetail) = when (uiState.visualState) {
                                AssistantVisualState.DISCONNECTED -> "Tap Orb to Start" to "Continuous real-time voice mode"
                                AssistantVisualState.CONNECTING -> "Connecting..." to "Linking with ${apiState.activeProvider.shortName}"
                                AssistantVisualState.LISTENING -> if (uiState.isMicActivelyCapturing) {
                                    "Listening to you..." to "Mic active • speak Urdu, Roman Urdu, or English"
                                } else {
                                    "Session Ready" to "Tap Push-to-Talk or speak when mic starts"
                                }
                                AssistantVisualState.THINKING -> "Processing..." to "Preparing response & actions"
                                AssistantVisualState.SPEAKING -> "Mahi is speaking" to "Speak or tap orb anytime to interrupt"
                                AssistantVisualState.RECONNECTING -> "Reconnecting..." to (uiState.reconnectMessage ?: "Preserving Live conversation")
                                AssistantVisualState.ERROR -> "Connection Error" to "Check API key, model, or network"
                            }

                            Text(
                                text = statusLabel,
                                color = when (uiState.visualState) {
                                    AssistantVisualState.LISTENING -> CyberCyan
                                    AssistantVisualState.SPEAKING -> NeonMagenta
                                    AssistantVisualState.THINKING -> NeonViolet
                                    AssistantVisualState.RECONNECTING -> WarningAmber
                                    else -> colors.onBackground
                                },
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            )

                            Text(
                                text = statusDetail,
                                color = colors.onSurfaceVariant,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            // Honest mic capture & live input level meter
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth(0.75f)
                                    .testTag("mic_level_indicator_row")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Mic,
                                    contentDescription = "Microphone Level",
                                    tint = if (uiState.isMicActivelyCapturing) CyberEmerald else colors.onSurfaceVariant,
                                    modifier = Modifier.size(14.dp)
                                )
                                LinearProgressIndicator(
                                    progress = { uiState.micLevel.coerceIn(0f, 1f) },
                                    color = if (uiState.isMicActivelyCapturing) CyberCyan else colors.onSurfaceVariant,
                                    trackColor = colors.surfaceVariant,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(5.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                )
                                Text(
                                    text = if (uiState.isMicActivelyCapturing) "LIVE" else "IDLE",
                                    color = if (uiState.isMicActivelyCapturing) CyberEmerald else colors.onSurfaceVariant,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            if (userPrefs.pushToTalkMode) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(20.dp))
                                        .background(NeonMagenta.copy(alpha = 0.2f))
                                        .border(1.dp, NeonMagenta, RoundedCornerShape(20.dp))
                                        .pointerInput(Unit) {
                                            detectTapGestures(
                                                onPress = {
                                                    if (hasMicPermission) {
                                                        viewModel.startPushToTalkHold()
                                                        tryAwaitRelease()
                                                        viewModel.releasePushToTalkHold()
                                                    } else {
                                                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                                    }
                                                }
                                            )
                                        }
                                        .padding(horizontal = 20.dp, vertical = 10.dp)
                                        .testTag("push_to_talk_hold_button")
                                ) {
                                    Text(
                                        text = "HOLD TO TALK (Push-to-Talk)",
                                        color = NeonMagenta,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        // 3. Bottom Section: Visualizer, Subtitles, Prompt Chips, and Input Bar
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            LiveWaveformVisualizer(
                                bars = uiState.waveformBars,
                                visualState = uiState.visualState,
                                height = 44.dp,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            SubtitleOverlay(
                                visualState = uiState.visualState,
                                userText = uiState.liveUserText,
                                assistantText = uiState.liveAssistantText,
                                showSubtitles = uiState.showSubtitles,
                                modifier = Modifier.padding(bottom = 6.dp)
                            )

                            QuickPromptChips(
                                onPromptSelected = { prompt ->
                                    if (!hasMicPermission) {
                                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    } else {
                                        viewModel.processUserQuery(prompt)
                                    }
                                },
                                modifier = Modifier.padding(bottom = 6.dp)
                            )

                            if (showTextInput) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(24.dp))
                                        .background(colors.surface)
                                        .border(1.dp, NeonViolet.copy(alpha = 0.6f), RoundedCornerShape(24.dp))
                                        .padding(horizontal = 12.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedTextField(
                                        value = typedQuery,
                                        onValueChange = { typedQuery = it },
                                        placeholder = { Text("Say or type anything to Mahi...", color = colors.onSurfaceVariant, fontSize = 13.sp) },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                        keyboardActions = KeyboardActions(
                                            onSend = {
                                                if (typedQuery.isNotBlank()) {
                                                    viewModel.processUserQuery(typedQuery)
                                                    typedQuery = ""
                                                    showTextInput = false
                                                }
                                            }
                                        ),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = Color.Transparent,
                                            unfocusedBorderColor = Color.Transparent,
                                            focusedTextColor = colors.onSurface,
                                            unfocusedTextColor = colors.onSurface
                                        ),
                                        modifier = Modifier.weight(1f)
                                    )

                                    IconButton(
                                        onClick = {
                                            if (typedQuery.isNotBlank()) {
                                                viewModel.processUserQuery(typedQuery)
                                                typedQuery = ""
                                                showTextInput = false
                                            }
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.Send,
                                            contentDescription = "Send",
                                            tint = NeonMagenta
                                        )
                                    }
                                }
                            } else {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(16.dp))
                                                .background(colors.surfaceVariant)
                                                .clickable { viewModel.toggleSubtitles() }
                                                .padding(horizontal = 10.dp, vertical = 6.dp)
                                        ) {
                                            Text(
                                                text = if (uiState.showSubtitles) "Subtitles: ON" else "Subtitles: OFF",
                                                color = if (uiState.showSubtitles) CyberCyan else colors.onSurfaceVariant,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }

                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(16.dp))
                                                .background(colors.surfaceVariant)
                                                .clickable { viewModel.setPushToTalkMode(!userPrefs.pushToTalkMode) }
                                                .padding(horizontal = 10.dp, vertical = 6.dp)
                                        ) {
                                            Text(
                                                text = if (userPrefs.pushToTalkMode) "PTT: ON" else "Continuous",
                                                color = if (userPrefs.pushToTalkMode) NeonMagenta else CyberEmerald,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }

                                        if (uiState.visualState != AssistantVisualState.DISCONNECTED || isContinuousScrolling) {
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(16.dp))
                                                    .background(Color(0xFFDC2626).copy(alpha = 0.2f))
                                                    .border(1.dp, Color(0xFFDC2626), RoundedCornerShape(16.dp))
                                                    .clickable {
                                                        viewModel.stopSession()
                                                        viewModel.stopAllGestures()
                                                    }
                                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                                                    .testTag("voice_emergency_stop_button")
                                            ) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(Icons.Default.Stop, contentDescription = "Stop", tint = Color(0xFFEF4444), modifier = Modifier.size(12.dp))
                                                    Spacer(modifier = Modifier.width(3.dp))
                                                    Text("STOP", color = Color(0xFFEF4444), fontSize = 11.sp, fontWeight = FontWeight.Black)
                                                }
                                            }
                                        }
                                    }

                                    IconButton(
                                        onClick = { showTextInput = true },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Keyboard,
                                            contentDescription = "Text Input",
                                            tint = colors.onSurfaceVariant,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                MahiHomeTab.LENS -> {
                    NeuralLensScreen(
                        analyzer = viewModel.neuralLensAnalyzer,
                        liveSnapshot = liveSnapshot,
                        selectedLanguage = userPrefs.language,
                        aiVisionAnswer = visionAnswer,
                        isAnalyzingAi = isVisionLoading,
                        visionError = visionError,
                        onLanguageSelected = { viewModel.setLanguage(it) },
                        onAskAiVision = { viewModel.askVisionQuestion(it) },
                        onAnalyzePhotoUri = { viewModel.analyzeVisionPhotoUri(it) },
                        onSpeakAnswer = { viewModel.speakText(it) },
                        onOpenApiSettings = { showApiSettingsScreen = true }
                    )
                }

                MahiHomeTab.RESEARCH -> {
                    SearchAndResearchScreen(
                        selectedLanguage = userPrefs.language,
                        isLoading = isResearchLoading,
                        progressMessage = researchProgress,
                        searchResult = aiSearchResult,
                        deepReport = deepReport,
                        exportedPdf = exportedPdf,
                        onLanguageSelected = { viewModel.setLanguage(it) },
                        onRunAiSearch = { viewModel.runAiSearch(it) },
                        onRunDeepResearch = { viewModel.runDeepResearch(it) },
                        onExportPdf = { viewModel.exportResearchPdf(it) },
                        onSpeakSummary = { viewModel.speakText(it) }
                    )
                }

                MahiHomeTab.AGENT -> {
                    AgentAutomationScreen(
                        deviceControl = viewModel.deviceControlHelper,
                        screenSnapshot = screenSnapshot,
                        notifications = notifications,
                        planSteps = agentPlanSteps,
                        isAgentRunning = isAgentRunning,
                        pendingConfirmation = pendingConfirmation,
                        history = automationHistory,
                        screenAiAnalysis = screenAiAnalysis,
                        installedApps = installedApps,
                        gestureStatus = gestureStatus,
                        isContinuousScrolling = isContinuousScrolling,
                        gestureHistory = gestureHistory,
                        onRunGoal = { viewModel.runAgentGoal(it) },
                        onStopAgent = { viewModel.stopAgent() },
                        onRespondConfirmation = { viewModel.respondToSensitiveConfirmation(it) },
                        onAnalyzeCurrentScreenWithAi = { viewModel.analyzeCurrentScreenWithAi() },
                        onSendAuthorizedWhatsAppReply = { key, sender, replyText ->
                            viewModel.sendAuthorizedWhatsAppReply(key, sender, replyText)
                        },
                        onClearHistory = { viewModel.clearAutomationHistory() },
                        onLaunchApp = { viewModel.launchAppDirect(it) },
                        onSearchInstalledApps = { viewModel.refreshInstalledApps(it) },
                        onStopGestures = { viewModel.stopAllGestures() },
                        onClearGestureHistory = { viewModel.clearAutomationHistory() }
                    )
                }

                MahiHomeTab.CALLS -> {
                    CallsAndSettingsScreen(
                        callPrefs = callPrefs,
                        activeCall = activeCall,
                        callHistory = callHistory,
                        capabilities = callCapabilities,
                        importantAlert = importantCallAlert,
                        callFeedback = callFeedback,
                        prefilledDialNumber = pendingDialNumber,
                        onConsumePrefilledDial = { viewModel.clearPendingDialNumber() },
                        onRefreshCapabilities = { viewModel.refreshCallCapabilities() },
                        onPlaceCall = { viewModel.placeOutgoingCall(it) },
                        onAnswerCall = { useSpeakerAi -> viewModel.answerIncomingCall(useSpeakerAi) },
                        onRejectOrEndCall = { viewModel.rejectOrEndCall() },
                        onToggleMute = { viewModel.toggleCallMute() },
                        onToggleSpeaker = { viewModel.toggleCallSpeakerphone() },
                        onManualTakeover = { viewModel.takeOverCallManually() },
                        onProcessCallerUtterance = { viewModel.processCallerUtteranceWithAiAssistant(it) },
                        onUpdatePrefs = { transform -> viewModel.updateCallPrefs(transform) },
                        onAddTrusted = { name, phone -> viewModel.addTrustedContact(name, phone) },
                        onRemoveTrusted = { id -> viewModel.removeTrustedContact(id) },
                        onAddBlocked = { name, phone -> viewModel.addBlockedCaller(name, phone) },
                        onRemoveBlocked = { id -> viewModel.removeBlockedCaller(id) },
                        onClearHistory = { viewModel.clearCallHistory() },
                        onDismissAlert = { viewModel.dismissImportantCallAlert() }
                    )
                }
            }

            // Settings Bottom Sheet Modal
            if (showSettingsModal) {
                SettingsModal(
                    currentMood = uiState.currentMood,
                    currentLanguage = userPrefs.language,
                    currentThemeMode = userPrefs.themeMode,
                    isContinuousMode = uiState.isContinuousMode,
                    isPushToTalkMode = userPrefs.pushToTalkMode,
                    isAutoLanguageDetection = userPrefs.autoLanguageDetection,
                    showSubtitles = uiState.showSubtitles,
                    voicePitch = uiState.voicePitch,
                    voiceSpeed = uiState.voiceSpeed,
                    activeProvider = apiState.activeProvider,
                    activeModel = apiState.activeConfig.selectedModel,
                    connectionStatus = apiState.activeConnectionStatus,
                    onOpenApiSettings = { showApiSettingsScreen = true },
                    onLanguageSelected = { viewModel.setLanguage(it) },
                    onThemeSelected = { viewModel.setThemeMode(it) },
                    onMoodSelected = { mood ->
                        viewModel.setMood(mood)
                    },
                    onContinuousToggle = { viewModel.toggleContinuousMode() },
                    onPushToTalkToggle = { viewModel.setPushToTalkMode(it) },
                    onAutoLanguageToggle = { viewModel.setAutoLanguageDetection(it) },
                    onSubtitlesToggle = { viewModel.toggleSubtitles() },
                    onPitchChanged = { viewModel.setVoicePitch(it) },
                    onSpeedChanged = { viewModel.setVoiceSpeed(it) },
                    onTestVoice = {
                        viewModel.processUserQuery("Say something confident and playful in your current voice!")
                    },
                    onDismiss = { showSettingsModal = false }
                )
            }
        }
    }
}
