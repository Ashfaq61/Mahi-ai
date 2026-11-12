package com.example.viewmodel

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.MahiAiEngine
import com.example.ai.MahiMood
import com.example.audio.AudioRecordManager
import com.example.audio.MahiSpeechSynthesizer
import com.example.audio.SpeechRecognitionManager
import com.example.tools.MahiToolsManager
import com.example.tools.ToolExecutionResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

enum class AssistantVisualState {
    DISCONNECTED,
    CONNECTING,
    LISTENING,
    THINKING,
    SPEAKING,
    ERROR
}

data class MahiMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

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
    val errorMessage: String? = null
)

class MahiAssistantViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "MahiViewModel"
    }

    private val audioRecordManager = AudioRecordManager()
    private val speechRecognitionManager = SpeechRecognitionManager(application)
    private val speechSynthesizer = MahiSpeechSynthesizer(application)
    private val aiEngine = MahiAiEngine()
    private val toolsManager = MahiToolsManager(application)

    private val _uiState = MutableStateFlow(AssistantUiState())
    val uiState: StateFlow<AssistantUiState> = _uiState.asStateFlow()

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
        } catch (e: Exception) {
            null
        }
    }

    init {
        speechSynthesizer.initialize()

        // Handle interruptions: if user starts speaking while Mahi is talking, interrupt Mahi!
        speechRecognitionManager.onSpeechInterrupted = {
            if (_uiState.value.visualState == AssistantVisualState.SPEAKING) {
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

    private fun observeSpeechRecognition() {
        recognitionCollectJob = viewModelScope.launch {
            // Live partial recognition updates
            launch {
                speechRecognitionManager.partialText.collectLatest { text ->
                    if (text.isNotBlank()) {
                        _uiState.value = _uiState.value.copy(
                            liveUserText = text,
                            visualState = AssistantVisualState.LISTENING
                        )
                    }
                }
            }

            // Final recognition result
            launch {
                speechRecognitionManager.finalText.collectLatest { query ->
                    if (query.isNotBlank()) {
                        processUserQuery(query)
                    }
                }
            }
        }
    }

    private fun observeWaveformVisualizer() {
        waveformCollectJob = viewModelScope.launch {
            // Switch waveform source based on whether user is listening or assistant is speaking
            launch {
                audioRecordManager.waveformBarsFlow.collectLatest { micBars ->
                    if (_uiState.value.visualState == AssistantVisualState.LISTENING) {
                        _uiState.value = _uiState.value.copy(waveformBars = micBars)
                    }
                }
            }
            launch {
                speechSynthesizer.speakingWaveform.collectLatest { ttsBars ->
                    if (_uiState.value.visualState == AssistantVisualState.SPEAKING) {
                        _uiState.value = _uiState.value.copy(waveformBars = ttsBars)
                    }
                }
            }
        }
    }

    fun toggleSession() {
        when (_uiState.value.visualState) {
            AssistantVisualState.DISCONNECTED -> startSession()
            AssistantVisualState.SPEAKING -> interruptAssistant()
            AssistantVisualState.LISTENING -> stopSession()
            AssistantVisualState.THINKING -> stopSession()
            AssistantVisualState.CONNECTING -> stopSession()
            AssistantVisualState.ERROR -> startSession()
        }
    }

    fun startSession() {
        triggerHaptic()
        _uiState.value = _uiState.value.copy(
            visualState = AssistantVisualState.CONNECTING,
            liveUserText = "",
            errorMessage = null
        )

        viewModelScope.launch {
            try {
                audioRecordManager.startListening()
                speechRecognitionManager.startListening()
                _uiState.value = _uiState.value.copy(visualState = AssistantVisualState.LISTENING)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start voice session", e)
                _uiState.value = _uiState.value.copy(
                    visualState = AssistantVisualState.ERROR,
                    errorMessage = "Microphone or speech service unavailable"
                )
            }
        }
    }

    fun stopSession() {
        triggerHaptic()
        speechSynthesizer.stop()
        speechRecognitionManager.stopListening()
        audioRecordManager.stopListening()
        _uiState.value = _uiState.value.copy(
            visualState = AssistantVisualState.DISCONNECTED,
            waveformBars = List(32) { 0.06f }
        )
    }

    fun interruptAssistant() {
        triggerHaptic()
        speechSynthesizer.stop()
        _uiState.value = _uiState.value.copy(
            visualState = AssistantVisualState.LISTENING,
            liveUserText = "Listening..."
        )
        speechRecognitionManager.startListening()
        audioRecordManager.startListening()
    }

    fun processUserQuery(query: String) {
        if (query.isBlank()) return
        triggerHaptic()

        _uiState.value = _uiState.value.copy(
            visualState = AssistantVisualState.THINKING,
            liveUserText = query
        )

        // Stop speech recognition and mic analysis while thinking
        speechRecognitionManager.stopListening()
        audioRecordManager.stopListening()

        viewModelScope.launch {
            try {
                val mood = _uiState.value.currentMood
                val response = aiEngine.sendMessage(query, mood)

                // Execute tool if present
                var toolResult: ToolExecutionResult? = null
                if (response.toolAction != null) {
                    toolResult = toolsManager.execute(response.toolAction)
                    _uiState.value = _uiState.value.copy(lastExecutedTool = toolResult)
                }

                // If tool was changeMood, update local mood
                if (response.toolAction?.name.equals("changeMood", ignoreCase = true)) {
                    val targetMoodStr = response.toolAction?.params?.get("mood")
                    val matchedMood = MahiMood.values().firstOrNull { it.name.equals(targetMoodStr, ignoreCase = true) }
                    if (matchedMood != null) {
                        _uiState.value = _uiState.value.copy(currentMood = matchedMood)
                    }
                }

                _uiState.value = _uiState.value.copy(
                    visualState = AssistantVisualState.SPEAKING,
                    liveAssistantText = response.replyText
                )

                // Speak response with Mahi's voice
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
                    // Muted mode finishes immediately
                    onAssistantDoneSpeaking()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error in query processing", e)
                _uiState.value = _uiState.value.copy(
                    visualState = AssistantVisualState.ERROR,
                    errorMessage = e.localizedMessage ?: "Failed to generate reply"
                )
            }
        }
    }

    private fun onAssistantDoneSpeaking() {
        if (_uiState.value.isContinuousMode) {
            // Seamlessly resume listening in continuous mode
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(
                    visualState = AssistantVisualState.LISTENING,
                    liveUserText = ""
                )
                audioRecordManager.startListening()
                speechRecognitionManager.startListening()
            }
        } else {
            _uiState.value = _uiState.value.copy(
                visualState = AssistantVisualState.DISCONNECTED,
                waveformBars = List(32) { 0.06f }
            )
        }
    }

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
        processUserQuery(moodIntro)
    }

    fun toggleContinuousMode() {
        val next = !_uiState.value.isContinuousMode
        speechRecognitionManager.isContinuousMode = next
        _uiState.value = _uiState.value.copy(isContinuousMode = next)
    }

    fun toggleMute() {
        val next = !_uiState.value.isMuted
        if (next) speechSynthesizer.stop()
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

    private fun triggerHaptic() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(25, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(25)
            }
        } catch (e: Exception) {
            // Ignore vibration errors
        }
    }

    override fun onCleared() {
        super.onCleared()
        audioRecordManager.stopListening()
        speechRecognitionManager.release()
        speechSynthesizer.release()
    }
}
