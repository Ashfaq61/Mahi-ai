package com.example.automation

sealed class RoutedVoiceCommand {
    data class OpenApp(val appName: String) : RoutedVoiceCommand()
    data class SetTtsSpeed(val speed: Float) : RoutedVoiceCommand()
    data class TapScreenElement(val index: Int? = null, val labelQuery: String? = null) : RoutedVoiceCommand()
    data class TypeOnScreen(val text: String, val targetQuery: String? = null, val submit: Boolean = false) : RoutedVoiceCommand()
    data object AnalyzeScreen : RoutedVoiceCommand()
    data object AnalyzeScreenshot : RoutedVoiceCommand()
    data object OpenGalleryAnalysis : RoutedVoiceCommand()
    data object CompareGalleryImages : RoutedVoiceCommand()
    data object ExtractOcr : RoutedVoiceCommand()
    data class PerformScreenTask(val goal: String) : RoutedVoiceCommand()
    data class DeviceControl(val rawPhrase: String) : RoutedVoiceCommand()
    data class PlaceCall(val target: String) : RoutedVoiceCommand()
    data class OpenScreen(val tabRoute: String) : RoutedVoiceCommand()
    data class Conversation(val prompt: String) : RoutedVoiceCommand()
}

object CommandExecutionGuard {
    fun requiresExplicitConfirmation(goalOrCommand: String): String? {
        val lower = goalOrCommand.lowercase()
        return when {
            lower.contains("delete") || lower.contains("uninstall") || lower.contains("format") ->
                "Destructive file/app action requires explicit confirmation."
            lower.contains("buy") || lower.contains("purchase") || lower.contains("pay ") || lower.contains("transfer money") ->
                "Financial / purchase action requires explicit confirmation."
            lower.contains("install apk") ->
                "APK installation requires explicit user consent."
            else -> null
        }
    }
}

object VoiceCommandRouter {
    fun parseCommand(raw: String): RoutedVoiceCommand {
        val clean = raw.trim()
        val lower = clean.lowercase()
        if (clean.isBlank()) return RoutedVoiceCommand.Conversation(clean)

        // TTS Speed commands ("speech speed 1.5", "tez bolo", "ahista bolo")
        if (lower.contains("speech speed") || lower.contains("voice speed") || lower.contains("tts speed")) {
            val num = Regex("\\d+(?:\\.\\d+)?").find(lower)?.value?.toFloatOrNull()
            if (num != null) return RoutedVoiceCommand.SetTtsSpeed(num.coerceIn(0.5f, 2.0f))
        }
        if (lower.contains("speak faster") || lower.contains("tez bolo")) {
            return RoutedVoiceCommand.SetTtsSpeed(1.45f)
        }
        if (lower.contains("speak slower") || lower.contains("ahista bolo")) {
            return RoutedVoiceCommand.SetTtsSpeed(0.8f)
        }

        // Element tapping by index ("3 number par click karo", "tap element 2")
        Regex("(?i)(?:tap|click|press)\\s+(?:element\\s+)?#?(\\d+)").find(clean)?.let { m ->
            m.groupValues[1].toIntOrNull()?.let { idx ->
                return RoutedVoiceCommand.TapScreenElement(index = idx)
            }
        }
        Regex("(?i)(\\d+)\\s*(?:number|num)\\s*(?:par|pe|ko)?\\s*(?:click|tap|dabao)").find(clean)?.let { m ->
            m.groupValues[1].toIntOrNull()?.let { idx ->
                return RoutedVoiceCommand.TapScreenElement(index = idx)
            }
        }

        // Type on screen ("search box mein Mahi AI likho aur search karo", "type Hello World and send")
        Regex("(?i)^(?:(.+?)\\s+(?:mein|me|main|on)\\s+)?(.+?)\\s+likho(?:\\s+(?:aur|and)\\s+(search|send|enter))?.*$").find(clean)?.let { m ->
            val target = m.groupValues[1].takeIf { it.isNotBlank() }?.trim()
            val typed = m.groupValues[2].trim()
            val submit = m.groupValues[3].isNotBlank()
            return RoutedVoiceCommand.TypeOnScreen(text = typed, targetQuery = target, submit = submit)
        }
        Regex("(?i)^type\\s+(.+?)(?:\\s+(?:and|then)\\s+(send|submit|search|enter))?$").find(clean)?.let { m ->
            val typed = m.groupValues[1].trim()
            val submit = m.groupValues[2].isNotBlank()
            return RoutedVoiceCommand.TypeOnScreen(text = typed, submit = submit)
        }

        // Vision / Screen analysis
        if (lower.contains("gallery") && (lower.contains("photo") || lower.contains("image") || lower.contains("samjhao") || lower.contains("analy"))) {
            return RoutedVoiceCommand.OpenGalleryAnalysis
        }
        if (lower.contains("compare") && (lower.contains("image") || lower.contains("photo") || lower.contains("do "))) {
            return RoutedVoiceCommand.CompareGalleryImages
        }
        if (lower.contains("screenshot") && (lower.contains("samjhao") || lower.contains("analy") || lower.contains("describe") || lower.contains("read"))) {
            return RoutedVoiceCommand.AnalyzeScreenshot
        }
        if (lower.contains("screen dekho") || lower.contains("describe screen") || lower.contains("read screen") || lower.contains("what is on screen")) {
            return RoutedVoiceCommand.AnalyzeScreen
        }
        if (lower == "text padho" || lower.contains("read text") || lower.contains("extract text") || lower.contains("ocr")) {
            return RoutedVoiceCommand.ExtractOcr
        }

        // Interactive screen agent tasks
        val screenTaskSignals = listOf(
            "like karo", "like this", "subscribe", "comment karo", "save karo",
            "share mat karna", "wapas jao", "video chalao", "search karo", "follow karo", "agla video"
        )
        if (screenTaskSignals.any { lower.contains(it) }) {
            return RoutedVoiceCommand.PerformScreenTask(clean)
        }

        // Calls
        Regex("(?i)^(?:call|dial|phone karo)\\s+(.+)$").find(clean)?.let { m ->
            return RoutedVoiceCommand.PlaceCall(m.groupValues[1].trim())
        }

        // Device controls (battery, storage, wifi, volume, brightness, scroll)
        val controlSignals = listOf("battery", "storage", "wifi", "bluetooth", "volume", "mute", "unmute", "brightness", "scroll down", "scroll up", "neeche scroll", "upar scroll", "quick settings", "notification panel", "phone model", "device info")
        if (controlSignals.any { lower.contains(it) }) {
            return RoutedVoiceCommand.DeviceControl(clean)
        }

        // Open single app ("WhatsApp kholo", "open YouTube")
        Regex("(?i)^open\\s+([a-z0-9 ]+)$").find(clean)?.let { m ->
            return RoutedVoiceCommand.OpenApp(m.groupValues[1].trim())
        }
        Regex("(?i)^([a-z0-9 ]+?)\\s+(?:kholo|chalao)$").find(clean)?.let { m ->
            return RoutedVoiceCommand.OpenApp(m.groupValues[1].trim())
        }

        return RoutedVoiceCommand.Conversation(clean)
    }
}
