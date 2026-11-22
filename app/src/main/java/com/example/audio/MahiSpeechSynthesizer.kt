package com.example.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.example.preferences.AppLanguage
import com.example.preferences.MahiVoiceGender
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID

data class EffectiveTtsParams(
    val speechRate: Float,
    val pitch: Float
)

/**
 * Android TextToSpeech synthesizer for offline fallback, Settings voice previews, and Call Speaker Bridge.
 * Note: Active Gemini Live sessions speak natively over WebSocket PCM AudioTrack (Gemini Live audio);
 * local TTS never replaces Gemini Live.
 */
class MahiSpeechSynthesizer(context: Context) : TextToSpeech.OnInitListener {
    companion object {
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 2.0f

        /**
         * Keeps speech crisp and natural at higher speeds (>=1.35x) by applying gentle inverse
         * pitch compensation + safe rate clamping so Android TTS doesn't sound garbled/chipmunk-like.
         */
        fun computeEffectiveTtsParams(
            requestedSpeed: Float,
            requestedPitch: Float,
            clarityCompensation: Boolean
        ): EffectiveTtsParams {
            val safeRate = requestedSpeed.coerceIn(MIN_SPEED, MAX_SPEED)
            val basePitch = requestedPitch.coerceIn(0.65f, 1.5f)
            val adjustedPitch = if (clarityCompensation && safeRate > 1.30f) {
                val excess = (safeRate - 1.30f) / (MAX_SPEED - 1.30f)
                (basePitch * (1.0f - excess * 0.14f)).coerceIn(0.82f, 1.25f)
            } else {
                basePitch
            }
            return EffectiveTtsParams(speechRate = safeRate, pitch = adjustedPitch)
        }

        fun prepareIntelligibleText(rawText: String, speechRate: Float): String {
            var cleaned = rawText
                .replace("—", ", ")
                .trim()
            if (speechRate >= 1.55f) {
                // Insert tiny breathing commas around long clauses so fast TTS articulates clearly
                cleaned = cleaned.replace(";", ", ")
            }
            return cleaned.replace(Regex("\\s+"), " ").trim()
        }
    }

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext, this)
    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private var currentPitch: Float = 1.05f
    private var currentSpeed: Float = 1.05f
    private var currentClarity: Boolean = true
    private var currentGender: MahiVoiceGender = MahiVoiceGender.FEMALE
    private var currentLanguage: AppLanguage = AppLanguage.ENGLISH

    var onDoneSpeaking: (() -> Unit)? = null

    fun updateVoiceConfig(
        pitch: Float = currentPitch,
        speed: Float = currentSpeed,
        clarityCompensation: Boolean = currentClarity,
        gender: MahiVoiceGender = currentGender,
        language: AppLanguage = currentLanguage
    ) {
        currentPitch = pitch.coerceIn(0.6f, 1.6f)
        currentSpeed = speed.coerceIn(MIN_SPEED, MAX_SPEED)
        currentClarity = clarityCompensation
        currentGender = gender
        currentLanguage = language
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            _isInitialized.value = true
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    _isSpeaking.value = true
                }

                override fun onDone(utteranceId: String?) {
                    _isSpeaking.value = false
                    onDoneSpeaking?.invoke()
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    _isSpeaking.value = false
                    onDoneSpeaking?.invoke()
                }
            })
        }
    }

    fun speak(
        text: String,
        language: AppLanguage = currentLanguage,
        gender: MahiVoiceGender = currentGender,
        speed: Float = currentSpeed,
        pitch: Float = currentPitch,
        clarityCompensation: Boolean = currentClarity
    ) {
        if (!_isInitialized.value || text.isBlank()) return
        val params = computeEffectiveTtsParams(speed, pitch, clarityCompensation)
        val locale = Locale.forLanguageTag(language.bcp47)
        tts.language = locale
        selectPreferredVoice(locale, gender)?.let { tts.voice = it }
        tts.setSpeechRate(params.speechRate)
        tts.setPitch(params.pitch)
        val prepared = prepareIntelligibleText(text, params.speechRate)
        tts.speak(prepared, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
    }

    private fun selectPreferredVoice(locale: Locale, gender: MahiVoiceGender): Voice? {
        val voices = runCatching { tts.voices }.getOrNull().orEmpty()
        if (voices.isEmpty()) return null
        val sameLang = voices.filter { it.locale.language.equals(locale.language, ignoreCase = true) }
        val pool = sameLang.ifEmpty { voices.toList() }
        val hint = if (gender == MahiVoiceGender.MALE) listOf("male", "-m-", "iom", "rjs", "charon") else listOf("female", "-f-", "iob", "sfg", "aoede")
        return pool.firstOrNull { v -> hint.any { h -> v.name.contains(h, ignoreCase = true) } } ?: pool.firstOrNull()
    }

    fun stop() {
        _isSpeaking.value = false
        runCatching { tts.stop() }
    }

    fun shutdown() {
        stop()
        runCatching { tts.shutdown() }
    }
}
