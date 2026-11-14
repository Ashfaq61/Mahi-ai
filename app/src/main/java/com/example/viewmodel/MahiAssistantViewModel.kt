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
import com.example.WakePopupActivity
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
import com.example.audio.MicTestSnapshot
import com.example.audio.SpeechRecognitionManager
import com.example.audio.WakeEngineStatus
import com.example.audio.WakeEnrollmentState
import com.example.audio.WakeEnrollmentStore
import com.example.audio.WakeModelManager
import com.example.audio.WakeModelState
import com.example.audio.WakeWordEngine
import com.example.automation.AgentActionType
import com.example.automation.AgentPlanStep
import com.example.automation.AppLaunchResult
import com.example.automation.AppLauncherManager
import com.example.automation.AutomationHistoryEntry
import com.example.automation.AutomationOutcome
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
import com.example.control.AllVolumeLevels
import com.example.control.BatteryInfoSnapshot
import com.example.control.DeviceInfoSnapshot
import com.example.control.NetworkInfoSnapshot
import com.example.control.SmartphoneControlManager
import com.example.control.StorageInfoSnapshot
import com.example.control.VolumeStreamType
import com.example.memory.ConversationHistoryStore
import com.example.memory.ConversationThread
import com.example.memory.UserMemoryProfile
import com.example.memory.UserMemoryStore
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
import com.example.tools.MahiVisionToolDelegate
import com.example.tools.ToolAction
import com.example.tools.ToolExecutionResult
import com.example.vision.CameraVisionManager
import com.example.vision.GalleryAnalysisManager
import com.example.vision.ImageComparisonManager
import com.example.vision.ImageComparisonResult
import com.example.vision.LiveVisionSnapshot
import com.example.vision.NeuralLensAnalyzer
import com.example.vision.NotificationUnderstandingManager
import com.example.vision.OCRManager
import com.example.vision.ObjectRecognitionManager
import com.example.vision.SceneRecognitionManager
import com.example.vision.ScreenVisionManager
import com.example.vision.ScreenshotVisionManager
import com.example.vision.VisionAiConfig
import com.example.vision.VisionBitmapUtils
import com.example.vision.VisualQaTurn
import com.example.vision.VisualQuestionAnswerManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

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

    private val toolsManager: MahiToolsManager by lazy {
        MahiToolsManager(
            context = application,
            cameraController = cameraController,
            onNavigateToCamera = { _requestedTab.value = "CAMERA_STUDIO" },
            onRunAiSearchSummary = { query -> runAiSearch(query) },
            hasAudioPermission = {
                ContextCompat.checkSelfPermission(
                    getApplication(),
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
            },
            visionDelegate = visionToolDelegate
        )
    }

    private val visionToolDelegate = object : MahiVisionToolDelegate {
        override suspend fun captureScreenImage(question: String): ToolExecutionResult {
            val captureRes = suspendCancellableCoroutine<Result<ByteArray>> { cont ->
                MahiAccessibilityService.captureScreenJpeg(maxDim = 1024, quality = 72) { res ->
                    if (cont.isActive) cont.resume(res)
                }
            }
            return captureRes.fold(
                onSuccess = { jpegBytes ->
                    if (_uiState.value.isGeminiLiveActive && geminiLiveClient.isSessionActive()) {
                        val sent = geminiLiveClient.sendImageFrame(jpegBytes)
                        ToolExecutionResult(
                            actionName = "captureScreenImage",
                            displayTitle = "Screen Image Sent",
                            message = if (sent) {
                                "Captured current screen image (${jpegBytes.size / 1024} KB) and sent it to Gemini Live. Describe what you see now."
                            } else {
                                "Captured screen image, but Gemini Live session is not connected."
                            },
                            isSuccess = sent
                        )
                    } else {
                        val analyzed = screenshotVisionManager.captureAndAnalyzeScreenshot(currentVisionAiConfig(), question)
                        analyzed.fold(
                            onSuccess = { answer ->
                                _visionAnswer.value = answer
                                screenshotVisionManager.lastScreenshot.value?.let { visualQuestionAnswerManager.setActiveImage(it) }
                                ToolExecutionResult("captureScreenImage", "Screen Image Analyzed", answer, isSuccess = true)
                            },
                            onFailure = { err ->
                                ToolExecutionResult("captureScreenImage", "Screen Image", err.localizedMessage ?: "Screenshot analysis failed", isSuccess = false)
                            }
                        )
                    }
                },
                onFailure = { err ->
                    if (!MahiAccessibilityService.isServiceEnabled(getApplication())) {
                        MahiAccessibilityService.openAccessibilitySettings(getApplication())
                    }
                    ToolExecutionResult(
                        actionName = "captureScreenImage",
                        displayTitle = "Screen Capture Unavailable",
                        message = err.localizedMessage ?: "Enable Mahi Accessibility Service (Android 11+) or pick an image from Gallery.",
                        isSuccess = false
                    )
                }
            )
        }

        override suspend fun describeScreen(question: String): ToolExecutionResult {
            val res = screenVisionManager.readAndExplainScreen(currentVisionAiConfig(), question)
            return res.fold(
                onSuccess = {
                    _screenAiAnalysis.value = it
                    _visionAnswer.value = it
                    ToolExecutionResult("describeScreen", "Screen Vision", it, isSuccess = true)
                },
                onFailure = {
                    ToolExecutionResult("describeScreen", "Screen Vision", it.localizedMessage ?: "Screen reading unavailable", isSuccess = false)
                }
            )
        }

        override suspend fun analyzeScreenshot(question: String): ToolExecutionResult {
            val res = screenshotVisionManager.captureAndAnalyzeScreenshot(currentVisionAiConfig(), question)
            return res.fold(
                onSuccess = {
                    _visionAnswer.value = it
                    screenshotVisionManager.lastScreenshot.value?.let { bmp -> visualQuestionAnswerManager.setActiveImage(bmp) }
                    ToolExecutionResult("analyzeScreenshot", "Screenshot Vision", it, isSuccess = true)
                },
                onFailure = {
                    ToolExecutionResult("analyzeScreenshot", "Screenshot Vision", it.localizedMessage ?: "Screenshot unavailable", isSuccess = false)
                }
            )
        }

        override suspend fun readTextFromCamera(explainWithAi: Boolean): ToolExecutionResult {
            val bmp = neuralLensAnalyzer.latestCapturedBitmap
            if (bmp == null) {
                _requestedTab.value = "LENS"
                return ToolExecutionResult("readTextFromCamera", "Camera OCR", "Opened Neural Lens—point camera at text to scan.", isSuccess = false)
            }
            val res = ocrManager.extractText(bmp, currentVisionAiConfig(), includeAiExplanation = explainWithAi)
            return res.fold(
                onSuccess = { out ->
                    _ocrExtractedText.value = out.extractedText
                    val msg = if (out.explanation.isNotBlank()) "${out.extractedText}\n\n${out.explanation}" else out.extractedText
                    _visionAnswer.value = msg
                    ToolExecutionResult("readTextFromCamera", "Camera OCR", msg, isSuccess = true)
                },
                onFailure = {
                    ToolExecutionResult("readTextFromCamera", "Camera OCR", it.localizedMessage ?: "OCR failed", isSuccess = false)
                }
            )
        }

        override suspend fun readTextFromScreenshot(explainWithAi: Boolean): ToolExecutionResult {
            val bmp = screenshotVisionManager.captureActiveScreenBitmap().getOrElse { err ->
                return ToolExecutionResult("readTextFromScreenshot", "Screenshot OCR", err.localizedMessage ?: "Screenshot unavailable", isSuccess = false)
            }
            visualQuestionAnswerManager.setActiveImage(bmp)
            val res = ocrManager.extractText(bmp, currentVisionAiConfig(), includeAiExplanation = explainWithAi)
            return res.fold(
                onSuccess = { out ->
                    _ocrExtractedText.value = out.extractedText
                    val msg = if (out.explanation.isNotBlank()) "${out.extractedText}\n\n${out.explanation}" else out.extractedText
                    _visionAnswer.value = msg
                    ToolExecutionResult("readTextFromScreenshot", "Screenshot OCR", msg, isSuccess = true)
                },
                onFailure = {
                    ToolExecutionResult("readTextFromScreenshot", "Screenshot OCR", it.localizedMessage ?: "Screenshot OCR failed", isSuccess = false)
                }
            )
        }

        override suspend fun identifyObjects(): ToolExecutionResult {
            val res = objectRecognitionManager.identifyObjects(currentVisionAiConfig())
            return res.fold(
                onSuccess = {
                    _visionAnswer.value = it
                    ToolExecutionResult("identifyObjects", "Object Recognition", it, isSuccess = true)
                },
                onFailure = {
                    _requestedTab.value = "LENS"
                    ToolExecutionResult("identifyObjects", "Object Recognition", it.localizedMessage ?: "Open Neural Lens to capture a frame.", isSuccess = false)
                }
            )
        }

        override suspend fun recognizeScene(): ToolExecutionResult {
            val res = sceneRecognitionManager.recognizeScene(currentVisionAiConfig())
            return res.fold(
                onSuccess = {
                    _visionAnswer.value = it
                    ToolExecutionResult("recognizeScene", "Scene Recognition", it, isSuccess = true)
                },
                onFailure = {
                    _requestedTab.value = "LENS"
                    ToolExecutionResult("recognizeScene", "Scene Recognition", it.localizedMessage ?: "Open Neural Lens to capture a frame.", isSuccess = false)
                }
            )
        }

        override suspend fun describeCameraView(question: String): ToolExecutionResult {
            val res = cameraVisionManager.describeCameraView(currentVisionAiConfig(), question)
            return res.fold(
                onSuccess = {
                    _visionAnswer.value = it
                    ToolExecutionResult("describeCameraView", "Camera Vision", it, isSuccess = true)
                },
                onFailure = {
                    _requestedTab.value = "LENS"
                    ToolExecutionResult("describeCameraView", "Camera Vision", it.localizedMessage ?: "Point Neural Lens camera first.", isSuccess = false)
                }
            )
        }

        override suspend fun summarizeNotifications(): ToolExecutionResult {
            val res = notificationUnderstandingManager.summarizeActiveNotifications(getApplication(), currentVisionAiConfig())
            return res.fold(
                onSuccess = {
                    _visionAnswer.value = it
                    ToolExecutionResult("summarizeNotifications", "Notification Digest", it, isSuccess = true)
                },
                onFailure = {
                    ToolExecutionResult("summarizeNotifications", "Notification Digest", it.localizedMessage ?: "Enable Notification Listener access.", isSuccess = false)
                }
            )
        }

        override suspend fun openGalleryAnalysis(): ToolExecutionResult {
            _requestedTab.value = "LENS"
            _pendingPhotoPickerMode.value = "SINGLE"
            return ToolExecutionResult("openGalleryAnalysis", "Gallery Photo Picker", "Opened Neural Lens Photo Picker to choose an image.", isSuccess = true)
        }

        override suspend fun compareImages(): ToolExecutionResult {
            _requestedTab.value = "LENS"
            _pendingPhotoPickerMode.value = "COMPARE"
            return ToolExecutionResult("compareImages", "Compare Two Images", "Opened Neural Lens Photo Picker to select 2 images to compare.", isSuccess = true)
        }

        override suspend fun askAboutLastImage(question: String): ToolExecutionResult {
            val res = visualQuestionAnswerManager.askFollowUp(question, currentVisionAiConfig())
            return res.fold(
                onSuccess = {
                    _visionAnswer.value = it
                    ToolExecutionResult("askAboutLastImage", "Visual Q&A", it, isSuccess = true)
                },
                onFailure = {
                    ToolExecutionResult("askAboutLastImage", "Visual Q&A", it.localizedMessage ?: "Capture or pick an image first.", isSuccess = false)
                }
            )
        }

        override suspend fun confirmSensitiveAction(title: String, reason: String): Boolean {
            return requestSensitiveConfirmation(title, reason)
        }
    }

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
    val isReadingScreen: StateFlow<Boolean> = MahiAccessibilityService.isReadingScreen
    val gestureStatus: StateFlow<String> = MahiAccessibilityService.gestureStatus
    val isContinuousScrolling: StateFlow<Boolean> = MahiAccessibilityService.isContinuousScrolling
    val gestureHistory: StateFlow<List<GestureActionRecord>> = MahiAccessibilityService.gestureHistory
    val activeNotifications: StateFlow<List<CapturedNotification>> = MahiNotificationService.notifications
    val agentPlanSteps: StateFlow<List<AgentPlanStep>> = autonomousAgentEngine.planSteps
    val isAgentRunning: StateFlow<Boolean> = autonomousAgentEngine.isRunning
    val pendingSensitiveConfirmation: StateFlow<PendingSensitiveConfirmation?> = autonomousAgentEngine.pendingConfirmation
    val automationHistory: StateFlow<List<AutomationHistoryEntry>> = autonomousAgentEngine.historyStore.history

    private var liveConfirmationDeferred: CompletableDeferred<Boolean>? = null
    private val _pendingLiveConfirmation = MutableStateFlow<Pair<String, String>?>(null)
    val pendingLiveConfirmation: StateFlow<Pair<String, String>?> = _pendingLiveConfirmation.asStateFlow()

    private val _pendingPhotoPickerMode = MutableStateFlow<String?>(null)
    val pendingPhotoPickerMode: StateFlow<String?> = _pendingPhotoPickerMode.asStateFlow()

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

    // Part 1: Wake Up Detection, Enrollment, Offline Model & Mic Calibration
    val wakeEnrollmentStore = WakeEnrollmentStore(application)
    val wakeModelManager = WakeModelManager(application)
    val wakeWordEngine = WakeWordEngine(application, wakeEnrollmentStore, wakeModelManager)
    val wakeEnrollmentState: StateFlow<WakeEnrollmentState> = wakeEnrollmentStore.state
    val wakeModelState: StateFlow<WakeModelState> = wakeModelManager.state
    val micTestSnapshot: StateFlow<MicTestSnapshot> = wakeWordEngine.micTest
    val wakeEngineStatus: StateFlow<WakeEngineStatus> = combine(
        wakeWordEngine.status,
        MahiVoiceForegroundService.isWakeServiceActive,
        preferencesStore.prefsFlow
    ) { localStatus, serviceActive, prefs ->
        when {
            localStatus == WakeEngineStatus.PAUSED_FOR_ACTIVE_VOICE ||
                localStatus == WakeEngineStatus.STARTING ||
                localStatus == WakeEngineStatus.ERROR -> localStatus
            prefs.wakeDetectionEnabled && serviceActive -> WakeEngineStatus.LISTENING_FOR_WAKE
            !prefs.wakeDetectionEnabled -> WakeEngineStatus.OFF
            else -> localStatus
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, WakeEngineStatus.OFF)
    private val _wakeErrorMessage = MutableStateFlow<String?>(null)
    val wakeErrorMessage: StateFlow<String?> = _wakeErrorMessage.asStateFlow()

    // Part 2: Smart Intelligence & Vision (All 10 Active Managers)
    val screenVisionManager = ScreenVisionManager(providerClient)
    val screenshotVisionManager = ScreenshotVisionManager(neuralLensAnalyzer, providerClient)
    val cameraVisionManager = CameraVisionManager(neuralLensAnalyzer)
    val objectRecognitionManager = ObjectRecognitionManager(neuralLensAnalyzer)
    val sceneRecognitionManager = SceneRecognitionManager(neuralLensAnalyzer)
    val ocrManager = OCRManager(providerClient)
    val notificationUnderstandingManager = NotificationUnderstandingManager(providerClient)
    val galleryAnalysisManager = GalleryAnalysisManager(neuralLensAnalyzer)
    val visualQuestionAnswerManager = VisualQuestionAnswerManager(providerClient)
    val imageComparisonManager = ImageComparisonManager(ocrManager, providerClient)

    val visualQaTurns: StateFlow<List<VisualQaTurn>> = visualQuestionAnswerManager.turns
    private val _imageCompareResult = MutableStateFlow<ImageComparisonResult?>(null)
    val imageCompareResult: StateFlow<ImageComparisonResult?> = _imageCompareResult.asStateFlow()
    private val _ocrExtractedText = MutableStateFlow("")
    val ocrExtractedText: StateFlow<String> = _ocrExtractedText.asStateFlow()

    // Part 3: Conversation History & Consent-Gated Memory
    val conversationHistoryStore = ConversationHistoryStore(application)
    val userMemoryStore = UserMemoryStore(application)
    val historyThreads: StateFlow<List<ConversationThread>> = conversationHistoryStore.threads
    private val _activeHistoryThreadId = MutableStateFlow(conversationHistoryStore.activeThreadId)
    val activeHistoryThreadId: StateFlow<String?> = _activeHistoryThreadId.asStateFlow()
    val memoryProfile: StateFlow<UserMemoryProfile> = userMemoryStore.profile

    // Part 4: Smart Smartphone Control & Telemetry
    val smartphoneControlManager = SmartphoneControlManager(application, deviceControlHelper)
    private val _batteryInfo = MutableStateFlow(smartphoneControlManager.batteryManager.getBatteryInfo())
    val batteryInfo: StateFlow<BatteryInfoSnapshot> = _batteryInfo.asStateFlow()
    private val _storageInfo = MutableStateFlow(smartphoneControlManager.storageManager.getStorageInfo())
    val storageInfo: StateFlow<StorageInfoSnapshot> = _storageInfo.asStateFlow()
    private val _networkInfo = MutableStateFlow(smartphoneControlManager.networkManager.getNetworkInfo())
    val networkInfo: StateFlow<NetworkInfoSnapshot> = _networkInfo.asStateFlow()
    private val _deviceInfo = MutableStateFlow(smartphoneControlManager.deviceInfoManager.getDeviceInfo())
    val deviceInfo: StateFlow<DeviceInfoSnapshot> = _deviceInfo.asStateFlow()
    private val _volumeLevels = MutableStateFlow(smartphoneControlManager.volumeManager.getAllLevels())
    val volumeLevels: StateFlow<AllVolumeLevels> = _volumeLevels.asStateFlow()
    private val _brightnessPercent = MutableStateFlow(deviceControlHelper.getBrightnessPercent())
    val brightnessPercent: StateFlow<Int> = _brightnessPercent.asStateFlow()
    private val _canWriteSettings = MutableStateFlow(deviceControlHelper.canWriteSystemSettings())
    val canWriteSettings: StateFlow<Boolean> = _canWriteSettings.asStateFlow()
    private val _isFlashlightOn = MutableStateFlow(false)
    val isFlashlightOn: StateFlow<Boolean> = _isFlashlightOn.asStateFlow()
    private val _smartControlStatus = MutableStateFlow("")
    val smartControlStatus: StateFlow<String> = _smartControlStatus.asStateFlow()

    private var lastSavedLiveAssistantText: String = ""

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
        MahiVoiceForegroundService.onWakeDisabledFromNotification = {
            preferencesStore.setWakeDetectionEnabled(false)
            wakeWordEngine.stopAll()
        }
        MahiVoiceForegroundService.onWakeTriggered = { _ ->
            viewModelScope.launch {
                val hasAudio = ContextCompat.checkSelfPermission(
                    getApplication(),
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                if (hasAudio && !_uiState.value.isGeminiLiveActive &&
                    _uiState.value.visualState == AssistantVisualState.DISCONNECTED
                ) {
                    startSession()
                }
            }
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
        refreshSmartControlTelemetry()

        val hasAudio = ContextCompat.checkSelfPermission(
            application,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (initialPrefs.wakeDetectionEnabled && hasAudio && wakeModelManager.isModelReady()) {
            MahiVoiceForegroundService.startWakeDetection(application)
        }
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
        if (intent.getBooleanExtra(WakePopupActivity.EXTRA_START_LIVE_FROM_WAKE, false)) {
            intent.removeExtra(WakePopupActivity.EXTRA_START_LIVE_FROM_WAKE)
            val hasAudio = ContextCompat.checkSelfPermission(
                getApplication(),
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (hasAudio) {
                startSession()
            }
        }
    }

    fun consumeRequestedTab() {
        _requestedTab.value = null
    }

    fun consumePendingPhotoPickerMode() {
        _pendingPhotoPickerMode.value = null
    }

    fun respondToLiveToolConfirmation(approved: Boolean) {
        _pendingLiveConfirmation.value = null
        liveConfirmationDeferred?.complete(approved)
        liveConfirmationDeferred = null
    }

    private suspend fun requestSensitiveConfirmation(title: String, reason: String): Boolean {
        liveConfirmationDeferred?.cancel()
        val deferred = CompletableDeferred<Boolean>()
        liveConfirmationDeferred = deferred
        _pendingLiveConfirmation.value = title to reason
        return try {
            deferred.await()
        } catch (_: Exception) {
            false
        }
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
                        _uiState.update {
                            it.copy(
                                liveUserText = text,
                                visualState = AssistantVisualState.LISTENING
                            )
                        }
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
                    _uiState.update { cur ->
                        cur.copy(
                            isMicActivelyCapturing = activelyCapturing,
                            errorMessage = errorMsg ?: cur.errorMessage
                        )
                    }
                }
            }
            launch {
                audioRecordManager.amplitudeFlow.collectLatest { amp ->
                    _uiState.update { it.copy(micLevel = amp) }
                }
            }
            launch {
                audioRecordManager.waveformBarsFlow.collectLatest { micBars ->
                    _uiState.update { cur ->
                        if (cur.visualState == AssistantVisualState.LISTENING ||
                            cur.visualState == AssistantVisualState.RECONNECTING
                        ) {
                            cur.copy(waveformBars = micBars)
                        } else cur
                    }
                }
            }
            launch {
                speechSynthesizer.speakingWaveform.collectLatest { ttsBars ->
                    _uiState.update { cur ->
                        if (cur.visualState == AssistantVisualState.SPEAKING && !cur.isGeminiLiveActive) {
                            cur.copy(waveformBars = ttsBars)
                        } else cur
                    }
                }
            }
            launch {
                geminiLiveClient.speakingWaveform.collectLatest { liveBars ->
                    _uiState.update { cur ->
                        if (cur.visualState == AssistantVisualState.SPEAKING && cur.isGeminiLiveActive) {
                            cur.copy(waveformBars = liveBars)
                        } else cur
                    }
                }
            }
            launch {
                MahiVoiceForegroundService.foregroundRestrictionNotice.collectLatest { notice ->
                    _uiState.update { it.copy(backgroundMicNotice = notice) }
                }
            }
            launch {
                audioRecordManager.isSpeechDetected.collectLatest { speakingUser ->
                    val st = _uiState.value
                    if (speakingUser &&
                        st.visualState == AssistantVisualState.SPEAKING &&
                        !st.isMuted &&
                        !st.isGeminiLiveActive
                    ) {
                        interruptAssistant()
                    }
                }
            }
            launch {
                wakeWordEngine.lastError.collectLatest { err ->
                    _wakeErrorMessage.value = err
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
        val hasAudioPerm = ContextCompat.checkSelfPermission(
            getApplication(),
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasAudioPerm) {
            _uiState.update {
                it.copy(
                    visualState = AssistantVisualState.ERROR,
                    isGeminiLiveActive = false,
                    isMicActivelyCapturing = false,
                    errorCategory = ApiErrorCategory.CONNECTION_FAILED,
                    errorMessage = "Microphone permission is required before starting voice mode."
                )
            }
            return
        }

        val activeProvider = apiManagerState.value.activeProvider
        val activeModel = apiManagerState.value.activeConfig.selectedModel
        val apiKey = secureKeyStore.getActiveDecryptedKey(activeProvider)

        if (apiKey.isNullOrBlank()) {
            _uiState.update {
                it.copy(
                    visualState = AssistantVisualState.ERROR,
                    isGeminiLiveActive = false,
                    isMicActivelyCapturing = false,
                    errorCategory = ApiErrorCategory.MISSING_KEY,
                    errorMessage = "${activeProvider.displayName} API key missing. Open API Keys & Providers to add a key."
                )
            }
            return
        }

        // Single-mic arbitration: pause wake detection / mic test before starting active voice mic
        wakeWordEngine.stopMicTest()
        wakeWordEngine.pauseForActiveVoice()
        MahiVoiceForegroundService.pauseWakeForActiveSession(getApplication())
        wakeWordEngine.requestActiveVoiceAudioFocus()

        // Start foreground microphone service cleanly after RECORD_AUDIO verification (Android 14+ requirement)
        MahiVoiceForegroundService.start(getApplication())

        _uiState.update {
            it.copy(
                visualState = AssistantVisualState.CONNECTING,
                liveUserText = "",
                errorMessage = null,
                errorCategory = null,
                reconnectMessage = null
            )
        }

        if (shouldUseGeminiLiveAudio()) {
            startGeminiLiveSession(apiKey, activeModel)
        } else {
            startTurnBasedVoiceSession()
        }
    }

    private fun releaseActiveMicAndMaybeResumeWake() {
        wakeWordEngine.abandonActiveVoiceAudioFocus()
        if (userPrefs.value.wakeDetectionEnabled && wakeModelManager.isModelReady()) {
            MahiVoiceForegroundService.resumeWakeIfEnabled(getApplication())
        } else {
            MahiVoiceForegroundService.stop(getApplication())
        }
    }

    private fun startGeminiLiveSession(apiKey: String, model: String) {
        val memCtx = userMemoryStore.buildPromptContext(userPrefs.value.memoryConsentGranted)
        val systemPrompt = aiEngine.buildSystemPrompt(_uiState.value.currentMood, userPrefs.value.language, memCtx)
        geminiLiveClient.setMuted(_uiState.value.isMuted)
        audioRecordManager.isInputMuted = _uiState.value.isMuted

        // Start persistent PCM16 @ 16kHz microphone capture FIRST so initial setup frames queue safely
        audioRecordManager.onPcmAudioFrame = { pcmChunk ->
            geminiLiveClient.sendPcmAudioChunk(pcmChunk)
        }
        val micStarted = audioRecordManager.startListening()
        if (!micStarted) {
            releaseActiveMicAndMaybeResumeWake()
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
                    _uiState.update {
                        it.copy(
                            visualState = AssistantVisualState.LISTENING,
                            isGeminiLiveActive = true,
                            isMicActivelyCapturing = audioRecordManager.isActivelyCapturing(),
                            reconnectMessage = null,
                            errorMessage = null,
                            liveAssistantText = "Gemini Live audio active ($model). Continuous listening ON!"
                        )
                    }
                    val queued = pendingLivePromptAfterConnect
                    if (!queued.isNullOrBlank()) {
                        pendingLivePromptAfterConnect = null
                        _uiState.update { it.copy(liveUserText = queued) }
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

                    _uiState.update { it.copy(liveUserText = accumulated) }
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
                    _uiState.update { it.copy(liveAssistantText = text) }
                    if (text.isNotBlank() && text != lastSavedLiveAssistantText && text.length > 8) {
                        lastSavedLiveAssistantText = text
                    }
                }

                override fun onAssistantSpeakingChanged(isSpeaking: Boolean) {
                    val current = _uiState.value.visualState
                    if (current == AssistantVisualState.DISCONNECTED || current == AssistantVisualState.ERROR) return

                    if (!isSpeaking) {
                        val userSaid = _uiState.value.liveUserText.trim()
                        val assistantSaid = _uiState.value.liveAssistantText.trim()
                        if (assistantSaid.isNotBlank() && assistantSaid == lastSavedLiveAssistantText) {
                            if (userSaid.isNotBlank()) {
                                conversationHistoryStore.appendTurn(
                                    "user",
                                    userSaid,
                                    userPrefs.value.language.id,
                                    _uiState.value.currentMood.name
                                )
                            }
                            conversationHistoryStore.appendTurn(
                                "assistant",
                                assistantSaid,
                                userPrefs.value.language.id,
                                _uiState.value.currentMood.name
                            )
                            _activeHistoryThreadId.value = conversationHistoryStore.activeThreadId
                            lastSavedLiveAssistantText = ""
                        }
                    }

                    if (!isSpeaking && !_uiState.value.isContinuousMode && geminiLiveClient.connectionPhase.value != GeminiLiveConnectionPhase.RECONNECTING) {
                        // Continuous mode explicitly OFF -> stop listening after reply finishes
                        stopSession()
                        return
                    }

                    _uiState.update {
                        it.copy(visualState = if (isSpeaking) AssistantVisualState.SPEAKING else AssistantVisualState.LISTENING)
                    }
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
                            toolsManager.executeAsync(action)
                        }
                        val targetMood = if (action.name.equals("changeMood", ignoreCase = true)) {
                            val moodName = action.params["mood"]
                            MahiMood.entries.firstOrNull { it.name.equals(moodName, ignoreCase = true) }
                        } else null
                        if (targetMood != null) {
                            aiEngine.currentMood = targetMood
                            val memCtx = userMemoryStore.buildPromptContext(userPrefs.value.memoryConsentGranted)
                            geminiLiveClient.updateSystemInstruction(
                                aiEngine.buildSystemPrompt(targetMood, userPrefs.value.language, memCtx)
                            )
                        }
                        _uiState.update { cur ->
                            cur.copy(
                                lastExecutedTool = result,
                                currentMood = targetMood ?: cur.currentMood
                            )
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
                    _uiState.update { it.copy(visualState = AssistantVisualState.LISTENING) }
                }

                override fun onReconnecting(attempt: Int, maxAttempts: Int, reason: String) {
                    _uiState.update {
                        it.copy(
                            visualState = AssistantVisualState.RECONNECTING,
                            reconnectMessage = "Reconnecting Live session ($attempt/$maxAttempts)...",
                            errorMessage = null
                        )
                    }
                }

                override fun onError(error: GeminiLiveError) {
                    // STRICT RULE: Never silently fall back from Gemini Live audio-to-audio to text-only mode!
                    audioRecordManager.onPcmAudioFrame = null
                    audioRecordManager.stopListening()
                    releaseActiveMicAndMaybeResumeWake()
                    _uiState.update {
                        it.copy(
                            visualState = AssistantVisualState.ERROR,
                            isGeminiLiveActive = false,
                            isMicActivelyCapturing = false,
                            reconnectMessage = null,
                            errorCategory = error.category,
                            errorMessage = error.message,
                            waveformBars = List(32) { 0.06f }
                        )
                    }
                }

                override fun onDisconnected() {
                    audioRecordManager.onPcmAudioFrame = null
                    audioRecordManager.stopListening()
                    releaseActiveMicAndMaybeResumeWake()
                    _uiState.update {
                        it.copy(
                            visualState = AssistantVisualState.DISCONNECTED,
                            isGeminiLiveActive = false,
                            isMicActivelyCapturing = false,
                            reconnectMessage = null,
                            waveformBars = List(32) { 0.06f }
                        )
                    }
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
                    releaseActiveMicAndMaybeResumeWake()
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
                releaseActiveMicAndMaybeResumeWake()
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
        releaseActiveMicAndMaybeResumeWake()
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
                val memCtx = userMemoryStore.buildPromptContext(userPrefs.value.memoryConsentGranted)
                val response = aiEngine.sendMessageWithProvider(
                    provider = activeProvider,
                    apiKey = apiKey,
                    selectedModel = activeConfig.selectedModel,
                    baseUrl = activeConfig.customBaseUrl,
                    userMessage = query,
                    mood = mood,
                    language = lang,
                    memoryContext = memCtx
                )

                conversationHistoryStore.appendTurn("user", query, lang.id, mood.name)
                conversationHistoryStore.appendTurn("assistant", response.replyText, lang.id, mood.name)
                _activeHistoryThreadId.value = conversationHistoryStore.activeThreadId

                if (response.toolAction != null) {
                    val toolResult = toolsManager.executeAsync(response.toolAction)
                    _uiState.value = _uiState.value.copy(lastExecutedTool = toolResult)
                }

                if (response.toolAction?.name.equals("changeMood", ignoreCase = true)) {
                    val targetMoodStr = response.toolAction?.params?.get("mood")
                    MahiMood.entries.firstOrNull { it.name.equals(targetMoodStr, ignoreCase = true) }?.let {
                        aiEngine.currentMood = it
                        _uiState.value = _uiState.value.copy(currentMood = it)
                        val memCtx = userMemoryStore.buildPromptContext(userPrefs.value.memoryConsentGranted)
                        geminiLiveClient.updateSystemInstruction(aiEngine.buildSystemPrompt(it, userPrefs.value.language, memCtx))
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
                    MahiAccessibilityService.executeGestureIntent(routed.followUpGesture, getApplication())
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
                val gOut = MahiAccessibilityService.executeGestureIntent(routed.gesture, getApplication())
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
            is RoutedVoiceCommand.ReadNotifications -> {
                val res = toolsManager.execute(
                    ToolAction(
                        name = "readNotifications",
                        params = if (routed.whatsAppOnly) mapOf("filter" to "whatsapp") else emptyMap()
                    )
                )
                ok = res.isSuccess
                cardTitle = res.displayTitle
                outcomeText = res.message
            }
            is RoutedVoiceCommand.ReplyWhatsApp -> {
                val matchingWa = MahiNotificationService.notifications.value.firstOrNull { notif ->
                    notif.isWhatsApp && notif.canReply &&
                        (routed.senderHint.isBlank() || notif.senderTitle.contains(routed.senderHint, ignoreCase = true))
                }
                if (matchingWa == null) {
                    ok = false
                    cardTitle = "WhatsApp Reply"
                    outcomeText = "No replyable WhatsApp notification found."
                } else {
                    cardTitle = "Confirm WhatsApp Reply"
                    outcomeText = "Review reply to ${matchingWa.senderTitle} in Agent tab before sending."
                    _requestedTab.value = "AGENT"
                    sendAuthorizedWhatsAppReply(matchingWa.key, matchingWa.senderTitle, routed.message)
                }
            }
            is RoutedVoiceCommand.AnalyzeScreen -> {
                val res = screenVisionManager.readAndExplainScreen(currentVisionAiConfig(), routed.question)
                ok = res.isSuccess
                cardTitle = "Screen Vision"
                outcomeText = res.getOrElse { it.localizedMessage ?: "Screen Vision unavailable" }
                _screenAiAnalysis.value = outcomeText
                _visionAnswer.value = outcomeText
            }
            is RoutedVoiceCommand.AnalyzeScreenshot -> {
                val res = screenshotVisionManager.captureAndAnalyzeScreenshot(currentVisionAiConfig(), routed.question)
                ok = res.isSuccess
                cardTitle = "Screenshot Vision"
                outcomeText = res.getOrElse { it.localizedMessage ?: "Screenshot capture unavailable" }
                _visionAnswer.value = outcomeText
                screenshotVisionManager.lastScreenshot.value?.let {
                    visualQuestionAnswerManager.setActiveImage(it)
                }
            }
            is RoutedVoiceCommand.DescribeCameraVision -> {
                val res = cameraVisionManager.describeCameraView(currentVisionAiConfig(), routed.question)
                ok = res.isSuccess
                cardTitle = "Camera Vision"
                outcomeText = res.getOrElse {
                    _requestedTab.value = "LENS"
                    it.localizedMessage ?: "Open Neural Lens so camera frame is active."
                }
                _visionAnswer.value = outcomeText
            }
            RoutedVoiceCommand.AnalyzeNotifications -> {
                val res = notificationUnderstandingManager.summarizeActiveNotifications(getApplication(), currentVisionAiConfig())
                ok = res.isSuccess
                cardTitle = "Notification Digest"
                outcomeText = res.getOrElse { it.localizedMessage ?: "Notification access required" }
            }
            is RoutedVoiceCommand.ExtractOcr -> {
                val tool = ToolAction(
                    name = if (routed.fromScreenshot) "readTextFromScreenshot" else "readTextFromCamera",
                    params = mapOf("explain" to routed.explainWithAi.toString())
                )
                val res = toolsManager.executeAsync(tool)
                ok = res.isSuccess
                cardTitle = res.displayTitle
                outcomeText = res.message
            }
            RoutedVoiceCommand.OpenGalleryAnalysis -> {
                val res = visionToolDelegate.openGalleryAnalysis()
                ok = res.isSuccess
                cardTitle = res.displayTitle
                outcomeText = res.message
            }
            RoutedVoiceCommand.CompareGalleryImages -> {
                val res = visionToolDelegate.compareImages()
                ok = res.isSuccess
                cardTitle = res.displayTitle
                outcomeText = res.message
            }
            is RoutedVoiceCommand.TapScreenElement -> {
                val tapOut = MahiAccessibilityService.tapElement(
                    context = getApplication(),
                    index = routed.index,
                    query = routed.query.takeIf { it.isNotBlank() }
                )
                ok = tapOut.success
                cardTitle = "Tap Screen Element"
                outcomeText = tapOut.message
            }
            is RoutedVoiceCommand.TypeOnScreen -> {
                val res = toolsManager.executeAsync(
                    ToolAction(
                        name = "typeText",
                        params = buildMap {
                            put("text", routed.text)
                            if (routed.targetHint.isNotBlank()) put("query", routed.targetHint)
                            put("submit", routed.submit.toString())
                            put("append", routed.append.toString())
                            put("clearFirst", routed.clearFirst.toString())
                        }
                    )
                )
                ok = res.isSuccess
                cardTitle = res.displayTitle
                outcomeText = res.message
            }
            is RoutedVoiceCommand.SmartControl -> {
                val segments = routed.chainedSteps.ifEmpty { smartphoneControlManager.splitChainedCommands(routed.rawPhrase) }
                val outcome = smartphoneControlManager.executeChainedCommands(segments) { stepPhrase ->
                    executeSingleChainedControlStep(stepPhrase)
                }
                ok = outcome.success
                cardTitle = "Smart Control"
                outcomeText = outcome.message
                refreshSmartControlTelemetry()
            }
        }

        val effectiveUser = originalUtterance.ifBlank { _uiState.value.liveUserText }
        if (effectiveUser.isNotBlank()) {
            conversationHistoryStore.appendTurn("user", effectiveUser, userPrefs.value.language.id, _uiState.value.currentMood.name)
            conversationHistoryStore.appendTurn("assistant", outcomeText, userPrefs.value.language.id, _uiState.value.currentMood.name)
            _activeHistoryThreadId.value = conversationHistoryStore.activeThreadId
        }

        _uiState.update { cur ->
            cur.copy(
                liveUserText = effectiveUser,
                liveAssistantText = outcomeText,
                lastExecutedTool = ToolExecutionResult(
                    actionName = cardTitle,
                    displayTitle = cardTitle,
                    message = outcomeText,
                    linkUrl = cardUrl,
                    isSuccess = ok
                )
            )
        }
        if (!_uiState.value.isMuted && !_uiState.value.isGeminiLiveActive) {
            speakText(outcomeText)
        }
    }

    private suspend fun executeSingleChainedControlStep(stepPhrase: String): AutomationOutcome {
        smartphoneControlManager.handleDirectControlPhrase(stepPhrase)?.let { return it }
        return when (val sub = VoiceCommandRouter.parseCommand(stepPhrase)) {
            is RoutedVoiceCommand.LaunchApp -> {
                val r = appLauncherManager.launchApp(sub.appName, openStoreOrWebIfMissing = true)
                AutomationOutcome(r.success, r.message, r.verificationDetail)
            }
            is RoutedVoiceCommand.ExecuteGesture -> MahiAccessibilityService.executeGestureIntent(sub.gesture, getApplication())
            is RoutedVoiceCommand.TapScreenElement -> MahiAccessibilityService.tapElement(
                context = getApplication(),
                index = sub.index,
                query = sub.query.takeIf { it.isNotBlank() }
            )
            RoutedVoiceCommand.OpenGoogle -> {
                val r = appLauncherManager.openGoogleApp()
                AutomationOutcome(r.success, r.message, r.verificationDetail)
            }
            is RoutedVoiceCommand.OpenWebsite -> {
                val r = appLauncherManager.openWebsite(sub.url)
                AutomationOutcome(r.success, r.message, r.verificationDetail)
            }
            else -> AutomationOutcome(false, "Unsupported step: $stepPhrase")
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
        val memCtx = userMemoryStore.buildPromptContext(userPrefs.value.memoryConsentGranted)
        geminiLiveClient.updateSystemInstruction(aiEngine.buildSystemPrompt(mood, userPrefs.value.language, memCtx))

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
        val memCtx = userMemoryStore.buildPromptContext(userPrefs.value.memoryConsentGranted)
        geminiLiveClient.updateSystemInstruction(aiEngine.buildSystemPrompt(_uiState.value.currentMood, language, memCtx))
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

    // --- Part 1: Wake Up Detection, 5-Sample Enrollment, Vosk Model & Mic Test ---

    fun setWakeDetectionEnabled(enabled: Boolean) {
        val app = getApplication<Application>()
        if (enabled) {
            val hasAudio = ContextCompat.checkSelfPermission(
                app,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasAudio) {
                _wakeErrorMessage.value = "Grant Microphone permission before enabling Wake Up Detection."
                preferencesStore.setWakeDetectionEnabled(false)
                return
            }
            if (!wakeModelManager.isModelReady()) {
                _wakeErrorMessage.value = "Download the offline Vosk model (~40 MB) first, then enable Wake Up Detection."
                preferencesStore.setWakeDetectionEnabled(false)
                return
            }
            preferencesStore.setWakeDetectionEnabled(true)
            _wakeErrorMessage.value = null
            wakeWordEngine.stopMicTest()
            MahiVoiceForegroundService.startWakeDetection(app)
        } else {
            preferencesStore.setWakeDetectionEnabled(false)
            wakeWordEngine.stopAll()
            MahiVoiceForegroundService.disableWakeDetection(app)
        }
    }

    fun downloadWakeModel() {
        viewModelScope.launch {
            val res = wakeModelManager.downloadAndInstallModel()
            if (res.isSuccess && userPrefs.value.wakeDetectionEnabled) {
                MahiVoiceForegroundService.startWakeDetection(getApplication())
            }
        }
    }

    fun deleteWakeModel() {
        setWakeDetectionEnabled(false)
        wakeModelManager.deleteModel()
    }

    fun recordWakeEnrollmentSample() {
        val app = getApplication<Application>()
        val hasAudio = ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!hasAudio) {
            _wakeErrorMessage.value = "Microphone permission required for voice calibration."
            return
        }
        val resumeWakeAfter = MahiVoiceForegroundService.isWakeServiceActive.value
        if (resumeWakeAfter) {
            MahiVoiceForegroundService.pauseWakeForActiveSession(app)
        }
        wakeWordEngine.captureEnrollmentSample {
            if (resumeWakeAfter && userPrefs.value.wakeDetectionEnabled) {
                MahiVoiceForegroundService.resumeWakeIfEnabled(app)
            }
        }
    }

    fun resetWakeEnrollment() {
        wakeEnrollmentStore.resetEnrollment()
    }

    fun toggleMicTest() {
        val app = getApplication<Application>()
        if (wakeWordEngine.micTest.value.isTesting) {
            wakeWordEngine.stopMicTest()
            if (userPrefs.value.wakeDetectionEnabled && wakeModelManager.isModelReady()) {
                MahiVoiceForegroundService.resumeWakeIfEnabled(app)
            }
            return
        }
        val hasAudio = ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!hasAudio) {
            _wakeErrorMessage.value = "Grant Microphone permission to test microphone."
            return
        }
        if (MahiVoiceForegroundService.isWakeServiceActive.value) {
            MahiVoiceForegroundService.pauseWakeForActiveSession(app)
        }
        wakeWordEngine.startMicTest()
    }

    fun openBatteryUnrestrictedSettings() {
        smartphoneControlManager.quickSettingsManager.openBatteryOptimizationSettings()
    }

    // --- Neural Lens & Smart Intelligence Vision (All 10 Managers) ---

    private fun currentVisionAiConfig(): VisionAiConfig {
        val state = apiManagerState.value
        val provider = state.activeProvider
        val cfg = state.activeConfig
        val apiKey = secureKeyStore.getActiveDecryptedKey(provider).orEmpty()
        return VisionAiConfig(
            provider = provider,
            apiKey = apiKey,
            model = cfg.selectedModel,
            baseUrl = cfg.customBaseUrl,
            language = userPrefs.value.language
        )
    }

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
                neuralLensAnalyzer.latestCapturedBitmap?.let { bmp ->
                    if (!visualQuestionAnswerManager.hasActiveImage()) {
                        visualQuestionAnswerManager.setActiveImage(bmp)
                    }
                }
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
            val res = galleryAnalysisManager.analyzeSelectedImage(
                context = getApplication(),
                uri = uri,
                aiConfig = currentVisionAiConfig()
            )
            res.onSuccess { (bmp, answer) ->
                visualQuestionAnswerManager.setActiveImage(bmp)
                _visionAnswer.value = answer
            }.onFailure { err ->
                _visionError.value = err.localizedMessage ?: "Could not decode selected photo."
            }
            _isVisionLoading.value = false
        }
    }

    fun identifyObjectsInLens() {
        _isVisionLoading.value = true
        _visionError.value = null
        viewModelScope.launch {
            val res = objectRecognitionManager.identifyObjects(currentVisionAiConfig())
            res.onSuccess { _visionAnswer.value = it }
                .onFailure { _visionError.value = it.localizedMessage }
            _isVisionLoading.value = false
        }
    }

    fun recognizeSceneInLens() {
        _isVisionLoading.value = true
        _visionError.value = null
        viewModelScope.launch {
            val res = sceneRecognitionManager.recognizeScene(currentVisionAiConfig())
            res.onSuccess { _visionAnswer.value = it }
                .onFailure { _visionError.value = it.localizedMessage }
            _isVisionLoading.value = false
        }
    }

    fun readAndExplainLiveScreen() {
        _isVisionLoading.value = true
        _visionError.value = null
        viewModelScope.launch {
            val res = screenVisionManager.readAndExplainScreen(currentVisionAiConfig())
            res.onSuccess {
                _visionAnswer.value = it
                _screenAiAnalysis.value = it
            }.onFailure {
                _visionError.value = it.localizedMessage
            }
            _isVisionLoading.value = false
        }
    }

    fun captureAndAnalyzeScreenshot() {
        _isVisionLoading.value = true
        _visionError.value = null
        viewModelScope.launch {
            val res = screenshotVisionManager.captureAndAnalyzeScreenshot(currentVisionAiConfig())
            res.onSuccess {
                _visionAnswer.value = it
                screenshotVisionManager.lastScreenshot.value?.let { bmp ->
                    visualQuestionAnswerManager.setActiveImage(bmp)
                }
            }.onFailure {
                _visionError.value = it.localizedMessage ?: screenshotVisionManager.getCaptureCapabilityNote()
            }
            _isVisionLoading.value = false
        }
    }

    fun summarizeNotificationsSafely() {
        _isVisionLoading.value = true
        _visionError.value = null
        viewModelScope.launch {
            val res = notificationUnderstandingManager.summarizeActiveNotifications(getApplication(), currentVisionAiConfig())
            res.onSuccess { _visionAnswer.value = it }
                .onFailure { _visionError.value = it.localizedMessage }
            _isVisionLoading.value = false
        }
    }

    fun extractOcrFromCurrentFrame(explainWithAi: Boolean) {
        val bmp = neuralLensAnalyzer.latestCapturedBitmap ?: screenshotVisionManager.lastScreenshot.value
        if (bmp == null) {
            _visionError.value = "Capture a camera frame, pick a photo, or take a screenshot first."
            return
        }
        _isVisionLoading.value = true
        _visionError.value = null
        viewModelScope.launch {
            val res = ocrManager.extractText(bmp, currentVisionAiConfig(), includeAiExplanation = explainWithAi)
            res.onSuccess { out ->
                _ocrExtractedText.value = out.extractedText
                _visionAnswer.value = if (out.explanation.isNotBlank()) {
                    "${out.extractedText}\n\nExplanation:\n${out.explanation}"
                } else {
                    out.extractedText
                }
            }.onFailure {
                _visionError.value = it.localizedMessage ?: "OCR failed."
            }
            _isVisionLoading.value = false
        }
    }

    fun copyOcrText(text: String) {
        val copied = ocrManager.copyToClipboard(getApplication(), text)
        if (copied) {
            _uiState.update {
                it.copy(lastExecutedTool = ToolExecutionResult("ocrCopy", "Copied OCR", "Extracted text copied to clipboard.", isSuccess = true))
            }
        }
    }

    fun askVisualFollowUp(question: String) {
        _isVisionLoading.value = true
        _visionError.value = null
        viewModelScope.launch {
            val res = visualQuestionAnswerManager.askFollowUp(question, currentVisionAiConfig())
            res.onSuccess { _visionAnswer.value = it }
                .onFailure { _visionError.value = it.localizedMessage }
            _isVisionLoading.value = false
        }
    }

    fun resetVisualQa() {
        visualQuestionAnswerManager.resetConversation()
    }

    fun compareTwoImages(uriA: Uri, uriB: Uri) {
        _isVisionLoading.value = true
        _visionError.value = null
        viewModelScope.launch {
            val app = getApplication<Application>()
            val bmpA = VisionBitmapUtils.decodeUri(app, uriA)
            val bmpB = VisionBitmapUtils.decodeUri(app, uriB)
            if (bmpA == null || bmpB == null) {
                _visionError.value = "Could not load both selected images."
                _isVisionLoading.value = false
                return@launch
            }
            val res = imageComparisonManager.compareImages(bmpA, bmpB, currentVisionAiConfig())
            res.onSuccess { cmp ->
                _imageCompareResult.value = cmp
                _visionAnswer.value = cmp.aiComparison ?: cmp.offlineDiffSummary
            }.onFailure {
                _visionError.value = it.localizedMessage
            }
            _isVisionLoading.value = false
        }
    }

    // --- Part 3: Conversation History & Consent-Gated User Memory ---

    fun selectHistoryThread(threadId: String) {
        val thread = conversationHistoryStore.selectThread(threadId) ?: return
        _activeHistoryThreadId.value = thread.id
        val lastUser = thread.turns.lastOrNull { it.role == "user" }?.text.orEmpty()
        val lastAi = thread.turns.lastOrNull { it.role == "assistant" }?.text.orEmpty()
        _uiState.update {
            it.copy(
                liveUserText = lastUser,
                liveAssistantText = lastAi.ifBlank { it.liveAssistantText }
            )
        }
    }

    fun startNewHistoryThread() {
        val created = conversationHistoryStore.startNewThread(
            languageId = userPrefs.value.language.id,
            moodName = _uiState.value.currentMood.name
        )
        _activeHistoryThreadId.value = created.id
        _uiState.update {
            it.copy(
                liveUserText = "",
                liveAssistantText = "Started a fresh conversation thread!"
            )
        }
    }

    fun deleteHistoryThread(threadId: String) {
        conversationHistoryStore.deleteThread(threadId)
        _activeHistoryThreadId.value = conversationHistoryStore.activeThreadId
    }

    fun clearAllHistory() {
        conversationHistoryStore.clearAllHistory()
        _activeHistoryThreadId.value = null
    }

    fun setMemoryConsentGranted(enabled: Boolean) {
        preferencesStore.setMemoryConsentGranted(enabled)
        val memCtx = userMemoryStore.buildPromptContext(enabled)
        geminiLiveClient.updateSystemInstruction(aiEngine.buildSystemPrompt(_uiState.value.currentMood, userPrefs.value.language, memCtx))
    }

    fun updateMemoryProfileFields(name: String, langNote: String, tone: String) {
        userMemoryStore.updateProfileFields(
            consentGranted = userPrefs.value.memoryConsentGranted,
            userName = name,
            preferredLanguageNote = langNote,
            tonePreference = tone
        )
        val memCtx = userMemoryStore.buildPromptContext(userPrefs.value.memoryConsentGranted)
        geminiLiveClient.updateSystemInstruction(aiEngine.buildSystemPrompt(_uiState.value.currentMood, userPrefs.value.language, memCtx))
    }

    fun addOrEditMemoryFact(category: String, content: String, existingId: String?) {
        userMemoryStore.addOrUpdateFact(userPrefs.value.memoryConsentGranted, category, content, existingId)
        val memCtx = userMemoryStore.buildPromptContext(userPrefs.value.memoryConsentGranted)
        geminiLiveClient.updateSystemInstruction(aiEngine.buildSystemPrompt(_uiState.value.currentMood, userPrefs.value.language, memCtx))
    }

    fun deleteMemoryFact(factId: String) {
        userMemoryStore.deleteFact(factId)
        val memCtx = userMemoryStore.buildPromptContext(userPrefs.value.memoryConsentGranted)
        geminiLiveClient.updateSystemInstruction(aiEngine.buildSystemPrompt(_uiState.value.currentMood, userPrefs.value.language, memCtx))
    }

    fun forgetAllMemory() {
        userMemoryStore.forgetEverything()
        preferencesStore.setMemoryConsentGranted(false)
        geminiLiveClient.updateSystemInstruction(aiEngine.buildSystemPrompt(_uiState.value.currentMood, userPrefs.value.language, ""))
    }

    // --- Part 4: Smart Smartphone Control ---

    fun refreshSmartControlTelemetry() {
        _batteryInfo.value = smartphoneControlManager.batteryManager.getBatteryInfo()
        _storageInfo.value = smartphoneControlManager.storageManager.getStorageInfo()
        _networkInfo.value = smartphoneControlManager.networkManager.getNetworkInfo()
        _deviceInfo.value = smartphoneControlManager.deviceInfoManager.getDeviceInfo()
        _volumeLevels.value = smartphoneControlManager.volumeManager.getAllLevels()
        _brightnessPercent.value = deviceControlHelper.getBrightnessPercent()
        _canWriteSettings.value = deviceControlHelper.canWriteSystemSettings()
    }

    fun setFlashlightEnabled(enabled: Boolean) {
        val res = toolsManager.execute(ToolAction("toggleFlashlight", mapOf("state" to if (enabled) "on" else "off")))
        _isFlashlightOn.value = enabled
        _smartControlStatus.value = res.message
    }

    fun setVolumeStream(stream: VolumeStreamType, percent: Int) {
        val out = smartphoneControlManager.volumeManager.setStreamPercent(stream, percent)
        _volumeLevels.value = smartphoneControlManager.volumeManager.getAllLevels()
        _smartControlStatus.value = out.message
    }

    fun toggleMuteMedia(mute: Boolean) {
        val out = smartphoneControlManager.volumeManager.muteMedia(mute)
        _volumeLevels.value = smartphoneControlManager.volumeManager.getAllLevels()
        _smartControlStatus.value = out.message
    }

    fun setBrightnessPercent(percent: Int) {
        val out = deviceControlHelper.setBrightnessPercent(percent)
        _brightnessPercent.value = deviceControlHelper.getBrightnessPercent()
        _canWriteSettings.value = deviceControlHelper.canWriteSystemSettings()
        _smartControlStatus.value = out.message
    }

    fun openQuickSettingsShade() {
        _smartControlStatus.value = smartphoneControlManager.quickSettingsManager.openQuickSettingsShade().message
    }

    fun openNotificationShade() {
        _smartControlStatus.value = smartphoneControlManager.quickSettingsManager.openNotificationShade().message
    }

    fun openWifiPanel() {
        _smartControlStatus.value = smartphoneControlManager.quickSettingsManager.openInternetOrWirelessPanel().message
    }

    fun openBluetoothPanel() {
        _smartControlStatus.value = smartphoneControlManager.quickSettingsManager.openBluetoothSettings().message
    }

    fun openMobileDataSettings() {
        _smartControlStatus.value = smartphoneControlManager.quickSettingsManager.openMobileDataSettings().message
    }

    fun openAirplaneSettings() {
        _smartControlStatus.value = smartphoneControlManager.quickSettingsManager.openAirplaneModeSettings().message
    }

    fun openNfcPanel() {
        _smartControlStatus.value = smartphoneControlManager.quickSettingsManager.openNfcSettings().message
    }

    fun openWriteSettingsPermission() {
        deviceControlHelper.openWriteSettingsPermission()
    }

    fun openDndPolicySettings() {
        smartphoneControlManager.volumeManager.openDndAccessSettings()
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
        wakeWordEngine.stopMicTest()
        wakeWordEngine.abandonActiveVoiceAudioFocus()
        if (!userPrefs.value.wakeDetectionEnabled) {
            wakeWordEngine.stopAll()
            MahiVoiceForegroundService.stop(getApplication())
        }
        cameraController.releaseCamera()
        audioRecordManager.onPcmAudioFrame = null
        audioRecordManager.stopListening()
        speechRecognitionManager.release()
        speechSynthesizer.release()
        geminiLiveClient.disconnect()
        neuralLensAnalyzer.close()
        autonomousAgentEngine.stop()
        MahiAccessibilityService.stopAllGestures()
    }
}
