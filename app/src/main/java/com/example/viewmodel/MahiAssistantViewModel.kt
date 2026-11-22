package com.example.viewmodel

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.agents.AgentNetworkCoordinator
import com.example.ai.GeminiLiveClient
import com.example.ai.GeminiLiveStatus
import com.example.ai.MahiAiEngine
import com.example.ai.MahiMood
import com.example.api.AiProvider
import com.example.api.ModelCapability
import com.example.api.ModelRegistry
import com.example.api.ProviderChatMessage
import com.example.api.ProviderClient
import com.example.api.SecureApiKeyStore
import com.example.audio.MahiSpeechSynthesizer
import com.example.audio.MahiVoiceForegroundService
import com.example.audio.SpeechRecognitionManager
import com.example.audio.WakeModelManager
import com.example.automation.AppLabelMemoryStore
import com.example.automation.AutonomousAgentEngine
import com.example.automation.CommandExecutionGuard
import com.example.automation.DeviceControlHelper
import com.example.automation.MahiAccessibilityService
import com.example.automation.MahiNotificationService
import com.example.automation.PendingConfirmationRequest
import com.example.automation.RoutedVoiceCommand
import com.example.automation.SensitiveCategory
import com.example.automation.VoiceCommandRouter
import com.example.calls.CallAssistantManager
import com.example.calls.CallAutomationPrefs
import com.example.camera.MahiCameraController
import com.example.control.AllVolumeLevels
import com.example.control.BatteryInfoSnapshot
import com.example.control.DeviceInfoSnapshot
import com.example.control.NetworkInfoSnapshot
import com.example.control.PermissionAuditItem
import com.example.control.PermissionAuditManager
import com.example.control.SmartphoneControlManager
import com.example.control.StorageInfoSnapshot
import com.example.control.VolumeStreamType
import com.example.data.ChatMessageEntity
import com.example.data.MahiDataRepository
import com.example.data.MahiDatabase
import com.example.marketplace.ToolMarketplaceManager
import com.example.memory.UserMemoryStore
import com.example.pipeline.SelfCodingPipelineManager
import com.example.preferences.AppLanguage
import com.example.preferences.MahiPreferencesStore
import com.example.preferences.MahiVoiceGender
import com.example.research.GitHubRepoCandidate
import com.example.research.HuggingFaceModelCandidate
import com.example.research.NearbyPlaceResult
import com.example.research.OfflineLlmInspection
import com.example.research.ResearchAndDiscoveryEngine
import com.example.research.WebResearchResult
import com.example.tools.MahiToolsManager
import com.example.tools.MahiVisionToolDelegate
import com.example.tools.ToolAction
import com.example.tools.ToolExecutionResult
import com.example.ui.components.OrbVisualState
import com.example.vision.NeuralLensAnalyzer
import com.example.vision.NotificationUnderstandingManager
import com.example.vision.ScreenshotVisionManager
import com.example.vision.VisualQuestionAnswerManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

class MahiAssistantViewModel(application: Application) : AndroidViewModel(application) {
    private val appCtx = application.applicationContext
    private val db = MahiDatabase.getInstance(appCtx)
    val repository = MahiDataRepository(db)

    val preferencesStore = MahiPreferencesStore(appCtx)
    val keyStore = SecureApiKeyStore(appCtx)
    val providerClient = ProviderClient()
    val modelRegistry = ModelRegistry(providerClient, keyStore, repository)
    val aiEngine = MahiAiEngine(providerClient)
    val memoryStore = UserMemoryStore(appCtx)
    val labelMemoryStore = AppLabelMemoryStore(appCtx)
    val deviceControl = DeviceControlHelper(appCtx)
    val smartphoneControl = SmartphoneControlManager(appCtx, deviceControl)
    val agentEngine = AutonomousAgentEngine(appCtx, deviceControl, labelMemoryStore)
    val speechSynthesizer = MahiSpeechSynthesizer(appCtx)
    val speechRecognizer = SpeechRecognitionManager(appCtx)
    val geminiLiveClient = GeminiLiveClient()
    val wakeModelManager = WakeModelManager(appCtx)
    val callManager = CallAssistantManager(appCtx, aiEngine, keyStore)
    val cameraController = MahiCameraController(appCtx)
    val permissionAuditManager = PermissionAuditManager(appCtx)
    val agentCoordinator = AgentNetworkCoordinator(appCtx, repository)
    val marketplaceManager = ToolMarketplaceManager(appCtx)
    val researchEngine = ResearchAndDiscoveryEngine(appCtx, repository)
    val selfCodingPipeline = SelfCodingPipelineManager(appCtx, repository)
    val neuralLensAnalyzer = NeuralLensAnalyzer()
    val screenshotVision = ScreenshotVisionManager(providerClient, keyStore)
    val vqaManager = VisualQuestionAnswerManager(screenshotVision)

