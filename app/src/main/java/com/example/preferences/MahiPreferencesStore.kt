package com.example.preferences

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AppLanguage(
    val id: String,
    val displayName: String,
    val shortBadge: String,
    val ttsLanguageTag: String,
    val instruction: String
) {
    ENGLISH(
        id = "en",
        displayName = "English",
        shortBadge = "EN",
        ttsLanguageTag = "en-US",
        instruction = "Respond in crisp, natural English."
    ),
    ROMAN_URDU(
        id = "roman_ur",
        displayName = "Roman Urdu",
        shortBadge = "ROM",
        ttsLanguageTag = "en-US",
        instruction = "Respond in natural conversational Roman Urdu (Urdu written in Latin script, e.g. 'Yeh aap ke saamne ek laptop hai')."
    ),
    URDU(
        id = "ur",
        displayName = "Urdu (اردو)",
        shortBadge = "اردو",
        ttsLanguageTag = "ur-PK",
        instruction = "Respond in fluent, natural Urdu script (اردو)."
    );

    companion object {
        fun fromId(id: String?): AppLanguage =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: ENGLISH
    }
}

enum class AppThemeMode(val id: String, val displayName: String) {
    DARK("dark", "Cyber Dark"),
    LIGHT("light", "Luminous Light"),
    SYSTEM("system", "System");

    companion object {
        fun fromId(id: String?): AppThemeMode =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: DARK
    }
}

data class MahiUserPrefs(
    val language: AppLanguage = AppLanguage.ENGLISH,
    val themeMode: AppThemeMode = AppThemeMode.DARK,
    val autoLanguageDetection: Boolean = true,
    val pushToTalkMode: Boolean = false
)

class MahiPreferencesStore(context: Context) {
    companion object {
        private const val PREFS_NAME = "mahi_app_prefs"
        private const val KEY_LANGUAGE = "app_language"
        private const val KEY_THEME_MODE = "app_theme_mode"
        private const val KEY_AUTO_LANGUAGE = "auto_language_detection"
        private const val KEY_PUSH_TO_TALK = "push_to_talk_mode"
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _prefsFlow = MutableStateFlow(load())
    val prefsFlow: StateFlow<MahiUserPrefs> = _prefsFlow.asStateFlow()

    private fun load(): MahiUserPrefs {
        val lang = AppLanguage.fromId(prefs.getString(KEY_LANGUAGE, AppLanguage.ENGLISH.id))
        val theme = AppThemeMode.fromId(prefs.getString(KEY_THEME_MODE, AppThemeMode.DARK.id))
        val autoLang = prefs.getBoolean(KEY_AUTO_LANGUAGE, true)
        val ptt = prefs.getBoolean(KEY_PUSH_TO_TALK, false)
        return MahiUserPrefs(
            language = lang,
            themeMode = theme,
            autoLanguageDetection = autoLang,
            pushToTalkMode = ptt
        )
    }

    fun setLanguage(language: AppLanguage) {
        prefs.edit().putString(KEY_LANGUAGE, language.id).commit()
        _prefsFlow.value = _prefsFlow.value.copy(language = language)
    }

    fun setThemeMode(themeMode: AppThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, themeMode.id).commit()
        _prefsFlow.value = _prefsFlow.value.copy(themeMode = themeMode)
    }

    fun setAutoLanguageDetection(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_LANGUAGE, enabled).commit()
        _prefsFlow.value = _prefsFlow.value.copy(autoLanguageDetection = enabled)
    }

    fun setPushToTalkMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PUSH_TO_TALK, enabled).commit()
        _prefsFlow.value = _prefsFlow.value.copy(pushToTalkMode = enabled)
    }
}
