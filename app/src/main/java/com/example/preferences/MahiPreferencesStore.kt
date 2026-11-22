package com.example.preferences

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AppLanguage(val displayName: String, val bcp47: String, val instruction: String) {
    ENGLISH(
        displayName = "English",
        bcp47 = "en-US",
        instruction = "Respond clearly and naturally in English."
    ),
    ROMAN_URDU(
        displayName = "Roman Urdu",
        bcp47 = "ur-PK",
        instruction = "Respond clearly in natural Roman Urdu / Hinglish (Latin script)."
    ),
    URDU(
        displayName = "اردو (Urdu)",
        bcp47 = "ur-PK",
        instruction = "Respond clearly in natural Urdu script (اردو)."
    ),
    HINDI(
        displayName = "हिन्दी (Hindi)",
        bcp47 = "hi-IN",
        instruction = "Respond clearly in natural Hindi (हिन्दी)."
    )
}

enum class MahiVoiceGender(val displayName: String, val geminiVoiceName: String) {
    FEMALE("Female (Aoede)", "Aoede"),
    MALE("Male (Charon)", "Charon")
}

data class MahiUserPreferences(
    val language: AppLanguage = AppLanguage.ENGLISH,
    val voiceGender: MahiVoiceGender = MahiVoiceGender.FEMALE,
    val voicePitch: Float = 1.05f,
    val voiceSpeed: Float = 1.05f,
    val ttsClarityCompensation: Boolean = true,
    val isContinuousMode: Boolean = true,
    val showSubtitles: Boolean = true,
    val darkTheme: Boolean = true,
    val autopilotEnabled: Boolean = false,
    val wakeDetectionEnabled: Boolean = false,
    val preferGeminiLiveVoice: Boolean = true,
    val memoryConsentGranted: Boolean = false,
    val onboardingCompleted: Boolean = false,
    val lastExternalTargetPackage: String = ""
)

