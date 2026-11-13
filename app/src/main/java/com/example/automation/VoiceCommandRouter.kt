package com.example.automation

import com.example.preferences.AppLanguage

sealed class RoutedVoiceCommand {
    object StopEverything : RoutedVoiceCommand()

    data class LaunchApp(
        val appName: String,
        val followUpGesture: VoiceGestureIntent? = null
    ) : RoutedVoiceCommand()

    data class SearchYouTube(val query: String) : RoutedVoiceCommand()

    data class ExecuteGesture(val gesture: VoiceGestureIntent) : RoutedVoiceCommand()

    data class PlaceCall(val target: String) : RoutedVoiceCommand()
    object AnswerActiveCall : RoutedVoiceCommand()
    object RejectActiveCall : RoutedVoiceCommand()
    object EndActiveCall : RoutedVoiceCommand()
}

data class VoiceGestureIntent(
    val actionName: String,
    val targetText: String = "",
    val speed: GestureSpeed = GestureSpeed.NORMAL,
    val continuous: Boolean = false
)

enum class GestureSpeed(val durationMs: Long, val displayName: String) {
    SLOW(750L, "Slow"),
    NORMAL(300L, "Normal"),
    FAST(130L, "Fast")
}

/**
 * Deterministic Urdu / Roman Urdu / English voice command router + language detector.
 */
object VoiceCommandRouter {

    private val romanUrduTokens = setOf(
        "kholo", "chalao", "karo", "upar", "neeche", "niche", "ruko", "ruk", "jao",
        "band", "bhai", "yaar", "kya", "hai", "haan", "nahi", "batao", "dikhao",
        "iska", "is", "page", "ke", "end", "tak", "yeh", "button", "par", "pe",
        "search", "mera", "mujhe", "kaun", "aap", "tum", "theek", "shukriya", "call"
    )

    fun detectLanguageFromUtterance(text: String, fallback: AppLanguage): AppLanguage {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return fallback

        // Urdu Arabic script block check (\u0600-\u06FF)
        if (trimmed.any { it in '\u0600'..'\u06FF' }) {
            return AppLanguage.URDU
        }

        val words = trimmed.lowercase().split(Regex("[^a-z]+")).filter { it.isNotBlank() }
        val romanHits = words.count { it in romanUrduTokens }
        if (romanHits >= 2 || (words.size <= 3 && romanHits >= 1 && words.any { it in setOf("kholo", "chalao", "karo", "upar", "neeche", "ruko", "ruk", "jao") })) {
            return AppLanguage.ROMAN_URDU
        }

        return if (words.size >= 4 && romanHits == 0) AppLanguage.ENGLISH else fallback
    }

    fun parseCommand(rawInput: String): RoutedVoiceCommand? {
        val clean = rawInput.trim()
        if (clean.isEmpty()) return null
        val cleanNoWake = clean
            .replace(Regex("^(mahi\\s*,?\\s*|ماہی\\s+)", RegexOption.IGNORE_CASE), "")
            .trim()
            .removeSuffix(".")
            .trim()
        val lower = cleanNoWake.lowercase()

        // 1. Immediate STOP / Ruk jao
        if (lower == "stop" || lower == "stop scrolling" || lower == "ruk jao" || lower == "ruko" ||
            lower == "bas" || lower.contains("ruk jao") || lower.contains("stop gesture") ||
            clean.contains("رک جاؤ") || clean.contains("بند کرو")
        ) {
            return RoutedVoiceCommand.StopEverything
        }

        // 2. Call voice commands
        when {
            lower == "answer call" || lower == "pick up" || lower.contains("call uthao") || clean.contains("کال اٹھاؤ") ->
                return RoutedVoiceCommand.AnswerActiveCall
            lower == "reject call" || lower == "decline call" || lower.contains("call kato") || lower.contains("call reject") ->
                return RoutedVoiceCommand.RejectActiveCall
            lower == "end call" || lower == "hang up" || lower.contains("call band karo") ->
                return RoutedVoiceCommand.EndActiveCall
        }

        if (lower.startsWith("call ") || lower.startsWith("dial ") ||
            lower.contains(" ko call karo") || cleanNoWake.contains("کو کال کرو")
        ) {
            val target = when {
                lower.startsWith("call ") -> cleanNoWake.substring(5).trim()
                lower.startsWith("dial ") -> cleanNoWake.substring(5).trim()
                lower.contains(" ko call karo") -> {
                    val idx = cleanNoWake.indexOf(" ko call karo", ignoreCase = true)
                    if (idx > 0) cleanNoWake.substring(0, idx).trim() else ""
                }
                else -> cleanNoWake.substringBefore("کو کال کرو").trim()
            }
            if (target.isNotBlank()) return RoutedVoiceCommand.PlaceCall(target)
        }

        // 3. Compound YouTube search (English / Roman Urdu / Urdu)
        if ((lower.contains("youtube") || clean.contains("یوٹیوب")) &&
            (lower.contains("search") || lower.contains("par ") || lower.contains("pe ") || lower.contains("play ") || clean.contains("سرچ"))
        ) {
            val extractedQuery = lower
                .replace("youtube", "")
                .replace("par", "")
                .replace("pe", "")
                .replace("search", "")
                .replace("karo", "")
                .replace("for", "")
                .replace("on", "")
                .trim()
            if (extractedQuery.isNotBlank() && !lower.contains("chalao") && !lower.contains("open") && !lower.contains("kholo")) {
                return RoutedVoiceCommand.SearchYouTube(extractedQuery)
            }
        }

        // 4. App Launch + optional follow-up gesture (e.g. "Open Facebook and scroll down")
        val launchKeywords = listOf("open ", "launch ", "start ", "kholo", "chalao", "open karo", "کھولو", "چلاؤ")
        if (launchKeywords.any { lower.contains(it) }) {
            val matchedApp = AppLauncherManager.matchKnownApp(clean)
            val followUp = when {
                lower.contains("scroll down") || lower.contains("neeche") ->
                    VoiceGestureIntent("SWIPE_DOWN_SCROLL", speed = GestureSpeed.NORMAL)
                lower.contains("scroll up") || lower.contains("upar") ->
                    VoiceGestureIntent("SWIPE_UP_SCROLL", speed = GestureSpeed.NORMAL)
                else -> null
            }

            if (matchedApp != null) {
                return RoutedVoiceCommand.LaunchApp(matchedApp.canonicalName, followUp)
            }

            val stripped = AppLauncherManager.normalizeAppQuery(
                lower.substringBefore(" and ").substringBefore(" aur ")
            )
            if (stripped.isNotBlank() && !lower.contains("button") && !lower.contains("screen")) {
                return RoutedVoiceCommand.LaunchApp(stripped, followUp)
            }
        }

        // 5. Screen Gesture Engine commands (English, Roman Urdu, Urdu)
        parseGesture(clean, lower)?.let { return RoutedVoiceCommand.ExecuteGesture(it) }

        return null
    }