    val prefsFlow = preferencesStore.prefsFlow
    val apiState = keyStore.state
    val discoveredModels = modelRegistry.models
    val registryStatus = modelRegistry.status
    val remoteGroundingConfig = keyStore.remoteGrounding
    val wakeModelState = wakeModelManager.state
    val memoryProfile = memoryStore.profile
    val learnedLabelCount = labelMemoryStore.entryCount
    val screenSnapshot = MahiAccessibilityService.screenSnapshot
    val notifications = MahiNotificationService.notifications
    val agentPlanSteps = agentEngine.planSteps
    val agentHistory = agentEngine.history
    val isAgentRunning = agentEngine.isRunning
    val agentNodes = agentCoordinator.nodes
    val agentBusLogs = agentCoordinator.recentLogs
    val callPrefs = callManager.prefsFlow
    val activeCall = callManager.activeCall
    val callHistory = callManager.history
    val telecomCapabilities = callManager.capabilities
    val callImportantAlert = callManager.importantAlert
    val callFeedback = callManager.feedback
    val plugins = marketplaceManager.plugins
    val patchProposals = selfCodingPipeline.proposals
    val liveVisionSnapshot = neuralLensAnalyzer.liveSnapshot

    val chatMessages = repository.chatMessages.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val knowledgeNotes = repository.knowledgeNotes.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val releaseRecords = repository.releases.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _selectedMood = MutableStateFlow(MahiMood.SASSY)
    val selectedMood = _selectedMood.asStateFlow()

    private val _orbState = MutableStateFlow(OrbVisualState.IDLE)
    val orbState = _orbState.asStateFlow()

    private val _micLevel = MutableStateFlow(0f)
    val micLevel = _micLevel.asStateFlow()

    private val _statusBanner = MutableStateFlow("Ready • Tap Orb or type a command")
    val statusBanner = _statusBanner.asStateFlow()

    private val _liveSubtitle = MutableStateFlow("")
    val liveSubtitle = _liveSubtitle.asStateFlow()

    private val _isTestingApi = MutableStateFlow(false)
    val isTestingApi = _isTestingApi.asStateFlow()

    private val _pendingConfirmation = MutableStateFlow<PendingConfirmationRequest?>(null)
    val pendingConfirmation = _pendingConfirmation.asStateFlow()
    private var confirmationDeferred: CompletableDeferred<Boolean>? = null

    private val _isControllingScreen = MutableStateFlow(false)
    val isControllingScreen = _isControllingScreen.asStateFlow()

    private val _screenAiAnalysis = MutableStateFlow("")
    val screenAiAnalysis = _screenAiAnalysis.asStateFlow()

    private val _aiVisionAnswer = MutableStateFlow("")
    val aiVisionAnswer = _aiVisionAnswer.asStateFlow()

    private val _isAnalyzingVision = MutableStateFlow(false)
    val isAnalyzingVision = _isAnalyzingVision.asStateFlow()

    private val _visionError = MutableStateFlow<String?>(null)
    val visionError = _visionError.asStateFlow()

    private val _isFlashlightOn = MutableStateFlow(false)
    val isFlashlightOn = _isFlashlightOn.asStateFlow()

    private val _brightnessPercent = MutableStateFlow(55)
    val brightnessPercent = _brightnessPercent.asStateFlow()

    private val _canWriteSettings = MutableStateFlow(deviceControl.canModifySystemSettings())
    val canWriteSettings = _canWriteSettings.asStateFlow()

    private val _volumeLevels = MutableStateFlow(smartphoneControl.volumeManager.getAllVolumeLevels())
    val volumeLevels = _volumeLevels.asStateFlow()

