package com.example.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.example.preferences.AppLanguage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SpeechRecognitionManager(private val context: Context) {
    private var recognizer: SpeechRecognizer? = null

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _partialText = MutableStateFlow("")
    val partialText: StateFlow<String> = _partialText.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    var onFinalResult: ((String) -> Unit)? = null
    var onErrorRecover: ((Int, String) -> Unit)? = null
    var onRmsChangedNormalized: ((Float) -> Unit)? = null

    fun startListening(language: AppLanguage = AppLanguage.ENGLISH): Boolean {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            _lastError.value = "Android SpeechRecognizer unavailable on this device."
            return false
        }
        stopListening()
        _lastError.value = null
        return try {
            val sr = SpeechRecognizer.createSpeechRecognizer(context.applicationContext)
            sr.setRecognitionListener(createListener())
            recognizer = sr
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.bcp47)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            }
            sr.startListening(intent)
            _isListening.value = true
            true
        } catch (e: Exception) {
            _lastError.value = e.localizedMessage ?: "Failed to start SpeechRecognizer"
            _isListening.value = false
            false
        }
    }

    private fun createListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { _isListening.value = true }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {
            onRmsChangedNormalized?.invoke(((rmsdB + 2f) / 12f).coerceIn(0.05f, 1f))
        }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { _isListening.value = false }
        override fun onError(error: Int) {
            _isListening.value = false
            val msg = when (error) {
                SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required"
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network error in speech recognizer"
                SpeechRecognizer.ERROR_NO_MATCH -> "No speech heard"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
                else -> "Speech recognizer error ($error)"
            }
            _lastError.value = msg
            onErrorRecover?.invoke(error, msg)
        }
        override fun onResults(results: Bundle?) {
            _isListening.value = false
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            _partialText.value = text
            if (text.isNotBlank()) onFinalResult?.invoke(text)
        }
        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (text.isNotBlank()) _partialText.value = text
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    fun stopListening() {
        _isListening.value = false
        runCatching {
            recognizer?.stopListening()
            recognizer?.destroy()
        }
        recognizer = null
    }
}