class MahiPreferencesStore(context: Context) {
    companion object {
        private const val PREFS_NAME = "mahi_preferences_store"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_VOICE_GENDER = "voice_gender"
        private const val KEY_VOICE_PITCH = "voice_pitch"
        private const val KEY_VOICE_SPEED = "voice_speed"
        private const val KEY_TTS_CLARITY = "tts_clarity_comp"
        private const val KEY_CONTINUOUS = "continuous_mode"
        private const val KEY_SUBTITLES = "show_subtitles"
        private const val KEY_DARK_THEME = "dark_theme"
        private const val KEY_AUTOPILOT = "autopilot_enabled"
        private const val KEY_WAKE = "wake_detection_enabled"
        private const val KEY_PREFER_GEMINI_LIVE = "prefer_gemini_live"
        private const val KEY_MEMORY_CONSENT = "memory_consent_granted"
        private const val KEY_ONBOARDING = "onboarding_completed"
        private const val KEY_LAST_EXTERNAL_TARGET_PACKAGE = "last_external_target_package"

        fun isValidExternalTargetPackage(pkg: String?, ownPackageName: String): Boolean {
            val clean = pkg?.trim().orEmpty()
            if (clean.isBlank()) return false
            if (clean.equals(ownPackageName, ignoreCase = true)) return false
            val lower = clean.lowercase()
            if (lower == "com.android.systemui" || lower.contains("launcher") || lower.contains("inputmethod")) {
                return false
            }
            return true
        }
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _prefsFlow = MutableStateFlow(readPrefs())
    val prefsFlow: StateFlow<MahiUserPreferences> = _prefsFlow.asStateFlow()

    private fun readPrefs(): MahiUserPreferences {
        val lang = runCatching {
            AppLanguage.valueOf(prefs.getString(KEY_LANGUAGE, AppLanguage.ENGLISH.name) ?: AppLanguage.ENGLISH.name)
        }.getOrDefault(AppLanguage.ENGLISH)
        val gender = runCatching {
            MahiVoiceGender.valueOf(prefs.getString(KEY_VOICE_GENDER, MahiVoiceGender.FEMALE.name) ?: MahiVoiceGender.FEMALE.name)
        }.getOrDefault(MahiVoiceGender.FEMALE)
        return MahiUserPreferences(
            language = lang,
            voiceGender = gender,
            voicePitch = prefs.getFloat(KEY_VOICE_PITCH, 1.05f).coerceIn(0.6f, 1.6f),
            voiceSpeed = prefs.getFloat(KEY_VOICE_SPEED, 1.05f).coerceIn(0.5f, 2.0f),
            ttsClarityCompensation = prefs.getBoolean(KEY_TTS_CLARITY, true),
            isContinuousMode = prefs.getBoolean(KEY_CONTINUOUS, true),
            showSubtitles = prefs.getBoolean(KEY_SUBTITLES, true),
            darkTheme = prefs.getBoolean(KEY_DARK_THEME, true),
            autopilotEnabled = prefs.getBoolean(KEY_AUTOPILOT, false),
            wakeDetectionEnabled = prefs.getBoolean(KEY_WAKE, false),
            preferGeminiLiveVoice = prefs.getBoolean(KEY_PREFER_GEMINI_LIVE, true),
            memoryConsentGranted = prefs.getBoolean(KEY_MEMORY_CONSENT, false),
            onboardingCompleted = prefs.getBoolean(KEY_ONBOARDING, false),
            lastExternalTargetPackage = prefs.getString(KEY_LAST_EXTERNAL_TARGET_PACKAGE, "").orEmpty()
        )
    }

    private fun commitUpdate(mutator: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(mutator).commit()
        _prefsFlow.value = readPrefs()
    }

    fun setLanguage(language: AppLanguage) = commitUpdate { putString(KEY_LANGUAGE, language.name) }
    fun setVoiceGender(gender: MahiVoiceGender) = commitUpdate { putString(KEY_VOICE_GENDER, gender.name) }
    fun setVoicePitch(pitch: Float) = commitUpdate { putFloat(KEY_VOICE_PITCH, pitch.coerceIn(0.6f, 1.6f)) }
    fun setVoiceSpeed(speed: Float) = commitUpdate { putFloat(KEY_VOICE_SPEED, speed.coerceIn(0.5f, 2.0f)) }
    fun setTtsClarityCompensation(enabled: Boolean) = commitUpdate { putBoolean(KEY_TTS_CLARITY, enabled) }
    fun setContinuousMode(enabled: Boolean) = commitUpdate { putBoolean(KEY_CONTINUOUS, enabled) }
    fun setShowSubtitles(show: Boolean) = commitUpdate { putBoolean(KEY_SUBTITLES, show) }
    fun setDarkTheme(dark: Boolean) = commitUpdate { putBoolean(KEY_DARK_THEME, dark) }
    fun setAutopilotEnabled(enabled: Boolean) = commitUpdate { putBoolean(KEY_AUTOPILOT, enabled) }
    fun setWakeDetectionEnabled(enabled: Boolean) = commitUpdate { putBoolean(KEY_WAKE, enabled) }
    fun setPreferGeminiLiveVoice(prefer: Boolean) = commitUpdate { putBoolean(KEY_PREFER_GEMINI_LIVE, prefer) }
    fun setMemoryConsentGranted(granted: Boolean) = commitUpdate { putBoolean(KEY_MEMORY_CONSENT, granted) }
    fun setOnboardingCompleted(done: Boolean) = commitUpdate { putBoolean(KEY_ONBOARDING, done) }

    fun getLastExternalTargetPackage(): String = _prefsFlow.value.lastExternalTargetPackage

    fun setLastExternalTargetPackage(packageName: String) {
        val clean = packageName.trim()
        if (clean.isBlank()) return
        commitUpdate { putString(KEY_LAST_EXTERNAL_TARGET_PACKAGE, clean) }
    }

    fun clearLastExternalTargetPackage() {
        commitUpdate { remove(KEY_LAST_EXTERNAL_TARGET_PACKAGE) }
    }
}