    private val _batteryInfo = MutableStateFlow(BatteryInfoSnapshot())
    val batteryInfo = _batteryInfo.asStateFlow()

    private val _storageInfo = MutableStateFlow(StorageInfoSnapshot())
    val storageInfo = _storageInfo.asStateFlow()

    private val _networkInfo = MutableStateFlow(NetworkInfoSnapshot())
    val networkInfo = _networkInfo.asStateFlow()

    private val _deviceInfo = MutableStateFlow(DeviceInfoSnapshot())
    val deviceInfo = _deviceInfo.asStateFlow()

    private val _lastControlStatus = MutableStateFlow("")
    val lastControlStatus = _lastControlStatus.asStateFlow()

    private val _permissions = MutableStateFlow<List<PermissionAuditItem>>(emptyList())
    val permissions = _permissions.asStateFlow()

    private val _webResults = MutableStateFlow<List<WebResearchResult>>(emptyList())
    val webResults = _webResults.asStateFlow()

    private val _gitHubRepos = MutableStateFlow<List<GitHubRepoCandidate>>(emptyList())
    val gitHubRepos = _gitHubRepos.asStateFlow()

    private val _hfModels = MutableStateFlow<List<HuggingFaceModelCandidate>>(emptyList())
    val hfModels = _hfModels.asStateFlow()

    private val _nearbyPlaces = MutableStateFlow<List<NearbyPlaceResult>>(emptyList())
    val nearbyPlaces = _nearbyPlaces.asStateFlow()

    private val _offlineInspection = MutableStateFlow(researchEngine.inspectOfflineLlmReadiness())
    val offlineInspection = _offlineInspection.asStateFlow()

    private val _researchStatus = MutableStateFlow("")
    val researchStatus = _researchStatus.asStateFlow()

    private val _prefilledDial = MutableStateFlow("")
    val prefilledDial = _prefilledDial.asStateFlow()

    private val visionDelegate = object : MahiVisionToolDelegate {
        override suspend fun captureScreenImage(question: String): ToolExecutionResult {
            val jpegRes = MahiAccessibilityService.takeScreenshotJpeg(screenSnapshot.value)
            return jpegRes.fold(
                onSuccess = { bytes ->
                    vqaManager.rememberImage(bytes)
                    val ans = screenshotVision.analyzeScreenshotWithAi(bytes, question, prefsFlow.value.language)
                    ans.fold(
                        onSuccess = { ToolExecutionResult("captureScreenImage", "Screen Vision", it, true) },
                        onFailure = { ToolExecutionResult("captureScreenImage", "Screen Vision", it.localizedMessage ?: "Vision error", false) }
                    )
                },
                onFailure = { ToolExecutionResult("captureScreenImage", "Screen Vision", it.localizedMessage ?: "Screenshot unavailable", false) }
            )
        }

        override suspend fun describeScreen(question: String): ToolExecutionResult {
            val snap = MahiAccessibilityService.readScreenWithRetry(appCtx)
            return ToolExecutionResult("describeScreen", "Screen", snap.readableSummary, snap.elements.isNotEmpty())
        }

        override suspend fun analyzeScreenshot(question: String) = captureScreenImage(question)
        override suspend fun readTextFromCamera(explainWithAi: Boolean) = askAboutLastImage("Extract and read every piece of visible text.")
        override suspend fun readTextFromScreenshot(explainWithAi: Boolean) = captureScreenImage("Read all visible text accurately.")
        override suspend fun identifyObjects() = askAboutLastImage("Identify main objects visible in this image.")
        override suspend fun recognizeScene() = askAboutLastImage("Describe the overall scene and setting.")
        override suspend fun describeCameraView(question: String) = askAboutLastImage(question)

        override suspend fun summarizeNotifications(): ToolExecutionResult {
            val summary = NotificationUnderstandingManager.summarizeActiveNotifications()
            return ToolExecutionResult("summarizeNotifications", "Notifications", summary, true)
        }

        override suspend fun openGalleryAnalysis() = ToolExecutionResult("openGalleryAnalysis", "Gallery", "Open Lens tab to pick an image.", true)
        override suspend fun compareImages() = ToolExecutionResult("compareImages", "Compare", "Select photos in Lens tab.", true)

        override suspend fun askAboutLastImage(question: String): ToolExecutionResult {
            return vqaManager.askAboutLastImage(question, prefsFlow.value.language).fold(
                onSuccess = { ToolExecutionResult("askAboutLastImage", "Visual Q&A", it, true) },
                onFailure = { ToolExecutionResult("askAboutLastImage", "Visual Q&A", it.localizedMessage ?: "No image", false) }
            )
        }

        override suspend fun performScreenTask(goal: String): ToolExecutionResult {
            if (!prefsFlow.value.autopilotEnabled) {
                return ToolExecutionResult("performScreenTask", "Autopilot OFF", "Turn on 'Allow Screen Autopilot' in Settings first.", false)
            }
            _isControllingScreen.value = true
            val res = agentEngine.runScreenTask(goal)
            _isControllingScreen.value = false
            return ToolExecutionResult("performScreenTask", "Screen Agent", res.summary, res.completed)
        }

        override suspend fun setWatchMode(enabled: Boolean): ToolExecutionResult {
            MahiAccessibilityService.setWatchModeObserving(enabled)
            return ToolExecutionResult("setWatchMode", "Watch Mode", if (enabled) "Watch indicator enabled" else "Watch indicator off", true)
        }

        override suspend fun confirmSensitiveAction(title: String, reason: String): Boolean {
            return requestUserConfirmation(SensitiveCategory.MESSAGE, title, reason)
        }
    }