    private fun parseGesture(clean: String, lower: String): VoiceGestureIntent? {
        val isFast = lower.contains("fast") || lower.contains("tezi") || lower.contains("tez")
        val isSlow = lower.contains("slow") || lower.contains("ahista") || lower.contains("aram")
        val speed = when {
            isFast -> GestureSpeed.FAST
            isSlow -> GestureSpeed.SLOW
            else -> GestureSpeed.NORMAL
        }

        return when {
            lower.contains("continuous") && (lower.contains("up") || lower.contains("upar")) ->
                VoiceGestureIntent("CONTINUOUS_SCROLL_UP", speed = speed, continuous = true)
            lower.contains("continuous") ->
                VoiceGestureIntent("CONTINUOUS_SCROLL_DOWN", speed = speed, continuous = true)
            lower.contains("end tak") || lower.contains("scroll to bottom") || lower.contains("bottom") || lower.contains("aakhir") ->
                VoiceGestureIntent("SCROLL_BOTTOM", speed = GestureSpeed.FAST)
            lower.contains("scroll to top") || lower.contains("shuru") || lower.contains("top") ->
                VoiceGestureIntent("SCROLL_TOP", speed = GestureSpeed.FAST)
            lower.contains("fast scroll") ->
                VoiceGestureIntent("FAST_SCROLL_DOWN", speed = GestureSpeed.FAST)
            lower.contains("screen upar") || lower.contains("swipe up") || lower.contains("scroll up") || clean.contains("اسکرین اوپر") ->
                VoiceGestureIntent("SWIPE_UP", speed = speed)
            lower.contains("screen neeche") || lower.contains("screen niche") || lower.contains("swipe down") || lower.contains("scroll down") || clean.contains("اسکرین نیچے") ->
                VoiceGestureIntent("SWIPE_DOWN", speed = speed)
            lower.contains("swipe left") || lower.contains("bayein") || lower.contains("left") ->
                VoiceGestureIntent("SWIPE_LEFT", speed = speed)
            lower.contains("swipe right") || lower.contains("dayein") || lower.contains("right") ->
                VoiceGestureIntent("SWIPE_RIGHT", speed = speed)
            lower.contains("double tap") -> {
                val target = lower.substringAfter("double tap").replace("on ", "").trim()
                VoiceGestureIntent("DOUBLE_TAP", targetText = target)
            }
            lower.contains("long press") -> {
                val target = lower.substringAfter("long press").replace("on ", "").trim()
                VoiceGestureIntent("LONG_PRESS", targetText = target)
            }
            lower.contains("yeh button click") || lower.contains("button click") || lower.startsWith("tap ") || lower.startsWith("click ") -> {
                val target = lower
                    .replace("yeh button click karo", "")
                    .replace("button click karo", "")
                    .replace("click karo", "")
                    .replace("tap ", "")
                    .replace("click ", "")
                    .trim()
                VoiceGestureIntent("TAP_TEXT", targetText = target)
            }
            else -> null
        }
    }
}
