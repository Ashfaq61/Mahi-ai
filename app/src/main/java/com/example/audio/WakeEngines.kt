package com.example.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AutopilotStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == MahiVoiceForegroundService.ACTION_STOP_AUTOPILOT) {
            MahiVoiceForegroundService.clearAutopilotNotification(context)
            MahiVoiceForegroundService.onStopAutopilotRequested?.invoke()
        }
    }
}

class WakeEnrollmentStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("mahi_wake_enroll", Context.MODE_PRIVATE)

    fun getWakePhrase(): String = prefs.getString("wake_phrase", "Hey Mahi").orEmpty().ifBlank { "Hey Mahi" }

    fun setWakePhrase(phrase: String) {
        val clean = phrase.trim().ifBlank { "Hey Mahi" }
        prefs.edit().putString("wake_phrase", clean).commit()
    }
}

/**
 * Coordinates opt-in offline wake-word activation while strictly honoring single-mic arbitration:
 * active live voice pauses wake capture before opening Gemini Live's AudioRecord.
 */
class WakeWordEngine(
    private val context: Context,
    private val enrollmentStore: WakeEnrollmentStore,
    private val modelManager: WakeModelManager
) {
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private var speechHelper: SpeechRecognitionManager? = null
    var onWakePhraseDetected: ((String) -> Unit)? = null

    fun startWakeListening(): Boolean {
        if (!modelManager.isModelReady()) {
            _lastError.value = "Offline wake model (~40 MB) not downloaded yet; download in Settings first."
            _isListening.value = false
            return false
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            _lastError.value = "On-device speech recognizer unavailable on this device."
            _isListening.value = false
            return false
        }
        _lastError.value = null
        val targetPhrase = enrollmentStore.getWakePhrase().lowercase()
        val mgr = speechHelper ?: SpeechRecognitionManager(context).also { speechHelper = it }
        mgr.onFinalResult = { transcript ->
            val clean = transcript.lowercase()
            if (clean.contains(targetPhrase) || clean.contains("mahi") || clean.contains("hey mahi")) {
                _isListening.value = false
                onWakePhraseDetected?.invoke(transcript)
            } else if (_isListening.value) {
                mgr.startListening()
            }
        }
        mgr.onErrorRecover = { _, _ ->
            _isListening.value = false
        }
        val started = mgr.startListening()
        _isListening.value = started
        return started
    }

    fun pauseForActiveVoice() {
        _isListening.value = false
        speechHelper?.stopListening()
    }

    fun stopAll() {
        pauseForActiveVoice()
        speechHelper = null
    }
}
