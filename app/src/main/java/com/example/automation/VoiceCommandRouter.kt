package com.example.automation

import com.example.camera.CameraLensFacing
import com.example.preferences.AppLanguage

sealed class RoutedVoiceCommand {
    object StopEverything : RoutedVoiceCommand()

    data class LaunchApp(
        val appName: String,
        val followUpGesture: VoiceGestureIntent? = null
    ) : RoutedVoiceCommand()

    object OpenGoogle : RoutedVoiceCommand()
    data class GoogleSearch(val query: String, val summarizeInLanguage: Boolean = false) : RoutedVoiceCommand()
    data class SearchYouTube(val query: String) : RoutedVoiceCommand()
    data class OpenWebsite(val url: String) : RoutedVoiceCommand()

    data class OpenCamera(val lens: CameraLensFacing = CameraLensFacing.BACK) : RoutedVoiceCommand()
    data class SwitchCamera(val lens: CameraLensFacing) : RoutedVoiceCommand()
    data class CapturePhoto(val isSelfie: Boolean = false) : RoutedVoiceCommand()
    object StartVideoRecording : RoutedVoiceCommand()
    object StopVideoRecording : RoutedVoiceCommand()
    data class SetCameraFlash(val enabled: Boolean) : RoutedVoiceCommand()
    data class SetCameraZoom(val deltaStep: Float) : RoutedVoiceCommand()

    data class ExecuteGesture(val gesture: VoiceGestureIntent) : RoutedVoiceCommand()

    data class PlaceCall(val target: String) : RoutedVoiceCommand()
    object AnswerActiveCall : RoutedVoiceCommand()
    object RejectActiveCall : RoutedVoiceCommand()
    object EndActiveCall : RoutedVoiceCommand()
    data class ReadNotifications(val whatsAppOnly: Boolean = false) : RoutedVoiceCommand()
    data class ReplyWhatsApp(val senderHint: String, val message: String) : RoutedVoiceCommand()

    // Part 2: Smart Intelligence & Vision
    data class AnalyzeScreen(val question: String = "") : RoutedVoiceCommand()
    data class AnalyzeScreenshot(val question: String = "") : RoutedVoiceCommand()
    data class DescribeCameraVision(val question: String = "") : RoutedVoiceCommand()
    object AnalyzeNotifications : RoutedVoiceCommand()

    // Part 4: Smart Smartphone Control & Chained Commands
    data class SmartControl(val rawPhrase: String, val chainedSteps: List<String> = emptyList()) : RoutedVoiceCommand()

    fun deduplicationKey(): String = when (this) {
        StopEverything -> "STOP"
        is LaunchApp -> "LAUNCH:${appName.lowercase()}:${followUpGesture?.actionName.orEmpty()}"
        OpenGoogle -> "OPEN_GOOGLE"
        is GoogleSearch -> "GOOGLE_SEARCH:${query.lowercase()}"
        is SearchYouTube -> "YT_SEARCH:${query.lowercase()}"
        is OpenWebsite -> "OPEN_SITE:${url.lowercase()}"
        is OpenCamera -> "OPEN_CAM:${lens.name}"
        is SwitchCamera -> "SWITCH_CAM:${lens.name}"
        is CapturePhoto -> "CAPTURE_PHOTO:$isSelfie"
        StartVideoRecording -> "START_VIDEO"
        StopVideoRecording -> "STOP_VIDEO"
        is SetCameraFlash -> "SET_FLASH:$enabled"
        is SetCameraZoom -> "SET_ZOOM:$deltaStep"
        is ExecuteGesture -> "GESTURE:${gesture.actionName}:${gesture.targetText}"
        is PlaceCall -> "CALL:${target.lowercase()}"
        AnswerActiveCall -> "ANSWER_CALL"
        RejectActiveCall -> "REJECT_CALL"
        EndActiveCall -> "END_CALL"
        is ReadNotifications -> "READ_NOTIFS:$whatsAppOnly"
        is ReplyWhatsApp -> "REPLY_WA:${senderHint.lowercase()}:${message.lowercase()}"
        is AnalyzeScreen -> "VISION_SCREEN:${question.lowercase()}"
        is AnalyzeScreenshot -> "VISION_SHOT:${question.lowercase()}"
        is DescribeCameraVision -> "VISION_CAM:${question.lowercase()}"
        AnalyzeNotifications -> "NOTIF_DIGEST"
        is SmartControl -> "SMART_CTRL:${rawPhrase.lowercase()}"
    }
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
 * Prevents duplicate rapid executions across streaming speech transcripts,
 * Gemini Live tool calls, and repeat triggers within a cooldown window.
 */
class CommandExecutionGuard(
    private val dedupWindowMs: Long = 2_500L,
    private val clockMs: () -> Long = { System.currentTimeMillis() }
) {
    private var lastSignature: String? = null
    private var lastExecutedAtMs: Long = 0L

    @Synchronized
    fun shouldExecute(signature: String): Boolean {
        if (signature == "STOP" || signature == "STOP_VIDEO") {
            lastSignature = signature
            lastExecutedAtMs = clockMs()
            return true
        }
        val now = clockMs()
        if (lastSignature == signature && (now - lastExecutedAtMs) < dedupWindowMs) {
            return false
        }
        lastSignature = signature
        lastExecutedAtMs = now
        return true
    }

    @Synchronized
    fun reset() {
        lastSignature = null
        lastExecutedAtMs = 0L
    }
}

/**
 * Deterministic Urdu / Roman Urdu / Hindi / English voice command router + language detector.
 */
object VoiceCommandRouter {

