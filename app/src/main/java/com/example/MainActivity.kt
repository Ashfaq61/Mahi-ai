package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.audio.MahiVoiceForegroundService
import com.example.automation.MahiNotificationService
import com.example.control.VolumeStreamType
import com.example.ui.components.ScreenDoctorModal
import com.example.ui.components.SettingsModal
import com.example.ui.screens.AgentAutomationScreen
import com.example.ui.screens.ApiProvidersScreen
import com.example.ui.screens.CallsAndSettingsScreen
import com.example.ui.screens.DiscoveryAndResearchScreen
import com.example.ui.screens.FeatureAuditAndDiagnosticsScreen
import com.example.ui.screens.MahiScreen
import com.example.ui.screens.NeuralLensScreen
import com.example.ui.screens.SmartControlScreen
import com.example.ui.theme.MahiAiTheme
import com.example.util.configureAppCheck
import com.example.viewmodel.MahiAssistantViewModel

enum class MahiTab(val title: String, val icon: ImageVector) {
    VOICE("Mahi", Icons.Default.AutoAwesome),
    APIS("APIs", Icons.Default.Key),
    AGENT("Agent", Icons.Default.Psychology),
    CONTROL("Control", Icons.Default.SettingsRemote),
    LENS("Lens", Icons.Default.CameraAlt),
    CALLS("Calls", Icons.Default.Call),
    LAB("Lab", Icons.Default.Science),
    AUDIT("Audit", Icons.Default.VerifiedUser)
}

class MainActivity : ComponentActivity() {
    private val viewModel: MahiAssistantViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        configureAppCheck(this, intent)
        handleIncomingDialIntent(intent)