    val toolsManager = MahiToolsManager(
        context = appCtx,
        volumeManager = smartphoneControl.volumeManager,
        quickSettingsManager = smartphoneControl.quickSettingsManager,
        visionDelegate = visionDelegate
    )

    init {
        syncTtsSettingsFromPrefs()
        refreshSystemTelemetry()
        refreshPermissionsAudit()
        speechRecognizer.onRmsChangedNormalized = { _micLevel.value = it }
        speechRecognizer.onFinalResult = { spoken ->
            _liveSubtitle.value = "You: $spoken"
            submitUserPrompt(spoken, fromVoice = true)
        }
        speechRecognizer.onErrorRecover = { _, msg ->
            _orbState.value = OrbVisualState.IDLE
            _statusBanner.value = msg
        }
        MahiAccessibilityService.onStopAutomationFromOverlay = { stopAllAutomation() }
        MahiVoiceForegroundService.onStopAutopilotRequested = { stopAllAutomation() }
        MahiVoiceForegroundService.onStopRequestedFromNotification = { stopVoiceSession() }

        geminiLiveClient.onToolCall = { call ->
            toolsManager.executeAsync(ToolAction(call.name, call.params)).message
        }
        viewModelScope.launch {
            geminiLiveClient.status.collect { st ->
                when (st) {
                    GeminiLiveStatus.LISTENING -> _orbState.value = OrbVisualState.LISTENING
                    GeminiLiveStatus.THINKING -> _orbState.value = OrbVisualState.THINKING
                    GeminiLiveStatus.SPEAKING -> _orbState.value = OrbVisualState.SPEAKING
                    GeminiLiveStatus.ERROR, GeminiLiveStatus.BLOCKED -> {
                        _orbState.value = OrbVisualState.IDLE
                        _statusBanner.value = geminiLiveClient.lastError.value ?: st.label
                    }
                    else -> if (!isAgentRunning.value) _orbState.value = OrbVisualState.IDLE
                }
            }
        }
        viewModelScope.launch {
            geminiLiveClient.outputTranscript.collect { t ->
                if (t.isNotBlank()) _liveSubtitle.value = "Mahi: $t"
            }
        }
        viewModelScope.launch {
            modelRegistry.refreshIfStale(force = false)
        }
    }

    fun syncTtsSettingsFromPrefs() {
        val p = prefsFlow.value
        speechSynthesizer.updateVoiceConfig(
            pitch = p.voicePitch,
            speed = p.voiceSpeed,
            clarityCompensation = p.ttsClarityCompensation,
            gender = p.voiceGender,
            language = p.language
        )
    }

    fun setVoiceSpeed(speed: Float) {
        preferencesStore.setVoiceSpeed(speed)
        syncTtsSettingsFromPrefs()
    }

