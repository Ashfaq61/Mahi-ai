package com.example.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

class SpeechRecognitionManager(private val context: Context) {
    companion object {
        private const val TAG = "SpeechRecognitionMgr"
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var desiredListening = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var restartJob: Job? = null

    private val _isActivelyListening = MutableStateFlow(false)
    val isActivelyListening: StateFlow<Boolean> = _isActivelyListening.asStateFlow()

    private val _partialText = MutableStateFlow("")
    val partialText: StateFlow<String> = _partialText.asStateFlow()

    private val _finalText = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val finalText: SharedFlow<String> = _finalText.asSharedFlow()

    private val _soundLevel = MutableStateFlow(0f)
    val soundLevel: StateFlow<Float> = _soundLevel.asStateFlow()

    private val _isUserSpeaking = MutableStateFlow(false)
    val isUserSpeaking: StateFlow<Boolean> = _isUserSpeaking.asStateFlow()

    private val _lastErrorMessage = MutableStateFlow<String?>(null)
    val lastErrorMessage: StateFlow<String?> = _lastErrorMessage.asStateFlow()

    var onSpeechInterrupted: (() -> Unit)? = null
    var isContinuousMode: Boolean = true
    var preferredLanguageTag: String = "en-US"

    fun initialize(): Boolean {
        if (speechRecognizer != null) return true
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(createListener())
            }
            return true
        }
        Log.w(TAG, "Speech recognition is not available on this device")
        _lastErrorMessage.value = "Speech recognition service unavailable on this device."
        return false
    }

    private fun createListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                _isActivelyListening.value = true
                _isUserSpeaking.value = false
                _lastErrorMessage.value = null
            }

            override fun onBeginningOfSpeech() {
                _isUserSpeaking.value = true
                onSpeechInterrupted?.invoke()
            }

            override fun onRmsChanged(rmsdB: Float) {
                val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                _soundLevel.value = normalized
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                _isUserSpeaking.value = false
            }

            override fun onError(error: Int) {
                val errorMessage = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                    SpeechRecognizer.ERROR_CLIENT -> "Client error"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required"
                    SpeechRecognizer.ERROR_NETWORK -> "Speech network error"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech network timeout"
                    SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                    SpeechRecognizer.ERROR_SERVER -> "Server error"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
                    else -> "Speech recognition error ($error)"
                }
                _isUserSpeaking.value = false
                _isActivelyListening.value = false

                if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    desiredListening = false
                    _lastErrorMessage.value = errorMessage
                    return
                }

                val recoverable = error == SpeechRecognizer.ERROR_NO_MATCH ||
                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                    error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                    error == SpeechRecognizer.ERROR_CLIENT

                if (desiredListening && isContinuousMode && recoverable) {
                    scheduleRestart(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 380L else 180L)
                } else if (!recoverable) {
                    _lastErrorMessage.value = errorMessage
                }
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val recognized = matches?.firstOrNull()?.trim()
                _isUserSpeaking.value = false
                _isActivelyListening.value = false
                if (!recognized.isNullOrBlank()) {
                    _partialText.value = recognized
                    _finalText.tryEmit(recognized)
                } else if (desiredListening && isContinuousMode) {
                    scheduleRestart(180L)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull()?.trim()
                if (!text.isNullOrBlank()) {
                    _partialText.value = text
                    onSpeechInterrupted?.invoke()
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }

    fun startListening(): Boolean {
        restartJob?.cancel()
        if (!initialize()) return false
        desiredListening = true
        _partialText.value = ""
        _lastErrorMessage.value = null

        val locale = Locale.forLanguageTag(preferredLanguageTag)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, locale.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1600L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }

        return try {
            speechRecognizer?.startListening(intent)
            _isActivelyListening.value = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start speech listening", e)
            _isActivelyListening.value = false
            _lastErrorMessage.value = e.localizedMessage
            false
        }
    }

    private fun scheduleRestart(delayMs: Long) {
        restartJob?.cancel()
        restartJob = scope.launch {
            delay(delayMs)
            if (!desiredListening) return@launch
            try {
                speechRecognizer?.cancel()
            } catch (_: Exception) {
            }
            startListening()
        }
    }

    fun stopListening() {
        desiredListening = false
        restartJob?.cancel()
        restartJob = null
        _isUserSpeaking.value = false
        _isActivelyListening.value = false
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping SpeechRecognizer", e)
        }
    }

    fun release() {
        stopListening()
        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {
        }
        speechRecognizer = null
    }
}