        setContent {
            val prefs by viewModel.prefsFlow.collectAsStateWithLifecycle()
            val orbState by viewModel.orbState.collectAsStateWithLifecycle()
            val micLevel by viewModel.micLevel.collectAsStateWithLifecycle()
            val statusBanner by viewModel.statusBanner.collectAsStateWithLifecycle()
            val liveSubtitle by viewModel.liveSubtitle.collectAsStateWithLifecycle()
            val selectedMood by viewModel.selectedMood.collectAsStateWithLifecycle()
            val chatMessages by viewModel.chatMessages.collectAsStateWithLifecycle()
            val apiState by viewModel.apiState.collectAsStateWithLifecycle()
            val discoveredModels by viewModel.discoveredModels.collectAsStateWithLifecycle()
            val remoteGrounding by viewModel.remoteGroundingConfig.collectAsStateWithLifecycle()
            val isTestingApi by viewModel.isTestingApi.collectAsStateWithLifecycle()
            val registryStatus by viewModel.registryStatus.collectAsStateWithLifecycle()
            val wakeModelState by viewModel.wakeModelState.collectAsStateWithLifecycle()
            val memoryProfile by viewModel.memoryProfile.collectAsStateWithLifecycle()
            val learnedLabels by viewModel.learnedLabelCount.collectAsStateWithLifecycle()
            val screenSnapshot by viewModel.screenSnapshot.collectAsStateWithLifecycle()
            val notifications by viewModel.notifications.collectAsStateWithLifecycle()
            val agentPlanSteps by viewModel.agentPlanSteps.collectAsStateWithLifecycle()
            val isAgentRunning by viewModel.isAgentRunning.collectAsStateWithLifecycle()
            val isControlling by viewModel.isControllingScreen.collectAsStateWithLifecycle()
            val agentHistory by viewModel.agentHistory.collectAsStateWithLifecycle()
            val pendingConf by viewModel.pendingConfirmation.collectAsStateWithLifecycle()
            val screenAiAnalysis by viewModel.screenAiAnalysis.collectAsStateWithLifecycle()
            val agentNodes by viewModel.agentNodes.collectAsStateWithLifecycle()
            val agentBusLogs by viewModel.agentBusLogs.collectAsStateWithLifecycle()
            val isFlashlightOn by viewModel.isFlashlightOn.collectAsStateWithLifecycle()
            val brightnessPct by viewModel.brightnessPercent.collectAsStateWithLifecycle()
            val canWriteSettings by viewModel.canWriteSettings.collectAsStateWithLifecycle()
            val volumeLevels by viewModel.volumeLevels.collectAsStateWithLifecycle()
            val batteryInfo by viewModel.batteryInfo.collectAsStateWithLifecycle()
            val storageInfo by viewModel.storageInfo.collectAsStateWithLifecycle()
            val networkInfo by viewModel.networkInfo.collectAsStateWithLifecycle()
            val deviceInfo by viewModel.deviceInfo.collectAsStateWithLifecycle()
            val lastControlStatus by viewModel.lastControlStatus.collectAsStateWithLifecycle()
            val liveVision by viewModel.liveVisionSnapshot.collectAsStateWithLifecycle()
            val aiVisionAnswer by viewModel.aiVisionAnswer.collectAsStateWithLifecycle()
            val isAnalyzingVision by viewModel.isAnalyzingVision.collectAsStateWithLifecycle()
            val visionError by viewModel.visionError.collectAsStateWithLifecycle()
            val callPrefs by viewModel.callPrefs.collectAsStateWithLifecycle()
            val activeCall by viewModel.activeCall.collectAsStateWithLifecycle()
            val callHistory by viewModel.callHistory.collectAsStateWithLifecycle()
            val callCaps by viewModel.telecomCapabilities.collectAsStateWithLifecycle()
            val callAlert by viewModel.callImportantAlert.collectAsStateWithLifecycle()
            val callFeedback by viewModel.callFeedback.collectAsStateWithLifecycle()
            val prefilledDial by viewModel.prefilledDial.collectAsStateWithLifecycle()
            val permissions by viewModel.permissions.collectAsStateWithLifecycle()
            val webResults by viewModel.webResults.collectAsStateWithLifecycle()
            val gitHubRepos by viewModel.gitHubRepos.collectAsStateWithLifecycle()
            val hfModels by viewModel.hfModels.collectAsStateWithLifecycle()
            val nearbyPlaces by viewModel.nearbyPlaces.collectAsStateWithLifecycle()
            val offlineInspection by viewModel.offlineInspection.collectAsStateWithLifecycle()
            val knowledgeNotes by viewModel.knowledgeNotes.collectAsStateWithLifecycle()
            val plugins by viewModel.plugins.collectAsStateWithLifecycle()
            val patchProposals by viewModel.patchProposals.collectAsStateWithLifecycle()
            val releaseRecords by viewModel.releaseRecords.collectAsStateWithLifecycle()
            val researchStatus by viewModel.researchStatus.collectAsStateWithLifecycle()

            var currentTab by rememberSaveable { mutableStateOf(if (prefilledDial.isNotBlank()) MahiTab.CALLS else MahiTab.VOICE) }
            var showSettings by rememberSaveable { mutableStateOf(false) }
            var showDoctor by rememberSaveable { mutableStateOf(false) }

            MahiAiTheme(darkTheme = prefs.darkTheme) {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val useRail = maxWidth >= 600.dp
                    Scaffold(
                        contentWindowInsets = WindowInsets.safeDrawing,
                        bottomBar = {
                            if (!useRail) {
                                NavigationBar {
                                    MahiTab.entries.take(5).forEach { tab ->
                                        NavigationBarItem(
                                            selected = currentTab == tab,
                                            onClick = { currentTab = tab },
                                            icon = { Icon(tab.icon, contentDescription = tab.title) },
                                            label = { Text(tab.title, fontSize = 10.sp) }
                                        )
                                    }
                                    NavigationBarItem(
                                        selected = currentTab.ordinal >= 5,
                                        onClick = {
                                            currentTab = when (currentTab) {
                                                MahiTab.CALLS -> MahiTab.LAB
                                                MahiTab.LAB -> MahiTab.AUDIT
                                                else -> MahiTab.CALLS
                                            }
                                        },
                                        icon = { Icon(Icons.Default.Call, contentDescription = "More") },
                                        label = { Text(if (currentTab.ordinal >= 5) currentTab.title else "More", fontSize = 10.sp) }
                                    )
                                }
                            }
                        }
                    ) { innerPadding ->
                        Row(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                            if (useRail) {
                                NavigationRail {
                                    MahiTab.entries.forEach { tab ->
                                        NavigationRailItem(
                                            selected = currentTab == tab,
                                            onClick = { currentTab = tab },
                                            icon = { Icon(tab.icon, contentDescription = tab.title) },
                                            label = { Text(tab.title, fontSize = 10.sp) }
                                        )
                                    }
                                }
                            }
                            when (currentTab) {
                                MahiTab.VOICE -> MahiScreen(
                                    orbState = orbState,
                                    micLevel = micLevel,
                                    statusBannerText = statusBanner,
                                    liveSubtitleText = liveSubtitle,
                                    showSubtitles = prefs.showSubtitles,
                                    selectedMood = selectedMood,
                                    chatMessages = chatMessages,
                                    onOrbClick = { viewModel.toggleVoiceOrb() },
                                    onSelectMood = { viewModel.selectMood(it) },
                                    onSendPrompt = { viewModel.submitUserPrompt(it, fromVoice = false) },
                                    onOpenSettings = { showSettings = true },
                                    onOpenDoctor = { showDoctor = true }
                                )
                                MahiTab.APIS -> ApiProvidersScreen(
                                    apiState = apiState,
                                    discoveredModels = discoveredModels,
                                    remoteGroundingConfig = remoteGrounding,
                                    isTestingConnection = isTestingApi,
                                    registryStatus = registryStatus,
                                    onSelectProvider = { viewModel.keyStore.setActiveProvider(it) },
                                    onAddKeySlot = { prov, lbl, raw, url ->
                                        viewModel.keyStore.addSlot(prov, lbl, raw, baseUrl = url)
                                    },
                                    onSelectSlot = { prov, id -> viewModel.keyStore.selectSlot(prov, id) },
                                    onDeleteSlot = { prov, id -> viewModel.keyStore.deleteSlot(prov, id) },
                                    onTestConnection = { viewModel.testProviderConnection(it) },
                                    onRefreshModels = { viewModel.refreshDiscoveredModels() },
                                    onSelectModel = { prov, model -> viewModel.keyStore.setSelectedModel(prov, model) },
                                    onSaveRemoteGrounding = { en, url, tok -> viewModel.keyStore.saveRemoteGrounding(en, url, tok) }
                                )
                                MahiTab.AGENT -> AgentAutomationScreen(
                                    deviceControl = viewModel.deviceControl,
                                    screenSnapshot = screenSnapshot,
                                    notifications = notifications,
                                    planSteps = agentPlanSteps,
                                    isAgentRunning = isAgentRunning,
                                    isControllingScreen = isControlling,
                                    screenAgentStatus = screenAiAnalysis,
                                    pendingConfirmation = pendingConf,
                                    history = agentHistory,
                                    screenAiAnalysis = screenAiAnalysis,
                                    onRunGoal = { viewModel.runAutomationGoal(it) },
                                    onStopAgent = { viewModel.stopAllAutomation() },
                                    onRespondConfirmation = { viewModel.respondConfirmation(it) },
                                    onAnalyzeCurrentScreenWithAi = { viewModel.inspectCurrentScreen() },
                                    onSendAuthorizedWhatsAppReply = { key, _, msg -> MahiNotificationService.sendAuthorizedReply(key, msg) },
                                    onClearHistory = { viewModel.agentEngine.clearHistory() },
                                    agentNodes = agentNodes,
                                    agentBusLogs = agentBusLogs,
                                    onRunHeartbeatSweep = { viewModel.agentCoordinator.runHeartbeatSweep() }
                                )
                                MahiTab.CONTROL -> SmartControlScreen(
                                    isFlashlightOn = isFlashlightOn,
                                    brightnessPercent = brightnessPct,
                                    canWriteSettings = canWriteSettings,
                                    volumeLevels = volumeLevels,
                                    batteryInfo = batteryInfo,
                                    storageInfo = storageInfo,
                                    networkInfo = networkInfo,
                                    deviceInfo = deviceInfo,
                                    lastControlStatus = lastControlStatus,
                                    onBack = { currentTab = MahiTab.VOICE },
                                    onRefreshAll = { viewModel.refreshSystemTelemetry() },
                                    onLaunchApp = { viewModel.deviceControl.launchAppByName(it) },
                                    onNavigateToAgent = { currentTab = MahiTab.AGENT },
                                    onOpenQuickSettingsShade = { viewModel.smartphoneControl.quickSettingsManager.openQuickSettingsShade() },
                                    onOpenNotificationShade = { viewModel.smartphoneControl.quickSettingsManager.openNotificationShade() },
                                    onOpenWifiPanel = { viewModel.smartphoneControl.quickSettingsManager.openInternetOrWirelessPanel() },
                                    onOpenBluetoothPanel = { viewModel.smartphoneControl.quickSettingsManager.openBluetoothSettings() },
                                    onOpenMobileDataSettings = { viewModel.smartphoneControl.quickSettingsManager.openMobileDataSettings() },
                                    onOpenAirplaneSettings = { viewModel.smartphoneControl.quickSettingsManager.openAirplaneModeSettings() },
                                    onOpenNfcPanel = { viewModel.smartphoneControl.quickSettingsManager.openNfcSettings() },
                                    onToggleFlashlight = { viewModel.toggleFlashlight() },
                                    onSetVolumeStream = { stream, pct -> viewModel.setVolumeStream(stream, pct) },
                                    onToggleMuteMedia = { viewModel.smartphoneControl.volumeManager.setStreamMute(VolumeStreamType.MEDIA, volumeLevels.mediaPercent > 0) },
                                    onSetBrightness = { viewModel.setBrightness(it) },
                                    onOpenWriteSettings = { viewModel.deviceControl.openSettingsScreen("write_settings") },
                                    onOpenDndSettings = { viewModel.smartphoneControl.quickSettingsManager.openDndSettings() }
                                )
                                MahiTab.LENS -> NeuralLensScreen(
                                    analyzer = viewModel.neuralLensAnalyzer,
                                    liveSnapshot = liveVision,
                                    selectedLanguage = prefs.language,
                                    aiVisionAnswer = aiVisionAnswer,
                                    isAnalyzingAi = isAnalyzingVision,
                                    visionError = visionError,
                                    onLanguageSelected = { viewModel.preferencesStore.setLanguage(it) },
                                    onAskAiVision = { viewModel.askAiVision(it) },
                                    onAnalyzePhotoUri = { viewModel.analyzePhotoUri(it) },
                                    onSpeakAnswer = { text ->
                                        viewModel.syncTtsSettingsFromPrefs()
                                        viewModel.speechSynthesizer.speak(text, prefs.language)
                                    },
                                    onOpenApiSettings = { currentTab = MahiTab.APIS },
                                    cameraController = viewModel.cameraController
                                )
                                MahiTab.CALLS -> CallsAndSettingsScreen(
                                    callPrefs = callPrefs,
                                    activeCall = activeCall,
                                    callHistory = callHistory,
                                    capabilities = callCaps,
                                    importantAlert = callAlert,
                                    callFeedback = callFeedback,
                                    prefilledDialNumber = prefilledDial,
                                    onConsumePrefilledDial = { viewModel.setPrefilledDial("") },
                                    onRefreshCapabilities = { viewModel.callManager.refreshCapabilities() },
                                    onPlaceCall = { viewModel.callManager.resolveAndPlaceCall(it) },
                                    onAnswerCall = { viewModel.callManager.answerActiveCall(it) },
                                    onRejectOrEndCall = { viewModel.callManager.rejectOrEndActiveCall() },
                                    onToggleMute = { viewModel.callManager.toggleMute() },
                                    onToggleSpeaker = { viewModel.callManager.toggleSpeakerphone() },
                                    onManualTakeover = { viewModel.callManager.manualTakeover() },
                                    onProcessCallerUtterance = { viewModel.callManager.processCallerUtterance(it, prefs.language) },
                                    onUpdatePrefs = { mutator -> viewModel.updateCallPrefs(mutator) },
                                    onAddTrusted = { n, p -> viewModel.callManager.addTrustedContact(n, p) },
                                    onRemoveTrusted = { viewModel.callManager.removeTrustedContact(it) },
                                    onAddBlocked = { n, p -> viewModel.callManager.addBlockedCaller(n, p) },
                                    onRemoveBlocked = { viewModel.callManager.removeBlockedCaller(it) },
                                    onClearHistory = { viewModel.callManager.clearHistory() },
                                    onDismissAlert = { viewModel.callManager.dismissAlert() }
                                )
                                MahiTab.LAB -> DiscoveryAndResearchScreen(
                                    webResults = webResults,
                                    gitHubRepos = gitHubRepos,
                                    hfModels = hfModels,
                                    nearbyPlaces = nearbyPlaces,
                                    offlineInspection = offlineInspection,
                                    knowledgeNotes = knowledgeNotes,
                                    plugins = plugins,
                                    patchProposals = patchProposals,
                                    releaseRecords = releaseRecords,
                                    statusMessage = researchStatus,
                                    onSearchWeb = { viewModel.runWebResearch(it) },
                                    onSearchGitHub = { viewModel.runGitHubSearch(it) },
                                    onSearchHuggingFace = { viewModel.runHuggingFaceSearch(it) },
                                    onSearchNearby = { viewModel.runNearbySearch(it) },
                                    onSaveNote = { t, b -> viewModel.saveKnowledgeNote(t, b) },
                                    onDeleteNote = { viewModel.deleteKnowledgeNote(it) },
                                    onExportKnowledge = { viewModel.exportKnowledgeBackup() },
                                    onTogglePlugin = { viewModel.marketplaceManager.togglePlugin(it) },
                                    onTestPlugin = { viewModel.marketplaceManager.runStaticSecurityCheckAndSelfTest(it) },
                                    onStageSandboxPatch = { t, f, c -> viewModel.selfCodingPipeline.stagePatchInSandbox(t, f, c) },
                                    onApproveSandboxPatch = { viewModel.approveSandboxPatch(it) }
                                )
                                MahiTab.AUDIT -> FeatureAuditAndDiagnosticsScreen(
                                    permissions = permissions,
                                    onRefreshPermissions = { viewModel.refreshPermissionsAudit() },
                                    onOpenSettingsSection = { viewModel.deviceControl.openSettingsScreen(it) },
                                    onOpenRestrictedSettings = { viewModel.permissionAuditManager.openRestrictedSettingsAppInfo() },
                                    onRequestBatteryOptimization = { viewModel.permissionAuditManager.requestBatteryOptimizationExemption() }
                                )
                            }
                        }
                    }

                    if (showSettings) {
                        SettingsModal(
                            prefs = prefs,
                            wakeModelState = wakeModelState,
                            learnedLabelCount = learnedLabels,
                            memoryProfile = memoryProfile,
                            onLanguageChange = { viewModel.preferencesStore.setLanguage(it); viewModel.syncTtsSettingsFromPrefs() },
                            onVoiceGenderChange = { viewModel.preferencesStore.setVoiceGender(it); viewModel.syncTtsSettingsFromPrefs() },
                            onVoicePitchChange = { viewModel.preferencesStore.setVoicePitch(it); viewModel.syncTtsSettingsFromPrefs() },
                            onVoiceSpeedChange = { viewModel.setVoiceSpeed(it) },
                            onTtsClarityChange = { viewModel.setTtsClarityCompensation(it) },
                            onTestVoiceSpeed = { viewModel.testVoiceSpeedPreview() },
                            onContinuousModeChange = { viewModel.preferencesStore.setContinuousMode(it) },
                            onShowSubtitlesChange = { viewModel.preferencesStore.setShowSubtitles(it) },
                            onDarkThemeChange = { viewModel.preferencesStore.setDarkTheme(it) },
                            onAutopilotChange = { viewModel.preferencesStore.setAutopilotEnabled(it) },
                            onWakeDetectionChange = { en ->
                                viewModel.preferencesStore.setWakeDetectionEnabled(en)
                                if (en) MahiVoiceForegroundService.startWakeDetection(this@MainActivity)
                                else MahiVoiceForegroundService.disableWakeDetection(this@MainActivity)
                            },
                            onPreferGeminiLiveChange = { viewModel.preferencesStore.setPreferGeminiLiveVoice(it) },
                            onDownloadWakeModel = { viewModel.downloadWakeModel() },
                            onClearLearnedLabels = { viewModel.labelMemoryStore.clear() },
                            onMemoryConsentChange = { viewModel.preferencesStore.setMemoryConsentGranted(it) },
                            onForgetMemory = { viewModel.memoryStore.forgetEverything() },
                            onDismiss = { showSettings = false }
                        )
                    }

                    if (showDoctor) {
                        ScreenDoctorModal(
                            screenSnapshot = screenSnapshot,
                            learnedLabelsCount = learnedLabels,
                            lastExternalTargetPackage = prefs.lastExternalTargetPackage,
                            onDismiss = { showDoctor = false }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingDialIntent(intent)
    }

    private fun handleIncomingDialIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_DIAL) {
            val number = intent.data?.schemeSpecificPart.orEmpty()
            if (number.isNotBlank()) {
                viewModel.setPrefilledDial(number)
            }
        }
    }
}
