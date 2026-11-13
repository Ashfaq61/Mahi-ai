package com.example.viewmodel

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.GeminiLiveCallbacks
import com.example.ai.GeminiLiveClient
import com.example.ai.GeminiLiveConnectionPhase
import com.example.ai.GeminiLiveError
import com.example.ai.MahiAiEngine
import com.example.ai.MahiMood
import com.example.api.ApiErrorCategory
import com.example.api.AiProvider
import com.example.api.ApiKeyEntry
import com.example.api.ApiManagerState
import com.example.api.ConnectionStatus
import com.example.api.ConnectionTestResult
import com.example.api.ProviderApiException
import com.example.api.ProviderClient
import com.example.api.SecureApiKeyStore
import com.example.api.isGeminiLiveAudioModel
import com.example.audio.AudioRecordManager
import com.example.audio.MahiSpeechSynthesizer
import com.example.audio.MahiVoiceForegroundService
import com.example.audio.MicCaptureState
import com.example.audio.SpeechRecognitionManager
import com.example.automation.AgentActionType
import com.example.automation.AgentPlanStep
import com.example.automation.AppLaunchResult
import com.example.automation.AppLauncherManager
import com.example.automation.AutomationHistoryEntry
import com.example.automation.AutonomousAgentEngine
import com.example.automation.CapturedNotification
import com.example.automation.CommandExecutionGuard
import com.example.automation.DeviceControlHelper
import com.example.automation.GestureActionRecord
import com.example.automation.InstalledAppInfo
import com.example.automation.MahiAccessibilityService
import com.example.automation.MahiNotificationService
import com.example.automation.PendingSensitiveConfirmation
import com.example.automation.RoutedVoiceCommand
import com.example.automation.ScreenSnapshot
import com.example.automation.SensitiveCategory
import com.example.automation.VoiceCommandRouter
import com.example.camera.CameraLensFacing
import com.example.camera.CameraStudioState
import com.example.camera.MahiCameraController
import com.example.calls.ActiveCallState
import com.example.calls.AiCallAssistantPhase
import com.example.calls.CallAutomationPrefs
import com.example.calls.CallAutomationRecord
import com.example.calls.CallAutomationRepository
import com.example.calls.CallRuleContact
import com.example.calls.MahiInCallService
import com.example.calls.TelecomCapabilityStatus
import com.example.preferences.AppLanguage
import com.example.preferences.AppThemeMode
import com.example.preferences.MahiPreferencesStore
import com.example.preferences.MahiUserPrefs
import com.example.research.AiSearchResult
import com.example.research.DeepResearchReport
import com.example.research.ExportedPdfResult
import com.example.research.ResearchEngine
import com.example.research.ResearchPdfExporter
import com.example.tools.MahiToolsManager
import com.example.tools.ToolAction
import com.example.tools.ToolExecutionResult
import com.example.vision.LiveVisionSnapshot
import com.example.vision.NeuralLensAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AssistantVisualState {
    DISCONNECTED,
    CONNECTING,
    LISTENING,
    THINKING,
    SPEAKING,
    RECONNECTING,
    ERROR
}

data class AssistantUiState(
    val visualState: AssistantVisualState = AssistantVisualState.DISCONNECTED,
    val currentMood: MahiMood = MahiMood.SASSY,
    val liveUserText: String = "",
    val liveAssistantText: String = "Hey there! Tap the orb or say something to talk to me. Don't be shy!",
    val waveformBars: List<Float> = List(32) { 0.06f },
    val micLevel: Float = 0f,
    val isMicActivelyCapturing: Boolean = false,
    val lastExecutedTool: ToolExecutionResult? = null,
    val isContinuousMode: Boolean = true,
    val isMuted: Boolean = false,
    val voicePitch: Float = 1.20f,
    val voiceSpeed: Float = 1.08f,
    val showSubtitles: Boolean = true,
    val errorMessage: String? = null,
    val errorCategory: ApiErrorCategory? = null,
    val reconnectMessage: String? = null,
    val backgroundMicNotice: String? = null,
    val isGeminiLiveActive: Boolean = false
)

class MahiAssistantViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "MahiViewModel"
    }

    private val providerClient = ProviderClient()
    val secureKeyStore = SecureApiKeyStore(application)
    private val geminiLiveClient = GeminiLiveClient()
    val preferencesStore = MahiPreferencesStore(application)

    private val audioRecordManager = AudioRecordManager()
    private val speechRecognitionManager = SpeechRecognitionManager(application)
    private val speechSynthesizer = MahiSpeechSynthesizer(application)
    val cameraController = MahiCameraController(application)
    val cameraState: StateFlow<CameraStudioState> = cameraController.state

    private val aiEngine = MahiAiEngine(providerClient)
    val appLauncherManager = AppLauncherManager(application)
    val neuralLensAnalyzer = NeuralLensAnalyzer(providerClient)
    private val researchEngine = ResearchEngine(providerClient = providerClient)
    val deviceControlHelper = DeviceControlHelper(application)
    val autonomousAgentEngine = AutonomousAgentEngine(
        context = application,
        deviceControl = deviceControlHelper,
        providerClient = providerClient
    )
    private val commandExecutionGuard = CommandExecutionGuard(dedupWindowMs = 2_500L)

    private val toolsManager = MahiToolsManager(
        context = application,
        cameraController = cameraController,
        onNavigateToCamera = { _requestedTab.value = "CAMERA_STUDIO" },
        onRunAiSearchSummary = { query -> runAiSearch(query) },
        hasAudioPermission = {
            ContextCompat.checkSelfPermission(
                getApplication(),
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        }
    )

    private var pendingTranscriptJob: Job? = null
    private val liveTranscriptBuffer = StringBuilder()
    private var pendingLivePromptAfterConnect: String? = null

    private val _uiState = MutableStateFlow(AssistantUiState())
    val uiState: StateFlow<AssistantUiState> = _uiState.asStateFlow()

    val apiManagerState: StateFlow<ApiManagerState> = secureKeyStore.state
    val userPrefs: StateFlow<MahiUserPrefs> = preferencesStore.prefsFlow

    // Navigation deep-link support from MahiDialerActivity
    private val _requestedTab = MutableStateFlow<String?>(null)
    val requestedTab: StateFlow<String?> = _requestedTab.asStateFlow()
    private val _pendingDialNumber = MutableStateFlow("")
    val pendingDialNumber: StateFlow<String> = _pendingDialNumber.asStateFlow()

    // Installed searchable apps cache
    private val _installedApps = MutableStateFlow<List<InstalledAppInfo>>(emptyList())
    val installedApps: StateFlow<List<InstalledAppInfo>> = _installedApps.asStateFlow()

    // Neural Lens state
    val liveVisionSnapshot: StateFlow<LiveVisionSnapshot> = neuralLensAnalyzer.liveSnapshot
    private val _visionAnswer = MutableStateFlow("")
    val visionAnswer: StateFlow<String> = _visionAnswer.asStateFlow()
    private val _isVisionLoading = MutableStateFlow(false)
    val isVisionLoading: StateFlow<Boolean> = _isVisionLoading.asStateFlow()
    private val _visionError = MutableStateFlow<String?>(null)
    val visionError: StateFlow<String?> = _visionError.asStateFlow()

    // Search & Deep Research state
    private val _isResearchLoading = MutableStateFlow(false)
    val isResearchLoading: StateFlow<Boolean> = _isResearchLoading.asStateFlow()
    private val _researchProgress = MutableStateFlow("")
    val researchProgress: StateFlow<String> = _researchProgress.asStateFlow()
    private val _aiSearchResult = MutableStateFlow<AiSearchResult?>(null)
    val aiSearchResult: StateFlow<AiSearchResult?> = _aiSearchResult.asStateFlow()
    private val _deepResearchReport = MutableStateFlow<DeepResearchReport?>(null)
    val deepResearchReport: StateFlow<DeepResearchReport?> = _deepResearchReport.asStateFlow()
    private val _exportedPdf = MutableStateFlow<ExportedPdfResult?>(null)
    val exportedPdf: StateFlow<ExportedPdfResult?> = _exportedPdf.asStateFlow()

    // Screen Intelligence, Advanced Gestures, Notifications & Autonomous Agent state
    val screenSnapshot: StateFlow<ScreenSnapshot> = MahiAccessibilityService.screenSnapshot
    val gestureStatus: StateFlow<String> = MahiAccessibilityService.gestureStatus
    val isContinuousScrolling: StateFlow<Boolean> = MahiAccessibilityService.isContinuousScrolling
    val gestureHistory: StateFlow<List<GestureActionRecord>> = MahiAccessibilityService.gestureHistory
    val activeNotifications: StateFlow<List<CapturedNotification>> = MahiNotificationService.notifications
    val agentPlanSteps: StateFlow<List<AgentPlanStep>> = autonomousAgentEngine.planSteps
    val isAgentRunning: StateFlow<Boolean> = autonomousAgentEngine.isRunning
    val pendingSensitiveConfirmation: StateFlow<PendingSensitiveConfirmation?> = autonomousAgentEngine.pendingConfirmation
    val automationHistory: StateFlow<List<AutomationHistoryEntry>> = autonomousAgentEngine.historyStore.history

    private val _screenAiAnalysis = MutableStateFlow("")
    val screenAiAnalysis: StateFlow<String> = _screenAiAnalysis.asStateFlow()

    // Call Automation & AI Call Assistant state
    val callPrefs: StateFlow<CallAutomationPrefs> = CallAutomationRepository.prefs
    val activeCall: StateFlow<ActiveCallState> = CallAutomationRepository.activeCall
    val callHistory: StateFlow<List<CallAutomationRecord>> = CallAutomationRepository.callHistory
    val callCapabilities: StateFlow<TelecomCapabilityStatus> = CallAutomationRepository.capabilities
    val importantCallAlert: StateFlow<String?> = CallAutomationRepository.importantCallAlert

    private val _callFeedback = MutableStateFlow<String?>(null)
    val callFeedback: StateFlow<String?> = _callFeedback.asStateFlow()

    private var collectJob: Job? = null

    private val vibrator: Vibrator? by lazy {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = application.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                application.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (_: Exception) {
            null
        }
    }

    init {
        CallAutomationRepository.init(application)
        speechSynthesizer.initialize()
        val initialPrefs = preferencesStore.prefsFlow.value
        aiEngine.currentLanguage = initialPrefs.language
        speechSynthesizer.setSpeechLanguage(initialPrefs.language.ttsLanguageTag)
        speechRecognitionManager.preferredLanguageTag = initialPrefs.language.ttsLanguageTag

        MahiVoiceForegroundService.onStopRequestedFromNotification = {
            stopSession()
        }

        // Barge-in for standard turn-based providers
        speechRecognitionManager.onSpeechInterrupted = {
            if (_uiState.value.visualState == AssistantVisualState.SPEAKING && !_uiState.value.isGeminiLiveActive) {
                speechSynthesizer.stop()
                _uiState.value = _uiState.value.copy(
                    visualState = AssistantVisualState.LISTENING,
                    liveAssistantText = ""
                )
            }
        }

        observeStreams()
        refreshInstalledApps()
    }

    fun handleIncomingAppIntent(intent: Intent?) {
        if (intent == null) return
        val tab = intent.getStringExtra("open_tab")
        val dialNumber = intent.getStringExtra("dial_number")
        if (!dialNumber.isNullOrBlank()) {
            _pendingDialNumber.value = dialNumber
        }
        if (!tab.isNullOrBlank()) {
            _requestedTab.value = tab
        }
    }

    fun consumeRequestedTab() {
        _requestedTab.value = null
    }

    fun clearPendingDialNumber() {
        _pendingDialNumber.value = ""
    }

    private fun shouldUseGeminiLiveAudio(): Boolean {
        val state = apiManagerState.value
        val activeConfig = state.activeConfig
        return state.activeProvider == AiProvider.GEMINI && isGeminiLiveAudioModel(activeConfig.selectedModel)
    }

    private fun observeStreams() {
        collectJob = viewModelScope.launch {
            launch {
                speechRecognitionManager.partialText.collectLatest { text ->
                    if (text.isNotBlank() && !_uiState.value.isGeminiLiveActive) {
                        _uiState.value = _uiState.value.copy(
                            liveUserText = text,
                            visualState = AssistantVisualState.LISTENING
                        )
                    }
                }
            }
            launch {
                speechRecognitionManager.finalText.collectLatest { query ->
                    if (query.isNotBlank() && !_uiState.value.isGeminiLiveActive) {
                        processUserQuery(query)
                    }
                }
            }
            launch {
                audioRecordManager.captureState.collectLatest { cap ->
                    val activelyCapturing = cap == MicCaptureState.CAPTURING
                    val errorMsg = if (cap == MicCaptureState.PERMISSION_DENIED || cap == MicCaptureState.HARDWARE_ERROR) {
                        audioRecordManager.lastCaptureError.value
                    } else null
                    _uiState.value = _uiState.value.copy(
                        isMicActivelyCapturing = activelyCapturing,
                        errorMessage = errorMsg ?: _uiState.value.errorMessage
                    )
                }
            }
            launch {
                audioRecordManager.amplitudeFlow.collectLatest { amp ->
                    _uiState.value = _uiState.value.copy(micLevel = amp)
                }
            }
            launch {
                audioRecordManager.waveformBarsFlow.collectLatest { micBars ->
                    if (_uiState.value.visualState == AssistantVisualState.LISTENING ||
                        _uiState.value.visualState == AssistantVisualState.RECONNECTING
                    ) {
                        _uiState.value = _uiState.value.copy(waveformBars = micBars)
                    }
                }
            }
            launch {
                speechSynthesizer.speakingWaveform.collectLatest { ttsBars ->
                    if (_uiState.value.visualState == AssistantVisualState.SPEAKING && !_uiState.value.isGeminiLiveActive) {
                        _uiState.value = _uiState.value.copy(waveformBars = ttsBars)
                    }
                }
            }
            launch {
                geminiLiveClient.speakingWaveform.collectLatest { liveBars ->
                    if (_uiState.value.visualState == AssistantVisualState.SPEAKING && _uiState.value.isGeminiLiveActive) {
                        _uiState.value = _uiState.value.copy(waveformBars = liveBars)
                    }
                }
            }
            launch {
                MahiVoiceForegroundService.foregroundRestrictionNotice.collectLatest { notice ->
                    _uiState.value = _uiState.value.copy(backgroundMicNotice = notice)
                }
            }
        }
    }

    fun toggleSession() {
        when (_uiState.value.visualState) {
            AssistantVisualState.DISCONNECTED,
            AssistantVisualState.ERROR -> startSession()
            AssistantVisualState.SPEAKING -> interruptAssistant()
            AssistantVisualState.LISTENING,
            AssistantVisualState.THINKING,
            AssistantVisualState.CONNECTING,
            AssistantVisualState.RECONNECTING -> stopSession()
        }
    }

    fun startPushToTalkHold() {
        if (!_uiState.value.isGeminiLiveActive) {
            startSession()
        } else {
            audioRecordManager.isInputMuted = false
            _uiState.value = _uiState.value.copy(isMuted = false, visualState = AssistantVisualState.LISTENING)
        }
    }

    fun releasePushToTalkHold() {
        if (userPrefs.value.pushToTalkMode) {
            if (_uiState.value.isGeminiLiveActive) {
                audioRecordManager.isInputMuted = true
            } else {
                speechRecognitionManager.stopListening()
                audioRecordManager.stopListening()
            }
        }
    }

    fun startSession() {
        triggerHaptic()
        val activeProvider = apiManagerState.value.activeProvider
        val activeModel = apiManagerState.value.activeConfig.selectedModel
        val apiKey = secureKeyStore.getActiveDecryptedKey(activeProvider)

        if (apiKey.isNullOrBlank()) {
            _uiState.value = _uiState.value.copy(
                visualState = AssistantVisualState.ERROR,
                isGeminiLiveActive = false,
                isMicActivelyCapturing = false,
                errorCategory = ApiErrorCategory.MISSING_KEY,
                errorMessage = "${activeProvider.displayName} API key missing. Open API Keys & Providers to add a key."
            )
            return
        }

        // Start foreground microphone service cleanly so voice stays alive across TikTok/Facebook/YouTube
        MahiVoiceForegroundService.start(getApplication())

        _uiState.value = _uiState.value.copy(
            visualState = AssistantVisualState.CONNECTING,
            liveUserText = "",
            errorMessage = null,
            errorCategory = null,
            reconnectMessage = null
        )

        if (shouldUseGeminiLiveAudio()) {
            startGeminiLiveSession(apiKey, activeModel)
        } else {
            startTurnBasedVoiceSession()
        }
    }

    private fun startGeminiLiveSession(apiKey: String, model: String) {
        val systemPrompt = aiEngine.buildSystemPrompt(_uiState.value.currentMood, userPrefs.value.language)
        geminiLiveClient.setMuted(_uiState.value.isMuted)
        audioRecordManager.isInputMuted = _uiState.value.isMuted

        // Start persistent PCM16 @ 16kHz microphone capture FIRST so initial setup frames queue safely
        audioRecordManager.onPcmAudioFrame = { pcmChunk ->
            geminiLiveClient.sendPcmAudioChunk(pcmChunk)
        }
        val micStarted = audioRecordManager.startListening()
        if (!micStarted) {
            MahiVoiceForegroundService.stop(getApplication())
            _uiState.value = _uiState.value.copy(
                visualState = AssistantVisualState.ERROR,
                isGeminiLiveActive = false,
                isMicActivelyCapturing = false,
                errorCategory = ApiErrorCategory.CONNECTION_FAILED,
                errorMessage = audioRecordManager.lastCaptureError.value
                    ?: "Microphone unavailable. Check Android microphone permission."
            )
            return
        }

        geminiLiveClient.connect(
            apiKey = apiKey,
            model = model,
            systemPrompt = systemPrompt,
            callbacks = object : GeminiLiveCallbacks {
                override fun onSetupComplete() {
                    _uiState.value = _uiState.value.copy(
                        visualState = AssistantVisualState.LISTENING,
                        isGeminiLiveActive = true,
                        isMicActivelyCapturing = audioRecordManager.isActivelyCapturing(),
                        reconnectMessage = null,
                        errorMessage = null,
                        liveAssistantText = "Gemini Live audio active ($model). Continuous listening ON!"
                    )
                    val queued = pendingLivePromptAfterConnect
                    if (!queued.isNullOrBlank()) {
                        pendingLivePromptAfterConnect = null
                        _uiState.value = _uiState.value.copy(liveUserText = queued)
                        geminiLiveClient.sendTextPromptOverAudioSession(queued)
                    }
                }

                override fun onUserTranscript(text: String) {
                    val trimmedChunk = text.trim()
                    if (trimmedChunk.isEmpty()) return

                    // Immediate STOP / recording stop executes with 0ms latency
                    val immediateCheck = VoiceCommandRouter.parseCommand(trimmedChunk)
                    if (immediateCheck is RoutedVoiceCommand.StopEverything ||
                        immediateCheck is RoutedVoiceCommand.StopVideoRecording
                    ) {
                        pendingTranscriptJob?.cancel()
                        liveTranscriptBuffer.clear()
                        viewModelScope.launch {
                            executeRoutedVoiceCommand(immediateCheck, originalUtterance = trimmedChunk)
                        }
                        return
                    }

                    // Accumulate streaming transcription chunks safely without prematurely firing partials
                    val accumulated = synchronized(liveTranscriptBuffer) {
                        val existing = liveTranscriptBuffer.toString().trim()
                        val updated = when {
                            existing.isEmpty() -> trimmedChunk
                            trimmedChunk.startsWith(existing, ignoreCase = true) -> trimmedChunk
                            existing.endsWith(trimmedChunk, ignoreCase = true) -> existing
                            else -> "$existing $trimmedChunk"
                        }.takeLast(220)
                        liveTranscriptBuffer.clear()
                        liveTranscriptBuffer.append(updated)
                        updated
                    }

                    _uiState.value = _uiState.value.copy(liveUserText = accumulated)
                    if (userPrefs.value.autoLanguageDetection) {
                        val detected = VoiceCommandRouter.detectLanguageFromUtterance(accumulated, userPrefs.value.language)
                        if (detected != userPrefs.value.language) {
                            setLanguage(detected)
                        }
                    }

                    pendingTranscriptJob?.cancel()
                    pendingTranscriptJob = viewModelScope.launch {
                        // Wait briefly so partial speech ("Mahi, open...") settles cleanly into complete command
                        delay(550L)
                        val settledUtterance = synchronized(liveTranscriptBuffer) {
                            liveTranscriptBuffer.toString().trim()
                        }
                        if (settledUtterance.isBlank() || VoiceCommandRouter.isLikelyIncompletePartial(settledUtterance)) {
                            return@launch
                        }
                        val routed = VoiceCommandRouter.parseCommand(settledUtterance)
                        if (routed != null && commandExecutionGuard.shouldExecute(routed.deduplicationKey())) {
                            synchronized(liveTranscriptBuffer) { liveTranscriptBuffer.clear() }
                            executeRoutedVoiceCommand(routed, originalUtterance = settledUtterance)
                        } else if (settledUtterance.length > 60) {
                            synchronized(liveTranscriptBuffer) { liveTranscriptBuffer.clear() }
                        }
                    }
                }

                override fun onAssistantTranscript(text: String) {
                    _uiState.value = _uiState.value.copy(liveAssistantText = text)
                }

                override fun onAssistantSpeakingChanged(isSpeaking: Boolean) {
                    val current = _uiState.value.visualState
                    if (current == AssistantVisualState.DISCONNECTED || current == AssistantVisualState.ERROR) return

                    if (!isSpeaking && !_uiState.value.isContinuousMode && geminiLiveClient.connectionPhase.value != GeminiLiveConnectionPhase.RECONNECTING) {
                        // Continuous mode explicitly OFF -> stop listening after reply finishes
                        stopSession()
                        return
                    }

                    _uiState.value = _uiState.value.copy(
                        visualState = if (isSpeaking) AssistantVisualState.SPEAKING else AssistantVisualState.LISTENING
                    )
                }

                override fun onToolCall(id: String, action: ToolAction) {
                    viewModelScope.launch {
                        val dedupKey = toolActionDeduplicationKey(action)
                        val result = if (dedupKey != null && !commandExecutionGuard.shouldExecute(dedupKey)) {
                            ToolExecutionResult(
                                actionName = action.name,
                                displayTitle = action.name,
                                message = "Command already executed cleanly.",
                                isSuccess = true
                            )
                        } else {
                            synchronized(liveTranscriptBuffer) { liveTranscriptBuffer.clear() }
                            pendingTranscriptJob?.cancel()
                            toolsManager.execute(action)
                        }
                        _uiState.value = _uiState.value.copy(lastExecutedTool = result)
                        if (action.name.equals("changeMood", ignoreCase = true)) {
                            val moodName = action.params["mood"]
                            MahiMood.entries.firstOrNull { it.name.equals(moodName, ignoreCase = true) }?.let {
                                _uiState.value = _uiState.value.copy(currentMood = it)
                            }
                        }
                        geminiLiveClient.sendToolResponse(
                            callId = id,
                            name = action.name,
                            resultMessage = result.message,
                            success = result.isSuccess
                        )
                    }
                }

                override fun onInterrupted() {
                    _uiState.value = _uiState.value.copy(visualState = AssistantVisualState.LISTENING)
                }

                override fun onReconnecting(attempt: Int, maxAttempts: Int, reason: String) {
                    _uiState.value = _uiState.value.copy(
                        visualState = AssistantVisualState.RECONNECTING,
                        reconnectMessage = "Reconnecting Live session ($attempt/$maxAttempts)...",
                        errorMessage = null
                    )
                }

                override fun onError(error: GeminiLiveError) {
                    // STRICT RULE: Never silently fall back from Gemini Live audio-to-audio to text-only mode!
                    audioRecordManager.onPcmAudioFrame = null
                    audioRecordManager.stopListening()
                    MahiVoiceForegroundService.stop(getApplication())
                    _uiState.value = _uiState.value.copy(
                        visualState = AssistantVisualState.ERROR,
                        isGeminiLiveActive = false,
                        isMicActivelyCapturing = false,
                        reconnectMessage = null,
                        errorCategory = error.category,
                        errorMessage = error.message,
                        waveformBars = List(32) { 0.06f }
                    )
                }

                override fun onDisconnected() {
                    audioRecordManager.onPcmAudioFrame = null
                    audioRecordManager.stopListening()
                    MahiVoiceForegroundService.stop(getApplication())
                    _uiState.value = _uiState.value.copy(
                        visualState = AssistantVisualState.DISCONNECTED,
                        isGeminiLiveActive = false,
                        isMicActivelyCapturing = false,
                        reconnectMessage = null,
                        waveformBars = List(32) { 0.06f }
                    )
                }
            }
        )
    }

    private fun startTurnBasedVoiceSession() {
        viewModelScope.launch {
            try {
                audioRecordManager.onPcmAudioFrame = null
                val micOk = audioRecordManager.startListening()
                speechRecognitionManager.isContinuousMode = _uiState.value.isContinuousMode && !userPrefs.value.pushToTalkMode
                val sttOk = speechRecognitionManager.startListening()
                if (!micOk && !sttOk) {
                    MahiVoiceForegroundService.stop(getApplication())
                    _uiState.value = _uiState.value.copy(
                        visualState = AssistantVisualState.ERROR,
                        isMicActivelyCapturing = false,
                        errorCategory = ApiErrorCategory.CONNECTION_FAILED,
                        errorMessage = "Microphone and speech recognition unavailable on this device."
                    )
                    return@launch
                }
                _uiState.value = _uiState.value.copy(
                    visualState = AssistantVisualState.LISTENING,
                    isGeminiLiveActive = false,
                    isMicActivelyCapturing = micOk || sttOk
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start voice session", e)
                MahiVoiceForegroundService.stop(getApplication())
                _uiState.value = _uiState.value.copy(
                    visualState = AssistantVisualState.ERROR,
                    isMicActivelyCapturing = false,
                    errorCategory = ApiErrorCategory.CONNECTION_FAILED,
                    errorMessage = "Microphone or speech recognition service unavailable."
                )
            }
        }
    }

    fun stopSession() {
        triggerHaptic()
        audioRecordManager.onPcmAudioFrame = null
        audioRecordManager.stopListening()
        speechRecognitionManager.stopListening()
        speechSynthesizer.stop()
        geminiLiveClient.disconnect()
        MahiVoiceForegroundService.stop(getApplication())
        _uiState.value = _uiState.value.copy(
            visualState = AssistantVisualState.DISCONNECTED,
            isGeminiLiveActive = false,
            isMicActivelyCapturing = false,
            reconnectMessage = null,
            waveformBars = List(32) { 0.06f }
        )
    }

    fun interruptAssistant() {
        triggerHaptic()
        if (_uiState.value.isGeminiLiveActive) {
            geminiLiveClient.interruptPlayback()
            _uiState.value = _uiState.value.copy(visualState = AssistantVisualState.LISTENING)
        } else {
            speechSynthesizer.stop()
            _uiState.value = _uiState.value.copy(
                visualState = AssistantVisualState.LISTENING,
                liveUserText = "Listening..."
            )
            speechRecognitionManager.startListening()
            audioRecordManager.startListening()
        }
    }

    fun processUserQuery(query: String) {
        if (query.isBlank()) return
        triggerHaptic()

        // Auto-detect Urdu / Roman Urdu / English if enabled
        if (userPrefs.value.autoLanguageDetection) {
            val detected = VoiceCommandRouter.detectLanguageFromUtterance(query, userPrefs.value.language)
            if (detected != userPrefs.value.language) {
                setLanguage(detected)
            }
        }

        // Handle immediate deterministic Android automation commands (STOP, apps, Google search, camera, gestures, calls)
        val routed = VoiceCommandRouter.parseCommand(query)
        if (routed != null) {
            if (commandExecutionGuard.shouldExecute(routed.deduplicationKey())) {
                viewModelScope.launch {
                    executeRoutedVoiceCommand(routed, originalUtterance = query)
                }
            }
            return
        }

        val state = apiManagerState.value
        val activeProvider = state.activeProvider
        val activeConfig = state.activeConfig
        val apiKey = secureKeyStore.getActiveDecryptedKey(activeProvider)

        // If Gemini Live audio-to-audio is already active, send inside the active audio session
        if (_uiState.value.isGeminiLiveActive && geminiLiveClient.isSessionActive()) {
            _uiState.value = _uiState.value.copy(liveUserText = query)
            geminiLiveClient.sendTextPromptOverAudioSession(query)
            return
        }

        // Strict requirement: Never silently substitute text-only mode if Live audio model is selected
        if (activeProvider == AiProvider.GEMINI && isGeminiLiveAudioModel(activeConfig.selectedModel)) {
            if (apiKey.isNullOrBlank()) {
                _uiState.value = _uiState.value.copy(
                    visualState = AssistantVisualState.ERROR,
                    errorCategory = ApiErrorCategory.MISSING_KEY,
                    errorMessage = "Google Gemini API key is required for Gemini Live audio mode."
                )
                return
            }
            pendingLivePromptAfterConnect = query
            startSession()
            return
        }

        if (apiKey.isNullOrBlank()) {
            _uiState.value = _uiState.value.copy(
                visualState = AssistantVisualState.ERROR,
                errorCategory = ApiErrorCategory.MISSING_KEY,
                errorMessage = "${activeProvider.displayName} API key is missing. Open API Keys & Providers to configure it."
            )
            return
        }

        _uiState.value = _uiState.value.copy(
            visualState = AssistantVisualState.THINKING,
            liveUserText = query,
            errorMessage = null,
            errorCategory = null
        )

        speechRecognitionManager.stopListening()
        audioRecordManager.stopListening()

        viewModelScope.launch {
            try {
                val mood = _uiState.value.currentMood
                val lang = userPrefs.value.language
                val response = aiEngine.sendMessageWithProvider(
                    provider = activeProvider,
                    apiKey = apiKey,
                    selectedModel = activeConfig.selectedModel,
                    baseUrl = activeConfig.customBaseUrl,
                    userMessage = query,
                    mood = mood,
                    language = lang
                )

                if (response.toolAction != null) {
                    val toolResult = toolsManager.execute(response.toolAction)
                    _uiState.value = _uiState.value.copy(lastExecutedTool = toolResult)
                }

                if (response.toolAction?.name.equals("changeMood", ignoreCase = true)) {
                    val targetMoodStr = response.toolAction?.params?.get("mood")
                    MahiMood.entries.firstOrNull { it.name.equals(targetMoodStr, ignoreCase = true) }?.let {
                        _uiState.value = _uiState.value.copy(currentMood = it)
                    }
                }

                _uiState.value = _uiState.value.copy(
                    visualState = AssistantVisualState.SPEAKING,
                    liveAssistantText = response.replyText
                )

                if (!_uiState.value.isMuted) {
                    speechSynthesizer.pitch = _uiState.value.voicePitch
                    speechSynthesizer.speechRate = _uiState.value.voiceSpeed
                    speechSynthesizer.speak(
                        text = response.replyText,
                        onStart = {
                            _uiState.value = _uiState.value.copy(visualState = AssistantVisualState.SPEAKING)
                        },
                        onDone = {
                            onAssistantDoneSpeaking()
                        }
                    )
                } else {
                    onAssistantDoneSpeaking()
                }
            } catch (e: ProviderApiException) {
                Log.e(TAG, "Provider API error: ${e.category}")
                _uiState.value = _uiState.value.copy(
                    visualState = AssistantVisualState.ERROR,
                    errorCategory = e.category,
                    errorMessage = e.message,
                    waveformBars = List(32) { 0.06f }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error in query processing", e)
                _uiState.value = _uiState.value.copy(
                    visualState = AssistantVisualState.ERROR,
                    errorCategory = ApiErrorCategory.UNKNOWN,
                    errorMessage = e.localizedMessage ?: "Failed to generate reply",
                    waveformBars = List(32) { 0.06f }
                )
            }
        }
    }

    private fun toolActionDeduplicationKey(action: ToolAction): String? {
        return when (action.name.lowercase().trim()) {
            "openapp" -> {
                val target = (action.params["appName"] ?: action.params["title"] ?: "").lowercase().trim()
                if (target == "google") "OPEN_GOOGLE" else "LAUNCH:$target:"
            }
            "opengoogle" -> "OPEN_GOOGLE"
            "googlesearch" -> "GOOGLE_SEARCH:${(action.params["query"] ?: "").lowercase().trim()}"
            "youtubesearch" -> "YT_SEARCH:${(action.params["query"] ?: "").lowercase().trim()}"
            "openwebsite" -> "OPEN_SITE:${(action.params["url"] ?: "").lowercase().trim()}"
            "opencamera" -> {
                val front = action.params["frontOrBack"]?.contains("front", true) == true
                "OPEN_CAM:${if (front) "FRONT" else "BACK"}"
            }
            "capturephoto" -> "CAPTURE_PHOTO:${action.params["isSelfie"]?.lowercase() == "true"}"
            "startvideorecording" -> "START_VIDEO"
            "stopvideorecording" -> "STOP_VIDEO"
            else -> null
        }
    }

    private suspend fun executeRoutedVoiceCommand(
        routed: RoutedVoiceCommand,
        originalUtterance: String = ""
    ) {
        val outcomeText: String
        val cardTitle: String
        var cardUrl: String? = null
        var ok = true

        when (routed) {
            RoutedVoiceCommand.StopEverything -> {
                MahiAccessibilityService.stopAllGestures()
                autonomousAgentEngine.stop()
                if (cameraController.state.value.isRecordingVideo) {
                    cameraController.stopVideoRecording()
                }
                cardTitle = "Stopped Immediately"
                outcomeText = "Stopped all gestures and running automation."
            }
            is RoutedVoiceCommand.LaunchApp -> {
                val res = appLauncherManager.launchApp(routed.appName, openStoreOrWebIfMissing = true)
                ok = res.success
                cardTitle = res.appName
                cardUrl = res.fallbackUrl
                outcomeText = res.message
                if (ok && routed.followUpGesture != null) {
                    delay(1100L)
                    MahiAccessibilityService.executeGestureIntent(routed.followUpGesture)
                }
            }
            RoutedVoiceCommand.OpenGoogle -> {
                val res = appLauncherManager.openGoogleApp()
                ok = res.success
                cardTitle = res.appName
                cardUrl = res.fallbackUrl
                outcomeText = res.message
            }
            is RoutedVoiceCommand.GoogleSearch -> {
                val res = appLauncherManager.openGoogleSearch(routed.query)
                ok = res.success
                cardTitle = "Google: ${routed.query}"
                cardUrl = res.fallbackUrl
                outcomeText = res.message
                runAiSearch(routed.query)
            }
            is RoutedVoiceCommand.SearchYouTube -> {
                val res = appLauncherManager.openYouTubeSearch(routed.query)
                ok = res.success
                cardTitle = "YouTube: ${routed.query}"
                cardUrl = res.fallbackUrl
                outcomeText = res.message
            }
            is RoutedVoiceCommand.OpenWebsite -> {
                val res = appLauncherManager.openWebsite(routed.url)
                ok = res.success
                cardTitle = "Website"
                cardUrl = res.fallbackUrl
                outcomeText = res.message
            }
            is RoutedVoiceCommand.OpenCamera -> {
                outcomeText = cameraController.prepareOpenCamera(routed.lens)
                cardTitle = "Camera (${routed.lens.name})"
                _requestedTab.value = "CAMERA_STUDIO"
            }
            is RoutedVoiceCommand.SwitchCamera -> {
                outcomeText = cameraController.requestSwitchCamera(routed.lens)
                cardTitle = "Switch Camera"
                _requestedTab.value = "CAMERA_STUDIO"
            }
            is RoutedVoiceCommand.CapturePhoto -> {
                outcomeText = cameraController.requestPhotoOrSelfie(
                    isSelfie = routed.isSelfie,
                    countdownSeconds = if (routed.isSelfie) 3 else 0
                )
                cardTitle = if (routed.isSelfie) "Selfie (3s)" else "Capture Photo"
                _requestedTab.value = "CAMERA_STUDIO"
            }
            RoutedVoiceCommand.StartVideoRecording -> {
                val hasAudio = ContextCompat.checkSelfPermission(
                    getApplication(),
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                outcomeText = cameraController.requestStartVideoRecording(hasAudio)
                cardTitle = "Video Recording"
                _requestedTab.value = "CAMERA_STUDIO"
            }
            RoutedVoiceCommand.StopVideoRecording -> {
                outcomeText = cameraController.stopVideoRecording()
                cardTitle = "Video Saved"
            }
            is RoutedVoiceCommand.SetCameraFlash -> {
                outcomeText = cameraController.setFlashEnabled(routed.enabled)
                cardTitle = if (routed.enabled) "Flash ON" else "Flash OFF"
            }
            is RoutedVoiceCommand.SetCameraZoom -> {
                outcomeText = cameraController.adjustZoomBy(routed.deltaStep)
                cardTitle = "Camera Zoom"
            }
            is RoutedVoiceCommand.ExecuteGesture -> {
                val gOut = MahiAccessibilityService.executeGestureIntent(routed.gesture)
                ok = gOut.success
                cardTitle = routed.gesture.actionName.replace('_', ' ')
                outcomeText = gOut.message
            }
            is RoutedVoiceCommand.PlaceCall -> {
                val goal = if (originalUtterance.isNotBlank()) originalUtterance else "Call ${routed.target}"
                val step = AgentPlanStep(
                    stepNumber = 1,
                    title = "Place call to ${routed.target}",
                    actionType = AgentActionType.MAKE_CALL,
                    param = routed.target,
                    sensitiveCategory = SensitiveCategory.CALL
                )
                cardTitle = "Call Confirmation"
                outcomeText = "Review call approval in Agent / Calls"
                _requestedTab.value = "AGENT"
                autonomousAgentEngine.executePlan(goal, listOf(step), viewModelScope)
            }
            RoutedVoiceCommand.AnswerActiveCall -> {
                ok = MahiInCallService.answerIncomingCall(useSpeakerphoneBridge = false)
                cardTitle = "Answer Call"
                outcomeText = if (ok) "Answered incoming call" else "No active ringing call or Default Dialer role required."
            }
            RoutedVoiceCommand.RejectActiveCall -> {
                ok = MahiInCallService.rejectOrEndCall()
                cardTitle = "Reject Call"
                outcomeText = if (ok) "Rejected incoming call" else "No active call or Telecom role required."
            }
            RoutedVoiceCommand.EndActiveCall -> {
                ok = MahiInCallService.rejectOrEndCall()
                cardTitle = "End Call"
                outcomeText = if (ok) "Ended active call" else "No active call found."
            }
        }

        _uiState.value = _uiState.value.copy(
            liveUserText = originalUtterance.ifBlank { _uiState.value.liveUserText },
            liveAssistantText = outcomeText,
            lastExecutedTool = ToolExecutionResult(
                actionName = cardTitle,
                displayTitle = cardTitle,
                message = outcomeText,
                linkUrl = cardUrl,
                isSuccess = ok
            )
        )
        if (!_uiState.value.isMuted && !_uiState.value.isGeminiLiveActive) {
            speakText(outcomeText)
        }
    }

    private fun onAssistantDoneSpeaking() {
        if (_uiState.value.isContinuousMode && !userPrefs.value.pushToTalkMode && !_uiState.value.isGeminiLiveActive) {
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(
                    visualState = AssistantVisualState.LISTENING,
                    liveUserText = ""
                )
                audioRecordManager.startListening()
                speechRecognitionManager.startListening()
            }
        } else if (!_uiState.value.isGeminiLiveActive) {
            _uiState.value = _uiState.value.copy(
                visualState = AssistantVisualState.DISCONNECTED,
                isMicActivelyCapturing = false,
                waveformBars = List(32) { 0.06f }
            )
        }
    }

    // --- API Key & Provider Management Operations ---

    fun addAndOptionallyTestKey(
        provider: AiProvider,
        rawApiKey: String,
        label: String,
        testImmediately: Boolean = true
    ) {
        val trimmed = rawApiKey.trim()
        if (trimmed.isEmpty()) return
        val entry = secureKeyStore.addKey(provider, trimmed, label, selectAsActiveKey = true)
        if (_uiState.value.errorCategory == ApiErrorCategory.MISSING_KEY) {
            _uiState.value = _uiState.value.copy(errorMessage = null, errorCategory = null, visualState = AssistantVisualState.DISCONNECTED)
        }
        if (testImmediately) {
            testApiKeyConnection(provider, entry.id)
        }
    }

    fun updateApiKey(
        provider: AiProvider,
        keyId: String,
        newRawApiKey: String?,
        newLabel: String,
        testAfterUpdate: Boolean = false
    ) {
        secureKeyStore.updateKey(provider, keyId, newRawApiKey, newLabel)
        if (testAfterUpdate) {
            testApiKeyConnection(provider, keyId)
        }
    }

    fun deleteApiKey(provider: AiProvider, keyId: String) {
        secureKeyStore.deleteKey(provider, keyId)
    }

    fun selectApiKey(provider: AiProvider, keyId: String) {
        secureKeyStore.selectKey(provider, keyId)
    }

    fun revealDecryptedKey(keyId: String): String? {
        return secureKeyStore.getDecryptedKey(keyId)
    }

    fun setActiveProvider(provider: AiProvider) {
        if (_uiState.value.visualState != AssistantVisualState.DISCONNECTED) {
            stopSession()
        }
        secureKeyStore.setActiveProvider(provider)
        _uiState.value = _uiState.value.copy(
            errorMessage = null,
            errorCategory = null,
            visualState = AssistantVisualState.DISCONNECTED
        )
    }

    fun setProviderModel(provider: AiProvider, model: String) {
        secureKeyStore.setSelectedModel(provider, model)
    }

    fun setProviderBaseUrl(provider: AiProvider, baseUrl: String) {
        secureKeyStore.setCustomBaseUrl(provider, baseUrl)
    }

    fun testApiKeyConnection(provider: AiProvider, keyId: String) {
        val rawKey = secureKeyStore.getDecryptedKey(keyId)
        if (rawKey.isNullOrBlank()) {
            secureKeyStore.updateKeyStatus(
                provider = provider,
                keyId = keyId,
                status = ConnectionStatus.ERROR,
                lastError = "Unable to decrypt stored API key."
            )
            return
        }

        val cfg = apiManagerState.value.getConfig(provider)
        secureKeyStore.updateKeyStatus(provider, keyId, ConnectionStatus.TESTING)

        viewModelScope.launch {
            val result: ConnectionTestResult = providerClient.testConnection(
                provider = provider,
                apiKey = rawKey,
                selectedModel = cfg.selectedModel,
                baseUrl = cfg.customBaseUrl
            )

            if (result.discoveredModels.isNotEmpty()) {
                secureKeyStore.updateAvailableModels(provider, result.discoveredModels)
            }

            secureKeyStore.updateKeyStatus(
                provider = provider,
                keyId = keyId,
                status = result.status,
                latencyMs = result.latencyMs,
                usageInfo = result.usageInfo,
                lastError = result.errorMessage
            )
        }
    }

    fun testActiveKeyForProvider(provider: AiProvider): Boolean {
        val keyEntry: ApiKeyEntry = apiManagerState.value.getSelectedKey(provider) ?: return false
        testApiKeyConnection(provider, keyEntry.id)
        return true
    }

    // --- Persona & Voice Controls ---

    fun setMood(mood: MahiMood) {
        triggerHaptic()
        _uiState.value = _uiState.value.copy(currentMood = mood)
        aiEngine.currentMood = mood

        val moodIntro = when (mood) {
            MahiMood.SASSY -> "Sassy mode activated. Prepare yourself!"
            MahiMood.FLIRTY -> "Flirty mode on. Careful, you might fall for me."
            MahiMood.PLAYFUL -> "Playful mode engaged! Let's have some fun."
            MahiMood.SAVAGE -> "Savage mode unlocked. Don't say I didn't warn you!"
            MahiMood.SWEET -> "Sweet mode on. I'm all ears, sweetie."
        }
        if (apiManagerState.value.activeKey != null && !shouldUseGeminiLiveAudio()) {
            processUserQuery(moodIntro)
        } else {
            _uiState.value = _uiState.value.copy(liveAssistantText = moodIntro)
        }
    }

    fun toggleContinuousMode() {
        val next = !_uiState.value.isContinuousMode
        speechRecognitionManager.isContinuousMode = next && !userPrefs.value.pushToTalkMode
        _uiState.value = _uiState.value.copy(isContinuousMode = next)
    }

    fun setPushToTalkMode(enabled: Boolean) {
        preferencesStore.setPushToTalkMode(enabled)
        speechRecognitionManager.isContinuousMode = _uiState.value.isContinuousMode && !enabled
    }

    fun setAutoLanguageDetection(enabled: Boolean) {
        preferencesStore.setAutoLanguageDetection(enabled)
    }

    fun toggleMute() {
        val next = !_uiState.value.isMuted
        if (next) speechSynthesizer.stop()
        geminiLiveClient.setMuted(next)
        audioRecordManager.isInputMuted = next
        _uiState.value = _uiState.value.copy(isMuted = next)
    }

    fun toggleSubtitles() {
        _uiState.value = _uiState.value.copy(showSubtitles = !_uiState.value.showSubtitles)
    }

    fun setVoicePitch(pitch: Float) {
        _uiState.value = _uiState.value.copy(voicePitch = pitch)
        speechSynthesizer.pitch = pitch
    }

    fun setVoiceSpeed(speed: Float) {
        _uiState.value = _uiState.value.copy(voiceSpeed = speed)
        speechSynthesizer.speechRate = speed
    }

    fun dismissToolCard() {
        _uiState.value = _uiState.value.copy(lastExecutedTool = null)
    }

    // --- Language, Theme & Speech Helpers ---

    fun setLanguage(language: AppLanguage) {
        preferencesStore.setLanguage(language)
        aiEngine.currentLanguage = language
        speechSynthesizer.setSpeechLanguage(language.ttsLanguageTag)
        speechRecognitionManager.preferredLanguageTag = language.ttsLanguageTag
        geminiLiveClient.updateSystemInstruction(aiEngine.buildSystemPrompt(_uiState.value.currentMood, language))
    }

    fun setThemeMode(themeMode: AppThemeMode) {
        preferencesStore.setThemeMode(themeMode)
    }

    fun speakText(text: String) {
        if (text.isBlank() || _uiState.value.isMuted) return
        speechSynthesizer.setSpeechLanguage(userPrefs.value.language.ttsLanguageTag)
        speechSynthesizer.pitch = _uiState.value.voicePitch
        speechSynthesizer.speechRate = _uiState.value.voiceSpeed
        speechSynthesizer.speak(text)
    }

    // --- Neural Lens ---

    fun askVisionQuestion(question: String) {
        val state = apiManagerState.value
        val provider = state.activeProvider
        val cfg = state.activeConfig
        val apiKey = secureKeyStore.getActiveDecryptedKey(provider)
        if (apiKey.isNullOrBlank()) {
            _visionError.value = "${provider.displayName} API key missing. Add a key for Neural Lens AI analysis."
            _visionAnswer.value = neuralLensAnalyzer.formatOfflineMlKitSummary(userPrefs.value.language)
            return
        }

        _isVisionLoading.value = true
        _visionError.value = null
        viewModelScope.launch {
            try {
                val answer = neuralLensAnalyzer.askAiAboutVision(
                    provider = provider,
                    apiKey = apiKey,
                    model = cfg.selectedModel,
                    baseUrl = cfg.customBaseUrl,
                    language = userPrefs.value.language,
                    question = question
                )
                _visionAnswer.value = answer
            } catch (e: Exception) {
                _visionError.value = e.localizedMessage ?: "Vision AI analysis failed."
            } finally {
                _isVisionLoading.value = false
            }
        }
    }

    fun analyzeVisionPhotoUri(uri: Uri) {
        _isVisionLoading.value = true
        _visionError.value = null
        viewModelScope.launch {
            val snap = neuralLensAnalyzer.loadAndAnalyzeUri(getApplication(), uri)
            if (snap == null) {
                _visionError.value = "Could not decode selected photo."
                _isVisionLoading.value = false
                return@launch
            }
            val apiKey = secureKeyStore.getActiveDecryptedKey(apiManagerState.value.activeProvider)
            if (apiKey.isNullOrBlank()) {
                _visionAnswer.value = neuralLensAnalyzer.formatOfflineMlKitSummary(userPrefs.value.language)
                _isVisionLoading.value = false
            } else {
                askVisionQuestion("Identify objects, devices, and readable text in this image.")
            }
        }
    }

    // --- AI Search & Deep Research ---

    fun runAiSearch(query: String) {
        if (query.isBlank()) return
        val state = apiManagerState.value
        val provider = state.activeProvider
        val cfg = state.activeConfig
        val apiKey = secureKeyStore.getActiveDecryptedKey(provider)

        _isResearchLoading.value = true
        _researchProgress.value = "Searching live web sources..."
        viewModelScope.launch {
            try {
                val result = researchEngine.runAiSearch(
                    query = query,
                    language = userPrefs.value.language,
                    provider = provider,
                    apiKey = apiKey,
                    model = cfg.selectedModel,
                    baseUrl = cfg.customBaseUrl
                )
                _aiSearchResult.value = result
            } finally {
                _isResearchLoading.value = false
                _researchProgress.value = ""
            }
        }
    }

    fun runDeepResearch(topic: String) {
        if (topic.isBlank()) return
        val state = apiManagerState.value
        val provider = state.activeProvider
        val cfg = state.activeConfig
        val apiKey = secureKeyStore.getActiveDecryptedKey(provider)

        _isResearchLoading.value = true
        _exportedPdf.value = null
        _researchProgress.value = "Planning multi-step research..."
        viewModelScope.launch {
            try {
                val report = researchEngine.runDeepResearch(
                    topic = topic,
                    language = userPrefs.value.language,
                    provider = provider,
                    apiKey = apiKey,
                    model = cfg.selectedModel,
                    baseUrl = cfg.customBaseUrl,
                    onProgress = { status -> _researchProgress.value = status }
                )
                _deepResearchReport.value = report
            } finally {
                _isResearchLoading.value = false
                _researchProgress.value = ""
            }
        }
    }

    fun exportResearchPdf(report: DeepResearchReport) {
        viewModelScope.launch {
            val exported = withContext(Dispatchers.IO) {
                ResearchPdfExporter.exportReportToPdf(getApplication(), report)
            }
            _exportedPdf.value = exported
        }
    }

    // --- Screen Intelligence, App Launcher & Autonomous Android Agent ---

    fun refreshInstalledApps(searchQuery: String = "") {
        viewModelScope.launch(Dispatchers.IO) {
            val apps = appLauncherManager.getInstalledLaunchableApps(searchQuery)
            _installedApps.value = apps
        }
    }

    fun launchAppDirect(appNameOrPackage: String): AppLaunchResult {
        val result = appLauncherManager.launchApp(appNameOrPackage, openStoreOrWebIfMissing = true)
        _uiState.value = _uiState.value.copy(
            lastExecutedTool = ToolExecutionResult(
                actionName = "openApp",
                displayTitle = result.appName,
                message = result.message,
                linkUrl = result.fallbackUrl,
                isSuccess = result.success
            )
        )
        return result
    }

    fun openGoogleAppDirect(): AppLaunchResult {
        val result = appLauncherManager.openGoogleApp()
        _uiState.value = _uiState.value.copy(
            lastExecutedTool = ToolExecutionResult(
                actionName = "openGoogle",
                displayTitle = result.appName,
                message = result.message,
                linkUrl = result.fallbackUrl,
                isSuccess = result.success
            )
        )
        return result
    }

    // --- Camera Studio Controls ---

    fun openCameraMode(lens: CameraLensFacing = CameraLensFacing.BACK) {
        cameraController.prepareOpenCamera(lens)
        _requestedTab.value = "CAMERA_STUDIO"
    }

    fun switchCameraLens(lens: CameraLensFacing) {
        val msg = cameraController.requestSwitchCamera(lens)
        _uiState.value = _uiState.value.copy(
            lastExecutedTool = ToolExecutionResult("switchCamera", "Camera Lens", msg, isSuccess = true)
        )
    }

    fun capturePhotoOrSelfie(isSelfie: Boolean) {
        val msg = cameraController.requestPhotoOrSelfie(isSelfie = isSelfie, countdownSeconds = if (isSelfie) 3 else 0)
        _requestedTab.value = "CAMERA_STUDIO"
        _uiState.value = _uiState.value.copy(
            lastExecutedTool = ToolExecutionResult("capturePhoto", if (isSelfie) "Selfie (3s)" else "Photo", msg, isSuccess = true)
        )
    }

    fun toggleVideoRecording() {
        val hasAudio = ContextCompat.checkSelfPermission(
            getApplication(),
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        val msg = if (cameraController.state.value.isRecordingVideo) {
            cameraController.stopVideoRecording()
        } else {
            _requestedTab.value = "CAMERA_STUDIO"
            cameraController.requestStartVideoRecording(hasAudio)
        }
        _uiState.value = _uiState.value.copy(
            lastExecutedTool = ToolExecutionResult("videoRecording", "Video Studio", msg, isSuccess = true)
        )
    }

    fun toggleCameraFlash() {
        val next = !cameraController.state.value.isFlashEnabled
        cameraController.setFlashEnabled(next)
    }

    fun adjustCameraZoom(delta: Float) {
        cameraController.adjustZoomBy(delta)
    }

    fun launchSystemCameraFallback(mode: String) {
        cameraController.launchSystemCameraFallback(mode)
    }

    fun runAgentGoal(goal: String) {
        if (goal.isBlank()) return
        val state = apiManagerState.value
        val provider = state.activeProvider
        val cfg = state.activeConfig
        val apiKey = secureKeyStore.getActiveDecryptedKey(provider)

        viewModelScope.launch {
            val steps = autonomousAgentEngine.createPlanForGoal(
                goal = goal,
                screenSnapshot = screenSnapshot.value,
                language = userPrefs.value.language,
                provider = provider,
                apiKey = apiKey,
                model = cfg.selectedModel,
                baseUrl = cfg.customBaseUrl
            )
            autonomousAgentEngine.executePlan(goal, steps, viewModelScope)
        }
    }

    fun stopAgent() {
        autonomousAgentEngine.stop()
        MahiAccessibilityService.stopAllGestures()
    }

    fun stopAllGestures() {
        MahiAccessibilityService.stopAllGestures()
    }

    fun respondToSensitiveConfirmation(approved: Boolean) {
        autonomousAgentEngine.respondToSensitiveConfirmation(approved)
    }

    fun sendAuthorizedWhatsAppReply(notificationKey: String, sender: String, replyText: String) {
        val goal = "Reply to WhatsApp message from $sender"
        val step = AgentPlanStep(
            stepNumber = 1,
            title = "Send WhatsApp reply to $sender: \"$replyText\"",
            actionType = AgentActionType.SEND_WHATSAPP_REPLY,
            param = notificationKey,
            secondaryParam = replyText,
            sensitiveCategory = SensitiveCategory.MESSAGE
        )
        viewModelScope.launch {
            autonomousAgentEngine.executePlan(goal, listOf(step), viewModelScope)
        }
    }

    fun analyzeCurrentScreenWithAi() {
        val snap = MahiAccessibilityService.refreshScreenSnapshot() ?: screenSnapshot.value
        if (snap.elements.isEmpty()) {
            _screenAiAnalysis.value = "No external screen content captured yet. Enable Mahi Accessibility Service first."
            return
        }
        val state = apiManagerState.value
        val provider = state.activeProvider
        val cfg = state.activeConfig
        val apiKey = secureKeyStore.getActiveDecryptedKey(provider)
        if (apiKey.isNullOrBlank()) {
            _screenAiAnalysis.value = snap.readableSummary
            return
        }

        _screenAiAnalysis.value = "Analyzing screen UI elements..."
        viewModelScope.launch {
            try {
                val analysis = providerClient.generateChatReply(
                    provider = provider,
                    apiKey = apiKey,
                    model = cfg.selectedModel,
                    baseUrl = cfg.customBaseUrl,
                    systemPrompt = "You are Mahi's Screen Intelligence analyzer. Summarize the currently open app, key buttons/menus, and suggested safe next actions concisely. ${userPrefs.value.language.instruction}",
                    history = emptyList(),
                    userMessage = snap.readableSummary
                )
                _screenAiAnalysis.value = analysis
            } catch (e: Exception) {
                _screenAiAnalysis.value = "${snap.readableSummary}\n(AI error: ${e.localizedMessage})"
            }
        }
    }

    fun clearAutomationHistory() {
        autonomousAgentEngine.historyStore.clear()
        MahiAccessibilityService.clearGestureHistory()
    }

    // --- Telecom Call Automation & AI Call Assistant ---

    fun refreshCallCapabilities() {
        CallAutomationRepository.refreshCapabilities(getApplication())
    }

    fun placeOutgoingCall(target: String) {
        val (ok, msg) = MahiInCallService.placeOutgoingCall(getApplication(), target)
        _callFeedback.value = if (ok) msg else "Call failed: $msg"
    }

    fun answerIncomingCall(useSpeakerphoneAi: Boolean = false) {
        val ok = MahiInCallService.answerIncomingCall(useSpeakerphoneBridge = useSpeakerphoneAi)
        if (ok && useSpeakerphoneAi) {
            val greeting = CallAutomationRepository.buildAiDisclosureGreeting(activeCall.value.callerDisplayName)
            CallAutomationRepository.updateActiveCall {
                it.copy(
                    isSpeakerphone = true,
                    aiPhase = AiCallAssistantPhase.DISCLOSING_AI_AND_ASKING_REASON,
                    aiDialogueLog = it.aiDialogueLog + "Mahi (Disclosed AI): $greeting"
                )
            }
            val voice = callPrefs.value.assistantVoice
            speechSynthesizer.pitch = voice.pitch
            speechSynthesizer.speechRate = voice.speed
            speechSynthesizer.speak(greeting)
        }
        _callFeedback.value = if (ok) {
            if (useSpeakerphoneAi) "Answered call on Speakerphone with disclosed AI Assistant." else "Call answered."
        } else {
            "No incoming call bound — grant Default Phone App role to answer in-app."
        }
    }

    fun rejectOrEndCall() {
        val ok = MahiInCallService.rejectOrEndCall()
        speechSynthesizer.stop()
        _callFeedback.value = if (ok) "Call ended/rejected." else "Grant Default Phone App role or Answer Calls permission."
    }

    fun toggleCallMute() {
        val next = !activeCall.value.isMuted
        val ok = MahiInCallService.setCallMuted(next)
        if (!ok) _callFeedback.value = "Default Phone App role required to mute cellular call."
    }

    fun toggleCallSpeakerphone() {
        val next = !activeCall.value.isSpeakerphone
        val ok = MahiInCallService.setSpeakerphoneEnabled(next)
        if (!ok) _callFeedback.value = "Default Phone App role required to toggle speakerphone."
    }

    fun takeOverCallManually() {
        speechSynthesizer.stop()
        geminiLiveClient.interruptPlayback()
        CallAutomationRepository.updateActiveCall {
            it.copy(
                aiPhase = AiCallAssistantPhase.MANUAL_TAKEOVER_ACTIVE,
                aiDialogueLog = it.aiDialogueLog + "[Manual Takeover Activated — AI Assistant silenced; owner in full control]"
            )
        }
        _callFeedback.value = "Manual takeover active — AI silenced."
    }

    fun processCallerUtteranceWithAiAssistant(callerStatement: String) {
        if (callerStatement.isBlank()) return
        val prefs = callPrefs.value
        val eval = CallAutomationRepository.evaluateCallerMessageSafetyAndUrgency(callerStatement, prefs)
        CallAutomationRepository.updateActiveCall {
            it.copy(
                isUrgent = it.isUrgent || eval.isUrgent,
                callerReasonSummary = callerStatement.take(120),
                aiPhase = if (eval.shouldSuggestTakeover) AiCallAssistantPhase.URGENCY_ALERT_HANDOVER else AiCallAssistantPhase.ACTIVE_SPEAKERPHONE_ASSIST,
                aiDialogueLog = it.aiDialogueLog + "Caller: $callerStatement" + "Mahi (AI): ${eval.replyText}"
            )
        }
        if (eval.isUrgent) {
            CallAutomationRepository.notifyImportantCall(
                getApplication(),
                "Important Call — Manual Takeover Ready",
                callerStatement
            )
        }
        val voice = prefs.assistantVoice
        speechSynthesizer.pitch = voice.pitch
        speechSynthesizer.speechRate = voice.speed
        speechSynthesizer.speak(eval.replyText)
        if (eval.shouldDisconnectSpam) {
            MahiInCallService.rejectOrEndCall()
        }
    }

    fun updateCallPrefs(transform: (CallAutomationPrefs) -> CallAutomationPrefs) {
        CallAutomationRepository.updatePrefs(getApplication(), transform)
    }

    fun addTrustedContact(name: String, phone: String) {
        if (phone.isBlank()) return
        updateCallPrefs { cur ->
            cur.copy(trustedContacts = cur.trustedContacts + CallRuleContact(name = name.ifBlank { phone }, phoneNumber = phone.trim()))
        }
    }

    fun removeTrustedContact(id: String) {
        updateCallPrefs { cur ->
            cur.copy(trustedContacts = cur.trustedContacts.filterNot { it.id == id })
        }
    }

    fun addBlockedCaller(name: String, phone: String, reason: String = "User rule") {
        if (phone.isBlank()) return
        updateCallPrefs { cur ->
            cur.copy(blockedCallers = cur.blockedCallers + CallRuleContact(name = name.ifBlank { phone }, phoneNumber = phone.trim(), note = reason))
        }
    }

    fun removeBlockedCaller(id: String) {
        updateCallPrefs { cur ->
            cur.copy(blockedCallers = cur.blockedCallers.filterNot { it.id == id })
        }
    }

    fun clearCallHistory() {
        CallAutomationRepository.clearHistory(getApplication())
    }

    fun dismissImportantCallAlert() {
        CallAutomationRepository.clearImportantAlert()
    }

    private fun triggerHaptic() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(25, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(25)
            }
        } catch (_: Exception) {
        }
    }

    override fun onCleared() {
        super.onCleared()
        pendingTranscriptJob?.cancel()
        cameraController.releaseCamera()
        audioRecordManager.onPcmAudioFrame = null
        audioRecordManager.stopListening()
        speechRecognitionManager.release()
        speechSynthesizer.release()
        geminiLiveClient.disconnect()
        MahiVoiceForegroundService.stop(getApplication())
        neuralLensAnalyzer.close()
        autonomousAgentEngine.stop()
        MahiAccessibilityService.stopAllGestures()
    }
}