    private val romanUrduTokens = setOf(
        "kholo", "chalao", "karo", "upar", "neeche", "niche", "ruko", "ruk", "jao",
        "band", "bhai", "yaar", "kya", "hai", "haan", "nahi", "batao", "dikhao",
        "iska", "is", "page", "ke", "end", "tak", "yeh", "button", "par", "pe",
        "search", "mera", "mujhe", "kaun", "aap", "tum", "theek", "shukriya", "call",
        "dhoondo", "khaincho", "lo", "mein"
    )

    fun detectLanguageFromUtterance(text: String, fallback: AppLanguage): AppLanguage {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return fallback

        if (trimmed.any { it in '\u0600'..'\u06FF' }) {
            return AppLanguage.URDU
        }

        val words = trimmed.lowercase().split(Regex("[^a-z]+")).filter { it.isNotBlank() }
        val romanHits = words.count { it in romanUrduTokens }
        if (romanHits >= 2 || (words.size <= 3 && romanHits >= 1 && words.any {
                it in setOf("kholo", "chalao", "karo", "upar", "neeche", "ruko", "ruk", "jao", "dhoondo", "khaincho")
            })
        ) {
            return AppLanguage.ROMAN_URDU
        }

        return if (words.size >= 4 && romanHits == 0) AppLanguage.ENGLISH else fallback
    }

    /**
     * Checks whether a streaming partial transcript is already safe to evaluate after debounce
     * vs clearly incomplete trailing connector ("open", "mahi", "google par").
     */
    fun isLikelyIncompletePartial(rawInput: String): Boolean {
        val lower = rawInput.trim().lowercase()
        if (lower.isEmpty()) return true
        val incompleteEndings = setOf(
            "mahi", "open", "launch", "start", "kholo", "chalao", "search",
            "google par", "youtube par", "chrome mein", "call", "and", "aur"
        )
        return incompleteEndings.contains(lower)
    }