    fun setTtsClarityCompensation(enabled: Boolean) {
        preferencesStore.setTtsClarityCompensation(enabled)
        syncTtsSettingsFromPrefs()
    }

    fun testVoiceSpeedPreview() {
        syncTtsSettingsFromPrefs()
        val spd = prefsFlow.value.voiceSpeed
        val sample = when (prefsFlow.value.language) {
            AppLanguage.ROMAN_URDU -> "Salam! Yeh Mahi ki awaz ${String.format("%.2f", spd)}x speed par hai, bilkul saaf aur clear."
            else -> "Hello! This is Mahi speaking cleanly at ${String.format("%.2f", spd)} times speed with balanced clarity."
        }
        _liveSubtitle.value = "Mahi: $sample"
        speechSynthesizer.speak(sample, prefsFlow.value.language)
    }

    fun toggleVoiceOrb() {
        if (_orbState.value == OrbVisualState.LISTENING) {
            stopVoiceSession()
            return
        }
        if (ContextCompat.checkSelfPermission(appCtx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            _statusBanner.value = "Grant Microphone permission in Audits tab before starting voice."
            return
        }
        MahiVoiceForegroundService.pauseWakeForActiveSession(appCtx)
        MahiVoiceForegroundService.start(appCtx)
        val p = prefsFlow.value
        val geminiKey = keyStore.decryptSlotKey(AiProvider.GEMINI)
        if (p.preferGeminiLiveVoice && !geminiKey.isNullOrBlank()) {
            _orbState.value = OrbVisualState.THINKING
            _statusBanner.value = "Connecting Gemini Live Voice…"
            geminiLiveClient.connect(
                apiKey = geminiKey,
                voiceName = p.voiceGender.geminiVoiceName,
                systemPrompt = aiEngine.buildSystemPrompt(_selectedMood.value, p.language, memoryStore.buildPromptContext(p.memoryConsentGranted))
            )
        } else {
            _orbState.value = OrbVisualState.LISTENING
            _statusBanner.value = "Listening via Android SpeechRecognizer…"
            speechRecognizer.startListening(p.language)
        }
    }

    fun stopVoiceSession() {
        geminiLiveClient.disconnect()
        speechRecognizer.stopListening()
        speechSynthesizer.stop()
        MahiVoiceForegroundService.stop(appCtx)
        MahiVoiceForegroundService.resumeWakeIfEnabled(appCtx)
        _orbState.value = OrbVisualState.IDLE
        _statusBanner.value = "Voice stopped."
    }

    fun submitUserPrompt(rawPrompt: String, fromVoice: Boolean = false) {
        val clean = rawPrompt.trim()
        if (clean.isBlank()) return
        viewModelScope.launch {
            repository.saveChatMessage(
                ChatMessageEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = "default",
                    role = "user",
                    text = clean,
                    providerUsed = "",
                    modelUsed = "",
                    timestampMs = System.currentTimeMillis()
                )
            )
            CommandExecutionGuard.requiresExplicitConfirmation(clean)?.let { warning ->
                val ok = requestUserConfirmation(SensitiveCategory.DELETE, "Confirm Action", "$warning\n\nCommand: $clean")
                if (!ok) {
                    appendAssistantReply("Cancelled sensitive action per your decision.", speak = fromVoice)
                    return@launch
                }
            }

            when (val routed = VoiceCommandRouter.parseCommand(clean)) {
                is RoutedVoiceCommand.SetTtsSpeed -> {
                    setVoiceSpeed(routed.speed)
                    appendAssistantReply("Speech speed set to ${String.format("%.2f", routed.speed)}x with clarity compensation ${if (prefsFlow.value.ttsClarityCompensation) "ON" else "OFF"}.", speak = true)
                }
                is RoutedVoiceCommand.OpenApp -> {
                    val out = deviceControl.launchAppByName(routed.appName)
                    appendAssistantReply(out.message, speak = fromVoice)
                }
                is RoutedVoiceCommand.TapScreenElement -> {
                    val res = toolsManager.executeAsync(ToolAction("tapElement", mapOf("index" to (routed.index?.toString().orEmpty()))))
                    appendAssistantReply(res.message, speak = fromVoice)
                }
                is RoutedVoiceCommand.TypeOnScreen -> {
                    val res = toolsManager.executeAsync(ToolAction("typeText", mapOf("text" to routed.text, "submit" to routed.submit.toString())))
                    appendAssistantReply(res.message, speak = fromVoice)
                }
                is RoutedVoiceCommand.AnalyzeScreen -> {
                    val res = visionDelegate.describeScreen("Describe active screen")
                    appendAssistantReply(res.message, speak = fromVoice)
                }
                is RoutedVoiceCommand.AnalyzeScreenshot, is RoutedVoiceCommand.ExtractOcr -> {
                    val res = visionDelegate.readTextFromScreenshot(true)
                    appendAssistantReply(res.message, speak = fromVoice)
                }
                is RoutedVoiceCommand.OpenGalleryAnalysis -> appendAssistantReply("Open the Lens tab and tap Pick Photo to inspect any gallery image.", speak = fromVoice)
                is RoutedVoiceCommand.CompareGalleryImages -> appendAssistantReply("Open Lens tab to compare or analyze photos.", speak = fromVoice)
                is RoutedVoiceCommand.PerformScreenTask -> {
                    val res = visionDelegate.performScreenTask(routed.goal)
                    appendAssistantReply(res.message, speak = fromVoice)
                }
                is RoutedVoiceCommand.DeviceControl -> {
                    val segs = smartphoneControl.splitChainedCommands(routed.rawPhrase)
                    val out = smartphoneControl.executeChainedCommands(segs) { seg ->
                        smartphoneControl.handleDirectControlPhrase(seg) ?: deviceControl.launchAppByName(seg)
                    }
                    refreshSystemTelemetry()
                    appendAssistantReply(out.message, speak = fromVoice)
                }
                is RoutedVoiceCommand.PlaceCall -> {
                    val out = callManager.resolveAndPlaceCall(routed.target)
                    appendAssistantReply(out.message, speak = fromVoice)
                }
                else -> {
                    runAiConversation(clean, speak = fromVoice)
                }
            }
        }
    }

