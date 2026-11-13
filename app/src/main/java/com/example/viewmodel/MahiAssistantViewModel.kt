package com.example.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.GeminiLiveCallbacks
import com.example.ai.GeminiLiveClient
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
import com.example.audio.SpeechRecognitionManager
import com.example.automation.AgentActionType
import com.example.automation.AgentPlanStep
import com.example.automation.AutomationHistoryEntry
import com.example.automation.AutonomousAgentEngine
import com.example.automation.CapturedNotification
import com.example.automation.DeviceControlHelper
import com.example.automation.MahiAccessibilityService
import com.example.automation.MahiNotificationService
import com.example.automation.PendingSensitiveConfirmation
import com.example.automation.ScreenSnapshot
import com.example.automation.SensitiveCategory
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
    ERROR
}

data class AssistantUiState(
    val visualState: AssistantVisualState = AssistantVisualState.DISCONNECTED,
    val currentMood: MahiMood = MahiMood.SASSY,
    val liveUserText: String = "",
    val liveAssistantText: String = "Hey there! Tap the orb or say something to talk to me. Don't be shy!",
    val waveformBars: List<Float> = List(32) { 0.06f },
    val lastExecutedTool: ToolExecutionResult? = null,
    val isContinuousMode: Boolean = true,
    val isMuted: Boolean = false,
    val voicePitch: Float = 1.20f,
    val voiceSpeed: Float = 1.08f,
    val showSubtitles: Boolean = true,
    val errorMessage: String? = null,
    val errorCategory: ApiErrorCategory? = null,
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
    private val aiEngine = MahiAiEngine(providerClient)
    private val toolsManager = MahiToolsManager(application)

    val neuralLensAnalyzer = NeuralLensAnalyzer(providerClient)
    private val researchEngine = ResearchEngine(providerClient = providerClient)
    val deviceControlHelper = DeviceControlHelper(application)
    val autonomousAgentEngine = AutonomousAgentEngine(
        context = application,
        deviceControl = deviceControlHelper,
        providerClient = providerClient
    )

    private val _uiState = MutableStateFlow(AssistantUiState())
    val uiState: StateFlow<AssistantUiState> = _uiState.asStateFlow()

    val apiManagerState: StateFlow<ApiManagerState> = secureKeyStore.state
    val userPrefs: StateFlow<MahiUserPrefs> = preferencesStore.prefsFlow

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

    // Screen Intelligence, Notifications & Autonomous Agent state
    val screenSnapshot: StateFlow<ScreenSnapshot> = MahiAccessibilityService.screenSnapshot
    val activeNotifications: StateFlow<List<CapturedNotification>> = MahiNotificationService.notifications
    val agentPlanSteps: StateFlow<List<AgentPlanStep>> = autonomousAgentEngine.planSteps
    val isAgentRunning: StateFlow<Boolean> = autonomousAgentEngine.isRunning
    val pendingSensitiveConfirmation: StateFlow<PendingSensitiveConfirmation?> = autonomousAgentEngine.pendingConfirmation
    val automationHistory: StateFlow<List<AutomationHistoryEntry>> = autonomousAgentEngine.historyStore.history

    private val _screenAiAnalysis = MutableStateFlow("")
    val screenAiAnalysis: StateFlow<String> = _screenAiAnalysis.asStateFlow()

    private var waveformCollectJob: Job? = null
    private var recognitionCollectJob: Job? = null

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
        speechSynthesizer.initialize()
        val initialLang = preferencesStore.prefsFlow.value.language
        aiEngine.currentLanguage = initialLang
        speechSynthesizer.setSpeechLanguage(initialLang.ttsLanguageTag)

        // Interrupt TTS playback when user speaks in standard turn-based mode
        speechRecognitionManager.onSpeechInterrupted = {
            if (_uiState.value.visualState == AssistantVisualState.SPEAKING && !_uiState.value.isGeminiLiveActive) {
                speechSynthesizer.stop()
                _uiState.value = _uiState.value.copy(
                    visualState = AssistantVisualState.LISTENING,
                    liveAssistantText = ""
                )
            }
        }

        observeSpeechRecognition()
        observeWaveformVisualizer()
    }

    private fun shouldUseGeminiLiveAudio(): Boolean {
        val state = apiManagerState.value
        val activeConfig = state.activeConfig
        return state.activeProvider == AiProvider.GEMINI && isGeminiLiveAudioModel(activeConfig.selectedModel)
    }

    private fun observeSpeechRecognition() {
        recognitionCollectJob = viewModelScope.launch {
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
        }
    }

    private fun observeWaveformVisualizer() {
        waveformCollectJob = viewModelScope.launch {
            launch {
                audioRecordManager.waveformBarsFlow.collectLatest { micBars ->
                    if (_uiState.value.visualState == AssistantVisualState.LISTENING) {
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
        }
    }

    fun toggleSession() {
        when (_uiState.value.visualState) {
            AssistantVisualState.DISCONNECTED,
            AssistantVisualState.ERROR -> startSession()
            AssistantVisualState.SPEAKING -> interruptAssistant()
            AssistantVisualState.LISTENING,
            AssistantVisualState.THINKING,
            AssistantVisualState.CONNECTING -> stopSession()
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
                errorCategory = ApiErrorCategory.MISSING_KEY,
                errorMessage = "${activeProvider.displayName} API key missing. Open API Keys & Providers to add a key."
            )
            return
        }

        _uiState.value = _uiState.value.copy(
            visualState = AssistantVisualState.CONNECTING,
            liveUserText = "",
            errorMessage = null,
            errorCategory = null
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

        geminiLiveClient.connect(
            apiKey = apiKey,
            model = model,
            systemPrompt = systemPrompt,
            callbacks = object : GeminiLiveCallbacks {
                override fun onSetupComplete() {
                    audioRecordManager.onPcmAudioFrame = { pcmChunk ->
                        geminiLiveClient.sendPcmAudioChunk(pcmChunk)
                    }
                    audioRecordManager.startListening()
                    _uiState.value = _uiState.value.copy(
                        visualState = AssistantVisualState.LISTENING,
                        isGeminiLiveActive = true,
                        liveAssistantText = "Gemini Live audio connected ($model). Talk to me!"
                    )
                }

                override fun onUserTranscript(text: String) {
                    _uiState.value = _uiState.value.copy(liveUserText = text)
                }

                override fun onAssistantTranscript(text: String) {
                    _uiState.value = _uiState.value.copy(liveAssistantText = text)
                }

                override fun onAssistantSpeakingChanged(isSpeaking: Boolean) {
                    val current = _uiState.value.visualState
                    if (current == AssistantVisualState.DISCONNECTED || current == AssistantVisualState.ERROR) return
                    _uiState.value = _uiState.value.copy(
                        visualState = if (isSpeaking) AssistantVisualState.SPEAKING else AssistantVisualState.LISTENING
                    )
                }

                override fun onToolCall(id: String, action: ToolAction) {
                    viewModelScope.launch {
                        val result = toolsManager.execute(action)
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

                override fun onError(error: GeminiLiveError) {
                    // STRICT RULE: Never silently fall back from Gemini Live audio-to-audio to text-only mode!
                    audioRecordManager.onPcmAudioFrame = null
                    audioRecordManager.stopListening()
                    _uiState.value = _uiState.value.copy(
                        visualState = AssistantVisualState.ERROR,
                        isGeminiLiveActive = false,
                        errorCategory = error.category,
                        errorMessage = error.message,
                        waveformBars = List(32) { 0.06f }
                    )
                }

                override fun onDisconnected() {
                    audioRecordManager.onPcmAudioFrame = null
                    audioRecordManager.stopListening()
                    _uiState.value = _uiState.value.copy(
                        visualState = AssistantVisualState.DISCONNECTED,
                        isGeminiLiveActive = false,
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
                audioRecordManager.startListening()
                speechRecognitionManager.startListening()
                _uiState.value = _uiState.value.copy(
                    visualState = AssistantVisualState.LISTENING,
                    isGeminiLiveActive = false
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start voice session", e)
                _uiState.value = _uiState.value.copy(
                    visualState = AssistantVisualState.ERROR,
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
        _uiState.value = _uiState.value.copy(
            visualState = AssistantVisualState.DISCONNECTED,
            isGeminiLiveActive = false,
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

        // Strict requirement: If Gemini Live mode is configured, never silently substitute a text-only mode
        if (activeProvider == AiProvider.GEMINI && isGeminiLiveAudioModel(activeConfig.selectedModel)) {
            if (apiKey.isNullOrBlank()) {
                _uiState.value = _uiState.value.copy(
                    visualState = AssistantVisualState.ERROR,
                    errorCategory = ApiErrorCategory.MISSING_KEY,
                    errorMessage = "Google Gemini API key is required for Gemini Live audio mode."
                )
                return
            }
            // Start live audio session and deliver prompt once setup completes
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

    private fun onAssistantDoneSpeaking() {
        if (_uiState.value.isContinuousMode && !_uiState.value.isGeminiLiveActive) {
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

    // --- Existing Persona & Voice Controls ---

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
        speechRecognitionManager.isContinuousMode = next
        _uiState.value = _uiState.value.copy(isContinuousMode = next)
    }

    fun toggleMute() {
        val next = !_uiState.value.isMuted
        if (next) speechSynthesizer.stop()
        geminiLiveClient.setMuted(next)
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

    // --- Neural Lens (Live AI Camera Vision & OCR) ---

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

    // --- AI Search Engine & Deep Research Agent + PDF Export ---

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

    // --- Screen Intelligence & Autonomous Android Agent ---

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
        audioRecordManager.onPcmAudioFrame = null
        audioRecordManager.stopListening()
        speechRecognitionManager.release()
        speechSynthesizer.release()
        geminiLiveClient.disconnect()
        neuralLensAnalyzer.close()
        autonomousAgentEngine.stop()
    }
}