    fun parseCommand(rawInput: String): RoutedVoiceCommand? {
        val clean = rawInput.trim()
        if (clean.isEmpty()) return null
        val cleanNoWake = clean
            .replace(Regex("^(mahi\\s*,?\\s*|ماہی\\s+|माही\\s+)", RegexOption.IGNORE_CASE), "")
            .trim()
            .removeSuffix(".")
            .removeSuffix("।")
            .trim()
        val lower = cleanNoWake.lowercase()
        if (lower.isEmpty()) return null

        // 1. Specific Video Recording Stop vs Immediate STOP
        if (lower.contains("video band") || lower.contains("recording stop") ||
            lower.contains("stop recording") || lower.contains("stop video") ||
            clean.contains("ویڈیو بند") || clean.contains("ریکارڈنگ بند") || clean.contains("वीडियो बंद")
        ) {
            return RoutedVoiceCommand.StopVideoRecording
        }

        if (lower == "stop" || lower == "stop scrolling" || lower == "ruk jao" || lower == "ruko" ||
            lower == "bas" || lower.contains("ruk jao") || lower.contains("stop gesture") ||
            clean.contains("رک جاؤ") || clean.contains("بند کرو") || clean.contains("रुक जाओ")
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

        // 3. Camera Voice Controls (explicit phrases only — never guess)
        parseCameraCommand(cleanNoWake, lower)?.let { return it }

        // 4. YouTube Search (English / Roman Urdu / Urdu / Hindi)
        if ((lower.contains("youtube") || clean.contains("یوٹیوب") || clean.contains("यूट्यूब")) &&
            (lower.contains("search") || lower.contains("par ") || lower.contains("pe ") ||
                lower.contains("dhoondo") || lower.contains("play ") || clean.contains("سرچ") || clean.contains("सर्च"))
        ) {
            val extractedQuery = cleanNoWake
                .replace(Regex("\\byoutube\\b", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\b(par|pe|search|karo|dhoondo|for|on|mein|main|me)\\b", RegexOption.IGNORE_CASE), " ")
                .replace("یوٹیوب", "")
                .replace("سرچ", "")
                .replace("کرو", "")
                .replace("यूट्यूब", "")
                .replace("सर्च", "")
                .replace("पर", "")
                .replace(Regex("\\s+"), " ")
                .trim()
            if (extractedQuery.isNotBlank() && !lower.contains("chalao") && !lower.contains("open") && !lower.contains("kholo")) {
                return RoutedVoiceCommand.SearchYouTube(extractedQuery)
            }
        }

        // 5. Open Website (e.g., "Chrome mein wikipedia.org kholo", "open website https://openai.com")
        parseOpenWebsite(cleanNoWake, lower)?.let { return it }

        // 6. Google Search & Internet Search (kept cleanly distinct from YouTube Search!)
        parseGoogleSearch(cleanNoWake, lower)?.let { return it }

        // 7. Google App Launch ("Google kholo", "Open Google")
        if ((lower == "google kholo" || lower == "open google" || lower == "google open karo" ||
                lower == "launch google" || cleanNoWake == "گوگل کھولو" || cleanNoWake == "गूगल खोलो")
        ) {
            return RoutedVoiceCommand.OpenGoogle
        }

        // 7b. Part 2 Smart Vision & Part 4 Smart Smartphone Control (evaluated before generic app-launcher keywords)
        parseSmartVisionCommand(cleanNoWake, lower)?.let { return it }
        parseSmartControlPreLaunch(cleanNoWake, lower)?.let { return it }

        // 8. App Launch + optional follow-up gesture
        val launchKeywords = listOf(
            "open ", "launch ", "start ", "kholo", "chalao", "open karo",
            "کھولو", "چلاؤ", "खोलो", "चलाओ"
        )
        if (launchKeywords.any { lower.contains(it) }) {
            val matchedApp = AppLauncherManager.matchKnownApp(cleanNoWake)
            val followUp = when {
                lower.contains("scroll down") || lower.contains("neeche") ->
                    VoiceGestureIntent("SWIPE_DOWN_SCROLL", speed = GestureSpeed.NORMAL)
                lower.contains("scroll up") || lower.contains("upar") ->
                    VoiceGestureIntent("SWIPE_UP_SCROLL", speed = GestureSpeed.NORMAL)
                else -> null
            }

            if (matchedApp != null) {
                if (matchedApp.canonicalName == "Google") {
                    return RoutedVoiceCommand.OpenGoogle
                }
                if (matchedApp.canonicalName == "Camera") {
                    val lens = if (lower.contains("front") || lower.contains("selfie")) {
                        CameraLensFacing.FRONT
                    } else {
                        CameraLensFacing.BACK
                    }
                    return RoutedVoiceCommand.OpenCamera(lens)
                }
                return RoutedVoiceCommand.LaunchApp(matchedApp.canonicalName, followUp)
            }

            val stripped = AppLauncherManager.normalizeAppQuery(
                cleanNoWake.substringBefore(" and ").substringBefore(" aur ")
            )
            if (stripped.isNotBlank() && !lower.contains("button") && !lower.contains("screen")) {
                return RoutedVoiceCommand.LaunchApp(stripped, followUp)
            }
        }

        // 8b. Remaining Chained Smart Commands
        parseSmartControlOrChainedCommand(cleanNoWake, lower)?.let { return it }

        // 9. Notifications & WhatsApp Reply commands
        parseNotificationOrWhatsAppCommand(cleanNoWake, lower)?.let { return it }

        // 10. Screen Gesture Engine commands (English, Roman Urdu, Urdu)
        parseGesture(clean, lower)?.let { return RoutedVoiceCommand.ExecuteGesture(it) }

        return null
    }

    private fun parseSmartControlPreLaunch(cleanNoWake: String, lower: String): RoutedVoiceCommand? {
        val controlKeywords = listOf(
            "battery", "storage", "wifi", "internet", "network", "phone model", "ka model",
            "device info", "android version", "uptime", "quick settings", "control center",
            "notification panel", "notification shade", "notifications kholo", "volume",
            "mute", "unmute", "ringtone", "brightness", "bluetooth", "mobile data",
            "airplane", "nfc"
        )
        val chainRegex = Regex("(?i)\\s+(?:and then|aur phir|phir|then|aur|and)\\s+")
        val parts = if (chainRegex.containsMatchIn(cleanNoWake)) {
            cleanNoWake.split(chainRegex).map { it.trim() }.filter { it.isNotBlank() }
        } else {
            emptyList()
        }
        val isMultiStepControlChain = parts.size >= 2 && (
            lower.startsWith("settings") ||
                lower.contains(" and then ") ||
                lower.contains(" aur phir ") ||
                parts.any { p -> controlKeywords.any { k -> p.lowercase().contains(k) } }
            )
        if (isMultiStepControlChain) {
            return RoutedVoiceCommand.SmartControl(cleanNoWake, parts)
        }
        if (controlKeywords.any { lower.contains(it) }) {
            return RoutedVoiceCommand.SmartControl(
                rawPhrase = cleanNoWake,
                chainedSteps = if (parts.size >= 2) parts else listOf(cleanNoWake)
            )
        }
        return null
    }

    private fun parseSmartVisionCommand(cleanNoWake: String, lower: String): RoutedVoiceCommand? {
        return when {
            lower.contains("screenshot samjhao") || lower.contains("explain screenshot") ||
                lower.contains("analyze screenshot") || lower.contains("take screenshot") ||
                lower.contains("screenshot lo") ->
                RoutedVoiceCommand.AnalyzeScreenshot(cleanNoWake)

            lower.contains("screen par kya") || lower.contains("screen pe kya") ||
                lower.contains("what is on screen") || lower.contains("what's on my screen") ||
                lower.contains("explain screen") || lower.contains("read screen") ||
                cleanNoWake.contains("اسکرین پر کیا") ->
                RoutedVoiceCommand.AnalyzeScreen(cleanNoWake)

            lower == "ye kya hai" || lower == "yeh kya hai" || lower.contains("what is this") ||
                lower.contains("camera mein kya") || lower.contains("samne kya hai") ||
                cleanNoWake.contains("یہ کیا ہے") ->
                RoutedVoiceCommand.DescribeCameraVision(cleanNoWake)

            lower == "notification batao" || lower == "notifications batao" ||
                lower.contains("summarize notification") ->
                RoutedVoiceCommand.AnalyzeNotifications

            else -> null
        }
    }

    private fun parseSmartControlOrChainedCommand(cleanNoWake: String, lower: String): RoutedVoiceCommand? {
        val chainRegex = Regex("(?i)\\s+(?:and then|aur phir|phir|then|aur|and)\\s+")
        val hasChainedConnector = chainRegex.containsMatchIn(cleanNoWake)
        if (hasChainedConnector) {
            val parts = cleanNoWake.split(chainRegex).map { it.trim() }.filter { it.isNotBlank() }
            if (parts.size >= 2) {
                return RoutedVoiceCommand.SmartControl(cleanNoWake, parts)
            }
        }
        return null
    }

    private fun parseNotificationOrWhatsAppCommand(cleanNoWake: String, lower: String): RoutedVoiceCommand? {
        if ((lower.contains("read") || lower.contains("check") || lower.contains("parho") ||
                lower.contains("batao") || lower.contains("dikhao") || cleanNoWake.contains("پڑھو")) &&
            (lower.contains("notification") || lower.contains("messages") || lower.contains("message") ||
                lower.contains("whatsapp"))
        ) {
            return RoutedVoiceCommand.ReadNotifications(whatsAppOnly = lower.contains("whatsapp"))
        }

        if (lower.startsWith("whatsapp reply ") || lower.startsWith("reply whatsapp ") ||
            lower.startsWith("reply on whatsapp ")
        ) {
            val body = cleanNoWake
                .replace(Regex("^(whatsapp reply|reply whatsapp|reply on whatsapp)\\s*", RegexOption.IGNORE_CASE), "")
                .trim()
            if (body.isNotBlank()) {
                return RoutedVoiceCommand.ReplyWhatsApp(senderHint = "", message = body)
            }
        }

        val replyToRegex = Regex("^reply\\s+(?:to\\s+)?([^:]+?):\\s*(.+)$", RegexOption.IGNORE_CASE)
        replyToRegex.find(cleanNoWake)?.let { match ->
            val sender = match.groupValues[1].trim()
            val msg = match.groupValues[2].trim()
            if (sender.isNotBlank() && msg.isNotBlank()) {
                return RoutedVoiceCommand.ReplyWhatsApp(senderHint = sender, message = msg)
            }
        }
        return null
    }

    private fun parseCameraCommand(cleanNoWake: String, lower: String): RoutedVoiceCommand? {
        return when {
            lower.contains("selfie") || cleanNoWake.contains("سیلفی") || cleanNoWake.contains("सेल्फी") -> {
                if (lower.contains("camera") && (lower.contains("kholo") || lower.contains("open"))) {
                    RoutedVoiceCommand.OpenCamera(CameraLensFacing.FRONT)
                } else {
                    RoutedVoiceCommand.CapturePhoto(isSelfie = true)
                }
            }
            lower.contains("front camera") && (lower.contains("kholo") || lower.contains("open")) ->
                RoutedVoiceCommand.OpenCamera(CameraLensFacing.FRONT)
            lower.contains("back camera") && (lower.contains("kholo") || lower.contains("open")) ->
                RoutedVoiceCommand.OpenCamera(CameraLensFacing.BACK)
            lower.contains("camera back karo") || lower.contains("switch to back camera") || lower.contains("back camera lagao") ->
                RoutedVoiceCommand.SwitchCamera(CameraLensFacing.BACK)
            lower.contains("camera front karo") || lower.contains("switch to front camera") || lower.contains("front camera lagao") ->
                RoutedVoiceCommand.SwitchCamera(CameraLensFacing.FRONT)
            lower.contains("video record") || lower.contains("record video") ||
                lower.contains("video banao") || cleanNoWake.contains("ویڈیو ریکارڈ") || cleanNoWake.contains("वीडियो रिकॉर्ड") ->
                RoutedVoiceCommand.StartVideoRecording
            lower.contains("photo lo") || lower.contains("picture khaincho") || lower.contains("photo khaincho") ||
                lower.contains("take a photo") || lower.contains("take photo") || lower.contains("click photo") ||
                cleanNoWake.contains("فوٹو لو") || cleanNoWake.contains("تصویر کھینچو") || cleanNoWake.contains("फोटो लो") ->
                RoutedVoiceCommand.CapturePhoto(isSelfie = false)
            lower.contains("flash on") || cleanNoWake.contains("فلیش آن") || cleanNoWake.contains("फ्लैश ऑन") ->
                RoutedVoiceCommand.SetCameraFlash(enabled = true)
            lower.contains("flash off") || cleanNoWake.contains("فلیش آف") || cleanNoWake.contains("फ्लैश ऑफ") ->
                RoutedVoiceCommand.SetCameraFlash(enabled = false)
            lower.contains("zoom in") || cleanNoWake.contains("زوم ان") ->
                RoutedVoiceCommand.SetCameraZoom(deltaStep = 0.5f)
            lower.contains("zoom out") || cleanNoWake.contains("زوم آؤٹ") ->
                RoutedVoiceCommand.SetCameraZoom(deltaStep = -0.5f)
            (lower == "camera" || lower == "camera kholo" || lower == "open camera" || lower == "camera open karo" ||
                cleanNoWake == "کیمرہ کھولو" || cleanNoWake == "कैमरा खोलो") ->
                RoutedVoiceCommand.OpenCamera(CameraLensFacing.BACK)
            else -> null
        }
    }

    private fun parseGoogleSearch(cleanNoWake: String, lower: String): RoutedVoiceCommand.GoogleSearch? {
        val isGoogleOrInternetSearch =
            ((lower.contains("google") || lower.contains("internet") || cleanNoWake.contains("گوگل") || cleanNoWake.contains("انٹرنیٹ") || cleanNoWake.contains("गूगल")) &&
                (lower.contains("search") || lower.contains("dhoondo") || lower.contains("dhundo") || lower.contains("par ") ||
                    lower.contains("pe ") || lower.contains("find") || cleanNoWake.contains("سرچ") || cleanNoWake.contains("ڈھونڈو") || cleanNoWake.contains("सर्च"))) ||
                lower.startsWith("search google ")

        if (!isGoogleOrInternetSearch) return null
        if (lower == "google kholo" || lower == "open google" || lower == "google open karo") return null

        val extracted = cleanNoWake
            .replace(Regex("\\b(google|internet|par|pe|search|dhoondo|dhundo|karo|find|for|on|mein|me|main|latest\\s+(?=$))\\b", RegexOption.IGNORE_CASE), " ")
            .replace("گوگل", "")
            .replace("انٹرنیٹ", "")
            .replace("پر", "")
            .replace("سرچ", "")
            .replace("ڈھونڈو", "")
            .replace("کرو", "")
            .replace("गूगल", "")
            .replace("इंटरनेट", "")
            .replace("पर", "")
            .replace("सर्च", "")
            .replace("करो", "")
            .replace(Regex("\\s+"), " ")
            .trim()

        if (extracted.isBlank()) return null
        val wantSummary = lower.contains("summarize") || lower.contains("urdu") || lower.contains("batao")
        return RoutedVoiceCommand.GoogleSearch(query = extracted, summarizeInLanguage = wantSummary)
    }

    private fun parseOpenWebsite(cleanNoWake: String, lower: String): RoutedVoiceCommand.OpenWebsite? {
        val urlRegex = Regex("(https?://[^\\s]+|[a-zA-Z0-9-]+\\.(com|org|net|io|ai|edu|pk|in|co)(/[^\\s]*)?)", RegexOption.IGNORE_CASE)
        val hasWebsiteCue = lower.contains("website") || lower.contains("chrome mein") ||
            lower.contains("chrome me") || lower.contains("open http") || urlRegex.containsMatchIn(cleanNoWake)

        if (!hasWebsiteCue) return null
        // Exclude bare app launches like "open youtube" without a domain suffix
        val domainMatch = urlRegex.find(cleanNoWake) ?: return null
        val candidateUrl = domainMatch.value.trim().trimEnd('.', ',', '!', '?')
        if (candidateUrl.isBlank()) return null

        val normalized = if (candidateUrl.startsWith("http://", true) || candidateUrl.startsWith("https://", true)) {
            candidateUrl
        } else {
            "https://$candidateUrl"
        }
        return RoutedVoiceCommand.OpenWebsite(normalized)
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