    private suspend fun runAiConversation(prompt: String, speak: Boolean) {
        _orbState.value = OrbVisualState.THINKING
        _statusBanner.value = "Thinking…"
        val p = prefsFlow.value
        val result = agentCoordinator.withLlmConcurrencyGuard("orchestrator", "chat_reply") {
            aiEngine.generateReply(
                keyStore = keyStore,
                mood = _selectedMood.value,
                language = p.language,
                history = chatMessages.value.takeLast(8).map { ProviderChatMessage(it.role, it.text) },
                userMessage = prompt,
                memoryStore = memoryStore,
                memoryConsentGranted = p.memoryConsentGranted,
                modelResolver = { prov -> modelRegistry.bestModelFor(prov, ModelCapability.CHAT) }
            )
        }
        result.fold(
            onSuccess = { resp ->
                appendAssistantReply(resp.text, resp.providerUsed?.displayName.orEmpty(), resp.modelUsed, speak)
            },
            onFailure = { err ->
                val offlineMsg = "AI cloud unavailable (${err.localizedMessage}). You can still run offline device controls (battery, volume, storage, apps) or add a working API key in APIs tab."
                appendAssistantReply(offlineMsg, speak = speak)
            }
        )
    }

    private suspend fun appendAssistantReply(text: String, provider: String = "", model: String = "", speak: Boolean = false) {
        repository.saveChatMessage(
            ChatMessageEntity(
                id = UUID.randomUUID().toString(),
                sessionId = "default",
                role = "assistant",
                text = text,
                providerUsed = provider,
                modelUsed = model,
                timestampMs = System.currentTimeMillis()
            )
        )
        _liveSubtitle.value = "Mahi: $text"
        _orbState.value = if (speak) OrbVisualState.SPEAKING else OrbVisualState.IDLE
        _statusBanner.value = if (model.isNotBlank()) "Replied via $provider ($model)" else "Ready"
        if (speak) {
            syncTtsSettingsFromPrefs()
            speechSynthesizer.speak(text, prefsFlow.value.language)
        }
    }

    fun selectMood(mood: MahiMood) { _selectedMood.value = mood }
    fun setPrefilledDial(number: String) { _prefilledDial.value = number }

