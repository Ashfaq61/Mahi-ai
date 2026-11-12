package com.example.audio

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sin

/**
 * Manages TextToSpeech tuned specifically for Mahi's witty, sassy, female persona.
 * Generates synchronized live audio visualizer pulses during speech.
 */
class MahiSpeechSynthesizer(private val context: Context) {
    companion object {
        private const val TAG = "MahiSpeechSynthesizer"
    }

    private var tts: TextToSpeech? = null
    private var isInitialized = false

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _speakingWaveform = MutableStateFlow(List(32) { 0.05f })
    val speakingWaveform: StateFlow<List<Float>> = _speakingWaveform.asStateFlow()

    var onSpeechCompleted: (() -> Unit)? = null
    var onSpeechStarted: (() -> Unit)? = null

    var pitch: Float = 1.20f // Confident, young female voice pitch
    var speechRate: Float = 1.08f // Snappy, casual, lively cadence

    private var animationJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    fun initialize(onReady: () -> Unit = {}) {
        if (tts != null && isInitialized) {
            onReady()
            return
        }

        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isInitialized = true
                configureVoice()
                setupProgressListener()
                onReady()
                Log.d(TAG, "Mahi TTS initialized successfully")
            } else {
                Log.e(TAG, "TTS Initialization failed with status: $status")
            }
        }
    }

    private fun configureVoice() {
        tts?.let { engine ->
            val locale = Locale.US
            engine.language = locale
            engine.setPitch(pitch)
            engine.setSpeechRate(speechRate)

            // Try to find a natural female voice
            try {
                val voices = engine.voices
                if (voices != null) {
                    val femaleVoice = voices.firstOrNull { voice ->
                        voice.locale.language == locale.language &&
                                (voice.name.contains("female", ignoreCase = true) ||
                                        voice.name.contains("en-us-x-sfg", ignoreCase = true) ||
                                        voice.name.contains("en-us-x-iol", ignoreCase = true) ||
                                        !voice.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED))
                    }
                    if (femaleVoice != null) {
                        engine.voice = femaleVoice
                        Log.d(TAG, "Selected voice: ${femaleVoice.name}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not enumerate voices", e)
            }
        }
    }

    private fun setupProgressListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                _isSpeaking.value = true
                startWaveformAnimation()
                onSpeechStarted?.invoke()
            }

            override fun onDone(utteranceId: String?) {
                _isSpeaking.value = false
                stopWaveformAnimation()
                onSpeechCompleted?.invoke()
            }

            override fun onError(utteranceId: String?) {
                _isSpeaking.value = false
                stopWaveformAnimation()
                onSpeechCompleted?.invoke()
            }
        })
    }

    fun speak(text: String, onStart: () -> Unit = {}, onDone: () -> Unit = {}) {
        if (!isInitialized || tts == null) {
            initialize {
                speak(text, onStart, onDone)
            }
            return
        }

        stop() // Stop any previous speech
        this.onSpeechStarted = onStart
        this.onSpeechCompleted = onDone

        // Clean text from tool tags or markdown before speech
        val cleanText = text
            .replace(Regex("\\[TOOL:[^\\]]+\\]"), "")
            .replace("*", "")
            .replace("#", "")
            .replace("`", "")
            .trim()

        if (cleanText.isBlank()) {
            onDone()
            return
        }

        tts?.setPitch(pitch)
        tts?.setSpeechRate(speechRate)

        val utteranceId = UUID.randomUUID().toString()
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }

        tts?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
    }

    private fun startWaveformAnimation() {
        animationJob?.cancel()
        animationJob = scope.launch {
            var phase = 0.0
            while (isActive && _isSpeaking.value) {
                phase += 0.25
                val newBars = List(32) { i ->
                    val sin1 = abs(sin(phase + i * 0.35).toFloat())
                    val sin2 = abs(sin(phase * 1.4 - i * 0.2).toFloat())
                    val centerFactor = 1.0f - abs(i - 16) / 16f
                    val amp = (0.35f * sin1 + 0.65f * sin2) * (0.5f + 0.5f * centerFactor)
                    (0.12f + amp * 0.88f).coerceIn(0.08f, 1.0f)
                }
                _speakingWaveform.value = newBars
                delay(35) // ~30 FPS fluid update
            }
        }
    }

    private fun stopWaveformAnimation() {
        animationJob?.cancel()
        animationJob = null
        _speakingWaveform.value = List(32) { 0.05f }
    }

    fun stop() {
        try {
            tts?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping TTS", e)
        }
        _isSpeaking.value = false
        stopWaveformAnimation()
    }

    fun release() {
        stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }
}