    fun testProviderConnection(provider: AiProvider) {
        viewModelScope.launch {
            _isTestingApi.value = true
            val slot = apiState.value.activeSlot(provider)
            val key = keyStore.decryptSlotKey(provider)
            if (slot == null || key.isNullOrBlank()) {
                keyStore.recordTestResult(provider, "", false, "Add an API key for ${provider.displayName} first.")
                _isTestingApi.value = false
                return@launch
            }
            val model = modelRegistry.bestModelFor(provider, ModelCapability.CHAT)
            val res = providerClient.testConnection(provider, key, model, slot.baseUrl)
            res.fold(
                onSuccess = { probe ->
                    keyStore.recordTestResult(provider, slot.id, true, probe)
                    modelRegistry.discoverProviderModels(provider, key)
                },
                onFailure = { err ->
                    keyStore.recordTestResult(provider, slot.id, false, err.localizedMessage ?: "Connection failed")
                }
            )
            _isTestingApi.value = false
        }
    }

    fun refreshDiscoveredModels() {
        viewModelScope.launch { modelRegistry.refreshIfStale(force = true) }
    }

    fun runAutomationGoal(goal: String) {
        viewModelScope.launch {
            if (!prefsFlow.value.autopilotEnabled) {
                _screenAiAnalysis.value = "Enable 'Allow Screen Autopilot' in Settings first."
                return@launch
            }
            _isControllingScreen.value = true
            _orbState.value = OrbVisualState.CONTROLLING
            agentEngine.buildHeuristicPlan(goal)
            val res = agentEngine.runScreenTask(goal)
            _screenAiAnalysis.value = res.summary
            _isControllingScreen.value = false
            _orbState.value = OrbVisualState.IDLE
        }
    }

    fun stopAllAutomation() {
        agentEngine.requestEmergencyStop()
        _isControllingScreen.value = false
        _orbState.value = OrbVisualState.IDLE
        MahiVoiceForegroundService.clearAutopilotNotification(appCtx)
    }

    private suspend fun requestUserConfirmation(cat: SensitiveCategory, title: String, detail: String): Boolean {
        val def = CompletableDeferred<Boolean>()
        confirmationDeferred = def
        _pendingConfirmation.value = PendingConfirmationRequest(category = cat, title = title, detail = detail)
        return def.await()
    }

    fun respondConfirmation(approved: Boolean) {
        _pendingConfirmation.value = null
        confirmationDeferred?.complete(approved)
        confirmationDeferred = null
    }

    fun inspectCurrentScreen() {
        viewModelScope.launch {
            val res = visionDelegate.captureScreenImage("Explain what is on the screen right now and list actionable items.")
            _screenAiAnalysis.value = res.message
        }
    }

    fun analyzePhotoUri(uri: Uri) {
        viewModelScope.launch {
            val bytes = cameraController.loadJpegBytesFromUri(uri) ?: run {
                _visionError.value = "Unable to decode selected image."
                return@launch
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { neuralLensAnalyzer.analyzeBitmapSample(it) }
            vqaManager.rememberImage(bytes)
            askAiVision("Describe this photo and extract any readable text.")
        }
    }

    fun askAiVision(question: String) {
        viewModelScope.launch {
            _isAnalyzingVision.value = true
            _visionError.value = null
            val res = if (vqaManager.lastImageBytes != null) {
                vqaManager.askAboutLastImage(question, prefsFlow.value.language)
            } else {
                MahiAccessibilityService.takeScreenshotJpeg().fold(
                    onSuccess = { bytes ->
                        vqaManager.rememberImage(bytes)
                        screenshotVision.analyzeScreenshotWithAi(bytes, question, prefsFlow.value.language)
                    },
                    onFailure = { Result.failure(it) }
                )
            }
            res.fold(
                onSuccess = { _aiVisionAnswer.value = it },
                onFailure = { _visionError.value = it.localizedMessage ?: "Vision error" }
            )
            _isAnalyzingVision.value = false
        }
    }

    fun refreshSystemTelemetry() {
        _batteryInfo.value = smartphoneControl.batteryManager.getBatteryInfo()
        _storageInfo.value = smartphoneControl.storageManager.getStorageInfo()
        _networkInfo.value = smartphoneControl.networkManager.getNetworkInfo()
        _deviceInfo.value = smartphoneControl.deviceInfoManager.getDeviceInfo()
        _volumeLevels.value = smartphoneControl.volumeManager.getAllVolumeLevels()
        _canWriteSettings.value = deviceControl.canModifySystemSettings()
        _offlineInspection.value = researchEngine.inspectOfflineLlmReadiness()
    }

    fun refreshPermissionsAudit() {
        _permissions.value = permissionAuditManager.auditAll()
        callManager.refreshCapabilities()
    }

    fun toggleFlashlight() {
        val next = !_isFlashlightOn.value
        val out = smartphoneControl.quickSettingsManager.setTorch(next)
        if (out.success) _isFlashlightOn.value = next
        _lastControlStatus.value = out.message
    }

    fun setVolumeStream(stream: VolumeStreamType, pct: Int) {
        _lastControlStatus.value = smartphoneControl.volumeManager.setStreamPercent(stream, pct).message
        _volumeLevels.value = smartphoneControl.volumeManager.getAllVolumeLevels()
    }

    fun setBrightness(pct: Int) {
        _brightnessPercent.value = pct
        _lastControlStatus.value = deviceControl.setBrightnessPercent(pct).message
    }

    fun updateCallPrefs(mutator: (CallAutomationPrefs) -> CallAutomationPrefs) = callManager.updatePrefs(mutator)

    fun runWebResearch(q: String) {
        viewModelScope.launch {
            _researchStatus.value = "Searching Wikipedia & DuckDuckGo…"
            researchEngine.searchWebAndWikipedia(q).fold(
                onSuccess = { _webResults.value = it; _researchStatus.value = "Found ${it.size} verified summary source(s)." },
                onFailure = { _researchStatus.value = it.localizedMessage ?: "Search failed" }
            )
        }
    }

    fun runGitHubSearch(q: String) {
        viewModelScope.launch {
            researchEngine.searchGitHubRepos(q).fold(
                onSuccess = { _gitHubRepos.value = it; _researchStatus.value = "Loaded ${it.size} GitHub repositories." },
                onFailure = { _researchStatus.value = it.localizedMessage ?: "GitHub failed" }
            )
        }
    }

    fun runHuggingFaceSearch(q: String) {
        viewModelScope.launch {
            researchEngine.searchHuggingFaceModels(q).fold(
                onSuccess = { _hfModels.value = it; _researchStatus.value = "Evaluated ${it.size} Hugging Face models." },
                onFailure = { _researchStatus.value = it.localizedMessage ?: "HF search failed" }
            )
        }
    }

    fun runNearbySearch(q: String) {
        viewModelScope.launch {
            researchEngine.searchNearbyPlaces(q).fold(
                onSuccess = { _nearbyPlaces.value = it; _researchStatus.value = "Found ${it.size} nearby places." },
                onFailure = { _researchStatus.value = it.localizedMessage ?: "Nearby lookup failed" }
            )
        }
    }

    fun saveKnowledgeNote(title: String, body: String) {
        viewModelScope.launch {
            researchEngine.saveKnowledgeNote(title, "manual", body)
            _researchStatus.value = "Knowledge note saved to Room DB."
        }
    }

    fun deleteKnowledgeNote(id: String) {
        viewModelScope.launch { repository.deleteKnowledgeNote(id) }
    }

    fun exportKnowledgeBackup() {
        val file = researchEngine.exportKnowledgeBackupJson(knowledgeNotes.value)
        _researchStatus.value = "Exported backup JSON to ${file.name}"
    }

    fun approveSandboxPatch(proposalId: String) {
        viewModelScope.launch {
            selfCodingPipeline.approveAndRecordRelease(proposalId, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
                .fold(
                    onSuccess = { _researchStatus.value = "Recorded release checksum ${it.sha256Checksum.take(12)}…" },
                    onFailure = { _researchStatus.value = it.localizedMessage ?: "Blocked" }
                )
        }
    }

    fun downloadWakeModel() {
        viewModelScope.launch {
            wakeModelManager.downloadAndInstallModel()
        }
    }

    override fun onCleared() {
        super.onCleared()
        speechSynthesizer.shutdown()
        speechRecognizer.stopListening()
        geminiLiveClient.disconnect()
        cameraController.release()
    }
}
