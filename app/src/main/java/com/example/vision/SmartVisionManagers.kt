package com.example.vision

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Base64
import com.example.api.AiProvider
import com.example.api.ProviderClient
import com.example.automation.CapturedNotification
import com.example.automation.MahiAccessibilityService
import com.example.automation.MahiNotificationService
import com.example.automation.ScreenSnapshot
import com.example.preferences.AppLanguage
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Shared provider configuration snapshot passed into online-capable Smart Vision managers.
 */
data class VisionAiConfig(
    val provider: AiProvider = AiProvider.GEMINI,
    val apiKey: String = "",
    val model: String = "gemini-2.5-flash",
    val baseUrl: String = AiProvider.GEMINI.defaultBaseUrl,
    val language: AppLanguage = AppLanguage.ENGLISH
) {
    val hasValidKey: Boolean get() = apiKey.isNotBlank()
}

internal object VisionBitmapUtils {
    fun scaleDown(bitmap: Bitmap, maxDim: Int = 1024): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= maxDim) return bitmap
        val ratio = maxDim.toFloat() / longest.toFloat()
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).roundToInt().coerceAtLeast(1),
            (bitmap.height * ratio).roundToInt().coerceAtLeast(1),
            true
        )
    }

    fun toBase64Jpeg(bitmap: Bitmap): String {
        val scaled = scaleDown(bitmap, 1024)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 82, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    suspend fun decodeUri(context: Context, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun averageLuminance(bitmap: Bitmap): Int {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return 0
        var sum = 0L
        var samples = 0
        val stepX = max(1, w / 24)
        val stepY = max(1, h / 24)
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val px = bitmap.getPixel(x, y)
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val b = px and 0xFF
                sum += ((r * 299 + g * 587 + b * 114) / 1000)
                samples++
                x += stepX
            }
            y += stepY
        }
        return if (samples == 0) 0 else (sum / samples).toInt()
    }
}

/**
 * 1. ScreenVisionManager (Hybrid: Accessibility node tree works OFFLINE; deep AI summary works ONLINE).
 */
class ScreenVisionManager(private val providerClient: ProviderClient = ProviderClient()) {
    suspend fun readAndExplainScreen(
        aiConfig: VisionAiConfig,
        userQuestion: String = ""
    ): Result<String> {
        val snapshot: ScreenSnapshot? = MahiAccessibilityService.refreshScreenSnapshot(force = true)
        if (snapshot == null || snapshot.elements.isEmpty()) {
            return Result.failure(
                IllegalStateException("Mahi Accessibility Service is off or current screen has no readable UI nodes.")
            )
        }

        val summary = snapshot.readableSummary
        if (!aiConfig.hasValidKey) {
            return Result.success(
                when (aiConfig.language) {
                    AppLanguage.URDU -> "آف لائن اسکرین خلاصہ: $summary"
                    AppLanguage.ROMAN_URDU -> "Offline Screen Scan: $summary"
                    AppLanguage.ENGLISH -> "Offline Screen Readout: $summary"
                }
            )
        }

        val systemPrompt = """
You are Mahi's Screen Intelligence assistant. Explain the active Android app screen clearly.
${aiConfig.language.instruction}
Keep the summary practical, concise (2-4 sentences), and highlight main buttons or actions available.
        """.trimIndent()
        val prompt = buildString {
            append(summary)
            if (userQuestion.isNotBlank()) {
                append("\n\nUser Question: ").append(userQuestion)
            }
        }
        return try {
            val reply = providerClient.generateChatReply(
                provider = aiConfig.provider,
                apiKey = aiConfig.apiKey,
                model = aiConfig.model,
                baseUrl = aiConfig.baseUrl,
                systemPrompt = systemPrompt,
                history = emptyList(),
                userMessage = prompt
            )
            Result.success(reply)
        } catch (e: Exception) {
            Result.success("Offline Screen Readout (AI offline: ${e.localizedMessage}): $summary")
        }
    }
}

/**
 * 2. ScreenshotVisionManager
 * - Android 11+ (API 30+): Uses AccessibilityService.takeScreenshot() silently when Accessibility is enabled.
 * - Below API 30 or DRM-protected apps: Requires picking a screenshot from Gallery via PickVisualMedia.
 */
class ScreenshotVisionManager(
    private val neuralLensAnalyzer: NeuralLensAnalyzer,
    private val providerClient: ProviderClient = ProviderClient()
) {
    private val _lastScreenshot = MutableStateFlow<Bitmap?>(null)
    val lastScreenshot: StateFlow<Bitmap?> = _lastScreenshot.asStateFlow()

    suspend fun captureActiveScreenBitmap(): Result<Bitmap> = suspendCancellableCoroutine { cont ->
        MahiAccessibilityService.takeScreenshotBitmap { result ->
            result.onSuccess { bmp -> _lastScreenshot.value = VisionBitmapUtils.scaleDown(bmp, 1080) }
            if (cont.isActive) cont.resume(result)
        }
    }

    suspend fun captureAndAnalyzeScreenshot(
        aiConfig: VisionAiConfig,
        question: String = ""
    ): Result<String> {
        val captureRes = captureActiveScreenBitmap()
        val bitmap = captureRes.getOrElse { err -> return Result.failure(err) }
        val snap = neuralLensAnalyzer.analyzeBitmapOnDevice(bitmap)

        if (!aiConfig.hasValidKey) {
            val offlineOcr = snap.ocrText.ifBlank { "No readable text found in screenshot." }
            return Result.success(
                when (aiConfig.language) {
                    AppLanguage.URDU -> "اسکرین شاٹ OCR (آف لائن): $offlineOcr"
                    AppLanguage.ROMAN_URDU -> "Screenshot Scan (Offline): Labels=${snap.labels.joinToString { it.label }} | Text: $offlineOcr"
                    AppLanguage.ENGLISH -> "Screenshot Scan (Offline): Labels=${snap.labels.joinToString { it.label }} | Text: $offlineOcr"
                }
            )
        }

        val prompt = question.ifBlank {
            "Explain what is shown in this Android screenshot, extract key text, and suggest what the user can do next."
        }
        return try {
            val answer = neuralLensAnalyzer.askAiAboutVision(
                provider = aiConfig.provider,
                apiKey = aiConfig.apiKey,
                model = aiConfig.model,
                baseUrl = aiConfig.baseUrl,
                language = aiConfig.language,
                question = prompt,
                overrideBitmap = bitmap
            )
            Result.success(answer)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getCaptureCapabilityNote(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            "Live Accessibility Screenshot ready (Android ${Build.VERSION.RELEASE}). Note: banking/DRM screens return system failure by Android design."
        } else {
            "Android <11 requires selecting a saved screenshot via Photo Picker."
        }
    }
}

/**
 * 3. CameraVisionManager (Live CameraX preview frame + ML Kit Offline + Vision AI Online).
 */
class CameraVisionManager(private val neuralLensAnalyzer: NeuralLensAnalyzer) {
    fun getLatestFrame(): Bitmap? = neuralLensAnalyzer.latestCapturedBitmap

    suspend fun describeCameraView(
        aiConfig: VisionAiConfig,
        question: String = ""
    ): Result<String> {
        val bmp = neuralLensAnalyzer.latestCapturedBitmap
            ?: return Result.failure(IllegalStateException("Point the camera in Neural Lens first so a live frame is captured."))

        if (!aiConfig.hasValidKey) {
            return Result.success(neuralLensAnalyzer.formatOfflineMlKitSummary(aiConfig.language))
        }
        return try {
            val reply = neuralLensAnalyzer.askAiAboutVision(
                provider = aiConfig.provider,
                apiKey = aiConfig.apiKey,
                model = aiConfig.model,
                baseUrl = aiConfig.baseUrl,
                language = aiConfig.language,
                question = question.ifBlank { "What am I looking at? Explain objects, text, and context." },
                overrideBitmap = bmp
            )
            Result.success(reply)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

/**
 * 4. ObjectRecognitionManager (OFFLINE ML Kit Image Labeling + ONLINE detailed product/device identification).
 */
class ObjectRecognitionManager(private val neuralLensAnalyzer: NeuralLensAnalyzer) {
    suspend fun identifyObjects(
        aiConfig: VisionAiConfig,
        bitmap: Bitmap? = neuralLensAnalyzer.latestCapturedBitmap
    ): Result<String> {
        val target = bitmap
            ?: return Result.failure(IllegalStateException("Capture a camera frame or choose a photo first."))

        val snap = neuralLensAnalyzer.analyzeBitmapOnDevice(target)
        val offlineList = if (snap.labels.isEmpty()) {
            "No prominent objects confidently detected offline."
        } else {
            snap.labels.joinToString(", ") { "${it.label} (${it.confidencePercent}%)" }
        }

        if (!aiConfig.hasValidKey) {
            return Result.success("Detected Objects (Offline ML Kit): $offlineList")
        }

        return try {
            val reply = neuralLensAnalyzer.askAiAboutVision(
                provider = aiConfig.provider,
                apiKey = aiConfig.apiKey,
                model = aiConfig.model,
                baseUrl = aiConfig.baseUrl,
                language = aiConfig.language,
                question = "List and identify every visible object, gadget, brand, or product in this image clearly. Detected ML Kit hints: $offlineList",
                overrideBitmap = target
            )
            Result.success(reply)
        } catch (_: Exception) {
            Result.success("Detected Objects (Offline): $offlineList")
        }
    }
}

/**
 * 5. SceneRecognitionManager (OFFLINE ML Kit labels + brightness estimation; ONLINE contextual scene AI).
 */
class SceneRecognitionManager(private val neuralLensAnalyzer: NeuralLensAnalyzer) {
    suspend fun recognizeScene(
        aiConfig: VisionAiConfig,
        bitmap: Bitmap? = neuralLensAnalyzer.latestCapturedBitmap
    ): Result<String> {
        val target = bitmap
            ?: return Result.failure(IllegalStateException("Point the camera or pick an image to analyze the scene."))

        val snap = neuralLensAnalyzer.analyzeBitmapOnDevice(target)
        val lum = VisionBitmapUtils.averageLuminance(target)
        val lighting = when {
            lum < 55 -> "Low-light / dark environment"
            lum < 150 -> "Indoor / balanced lighting"
            else -> "Bright / well-lit scene"
        }

        val offlineDesc = buildString {
            append("Lighting: $lighting (luminance=$lum/255)")
            if (snap.labels.isNotEmpty()) {
                append(" | Scene tags: ").append(snap.labels.joinToString(", ") { it.label })
            }
        }

        if (!aiConfig.hasValidKey) {
            return Result.success("Scene Recognition (Offline): $offlineDesc")
        }

        return try {
            val reply = neuralLensAnalyzer.askAiAboutVision(
                provider = aiConfig.provider,
                apiKey = aiConfig.apiKey,
                model = aiConfig.model,
                baseUrl = aiConfig.baseUrl,
                language = aiConfig.language,
                question = "Describe the surroundings, setting (indoor/outdoor/room/street), atmosphere, and safety/context notes. Technical lighting cue: $offlineDesc",
                overrideBitmap = target
            )
            Result.success(reply)
        } catch (_: Exception) {
            Result.success("Scene Recognition (Offline): $offlineDesc")
        }
    }
}

data class OcrExtractionResult(
    val extractedText: String,
    val explanation: String = "",
    val usedOnlineUrduAi: Boolean = false
)

/**
 * 6. OCRManager
 * - OFFLINE: ML Kit Latin + Devanagari recognizers.
 * - ONLINE: Urdu/Nastaliq & complex multilingual scripts use Vision AI when key is configured,
 *   because Android ML Kit v2 does not bundle a standalone offline Urdu/Arabic recognizer model.
 */
class OCRManager(
    private val providerClient: ProviderClient = ProviderClient()
) {
    private val latinRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
    private val devanagariRecognizer by lazy {
        TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
    }

    suspend fun extractText(
        bitmap: Bitmap,
        aiConfig: VisionAiConfig,
        includeAiExplanation: Boolean = false
    ): Result<OcrExtractionResult> {
        val scaled = VisionBitmapUtils.scaleDown(bitmap, 1280)
        val input = InputImage.fromBitmap(scaled, 0)

        val latinText = runRecognizer(latinRecognizer, input)
        val devaText = runRecognizer(devanagariRecognizer, input)
        val combinedOffline = listOf(latinText, devaText)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString("\n")
            .trim()

        val needsOnlineUrdu = (aiConfig.language == AppLanguage.URDU || combinedOffline.isBlank()) && aiConfig.hasValidKey

        if (!needsOnlineUrdu && !includeAiExplanation) {
            return Result.success(
                OcrExtractionResult(
                    extractedText = combinedOffline.ifBlank { "No Latin/Hindi text detected offline. If this is Urdu script, configure Gemini key for online Urdu OCR." },
                    usedOnlineUrduAi = false
                )
            )
        }

        if (!aiConfig.hasValidKey) {
            return Result.success(
                OcrExtractionResult(
                    extractedText = combinedOffline.ifBlank { "No text detected offline." },
                    explanation = "Configure a Vision API key in Settings for Urdu script OCR and detailed translation.",
                    usedOnlineUrduAi = false
                )
            )
        }

        return try {
            val base64 = VisionBitmapUtils.toBase64Jpeg(scaled)
            val prompt = buildString {
                append("Transcribe ALL visible text verbatim (including Urdu, English, Roman Urdu, Hindi, or numbers). ")
                if (includeAiExplanation) {
                    append("Then provide a brief explanation/translation. ${aiConfig.language.instruction} ")
                }
                if (combinedOffline.isNotBlank()) {
                    append("\nOffline OCR pre-scan hint:\n$combinedOffline")
                }
            }
            val aiOut = providerClient.analyzeImageWithProvider(
                provider = aiConfig.provider,
                apiKey = aiConfig.apiKey,
                model = aiConfig.model,
                baseUrl = aiConfig.baseUrl,
                base64Jpeg = base64,
                systemPrompt = "You are a precise OCR & document assistant. Never invent text not present in the image.",
                userPrompt = prompt
            )
            Result.success(
                OcrExtractionResult(
                    extractedText = combinedOffline.ifBlank { aiOut },
                    explanation = if (includeAiExplanation || combinedOffline.isNotBlank()) aiOut else "",
                    usedOnlineUrduAi = true
                )
            )
        } catch (e: Exception) {
            if (combinedOffline.isNotBlank()) {
                Result.success(OcrExtractionResult(extractedText = combinedOffline, explanation = "Offline OCR only (${e.localizedMessage})"))
            } else {
                Result.failure(e)
            }
        }
    }

    fun copyToClipboard(context: Context, text: String): Boolean {
        if (text.isBlank()) return false
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        cm.setPrimaryClip(ClipData.newPlainText("Mahi OCR Text", text))
        return true
    }

    private suspend fun runRecognizer(
        recognizer: com.google.mlkit.vision.text.TextRecognizer,
        image: InputImage
    ): String = suspendCancellableCoroutine { cont ->
        recognizer.process(image)
            .addOnSuccessListener { txt -> if (cont.isActive) cont.resume(txt.text.trim()) }
            .addOnFailureListener { if (cont.isActive) cont.resume("") }
    }
}

enum class NotificationPriorityLevel { HIGH, NORMAL, LOW }

data class RedactedNotificationSummary(
    val appLabel: String,
    val senderTitle: String,
    val redactedMessage: String,
    val priority: NotificationPriorityLevel,
    val hadSensitiveRedaction: Boolean
)

/**
 * 7. NotificationUnderstandingManager
 * - Masks/redacts OTPs, verification PINs, CVVs, card numbers, and long account/ID numbers BEFORE summarizing or sending to AI.
 * - Never writes raw notification text to disk.
 */
class NotificationUnderstandingManager(
    private val providerClient: ProviderClient = ProviderClient()
) {
    companion object {
        private val OTP_REGEX = Regex("(?i)(?:otp|code|pin|verification|passcode|cvv|token)\\s*(?:is|:|#|-)?\\s*(\\d{4,8})")
        private val STANDALONE_OTP_REGEX = Regex("\\b\\d{4,8}\\b")
        private val CARD_REGEX = Regex("\\b(?:\\d[ -]*?){12,19}\\b")

        fun redactSensitiveText(raw: String): Pair<String, Boolean> {
            var masked = raw
            var redacted = false

            if (CARD_REGEX.containsMatchIn(masked)) {
                masked = CARD_REGEX.replace(masked, "[REDACTED_CARD_NUMBER]")
                redacted = true
            }
            if (OTP_REGEX.containsMatchIn(masked)) {
                masked = OTP_REGEX.replace(masked) { m ->
                    val digits = m.groupValues.getOrNull(1).orEmpty()
                    m.value.replace(digits, "****")
                }
                redacted = true
            } else if (
                (masked.contains("otp", ignoreCase = true) ||
                    masked.contains("verify", ignoreCase = true) ||
                    masked.contains("bank", ignoreCase = true) ||
                    masked.contains("password", ignoreCase = true)) &&
                STANDALONE_OTP_REGEX.containsMatchIn(masked)
            ) {
                masked = STANDALONE_OTP_REGEX.replace(masked, "****")
                redacted = true
            }
            return masked to redacted
        }

        fun classifyPriority(item: CapturedNotification, cleanText: String): NotificationPriorityLevel {
            val lower = "${item.senderTitle} $cleanText".lowercase()
            return when {
                item.isWhatsApp ||
                    lower.contains("urgent") ||
                    lower.contains("call") ||
                    lower.contains("bank") ||
                    lower.contains("otp") ||
                    lower.contains("delivery") -> NotificationPriorityLevel.HIGH
                lower.contains("promo") || lower.contains("sale") || lower.contains("discount") -> NotificationPriorityLevel.LOW
                else -> NotificationPriorityLevel.NORMAL
            }
        }
    }

    fun getSafeSummaries(notifications: List<CapturedNotification>): List<RedactedNotificationSummary> {
        return notifications.map { item ->
            val (masked, hadRedaction) = redactSensitiveText(item.messageText)
            val priority = classifyPriority(item, masked)
            RedactedNotificationSummary(
                appLabel = item.appLabel,
                senderTitle = item.senderTitle,
                redactedMessage = masked,
                priority = priority,
                hadSensitiveRedaction = hadRedaction
            )
        }
    }

    suspend fun summarizeActiveNotifications(
        context: Context,
        aiConfig: VisionAiConfig
    ): Result<String> {
        if (!MahiNotificationService.isListenerEnabled(context)) {
            return Result.failure(
                IllegalStateException("Notification Listener access is off. Enable it in Settings to summarize notifications.")
            )
        }
        val active = MahiNotificationService.notifications.value
        if (active.isEmpty()) {
            return Result.success("No unread notifications right now.")
        }

        val safeList = getSafeSummaries(active.take(12))
        val structuredText = safeList.joinToString("\n") { s ->
            "[${s.priority.name}] ${s.appLabel} • ${s.senderTitle}: ${s.redactedMessage.take(160)}"
        }

        if (!aiConfig.hasValidKey) {
            return Result.success("Safe Notification Digest (OTPs masked):\n$structuredText")
        }

        return try {
            val reply = providerClient.generateChatReply(
                provider = aiConfig.provider,
                apiKey = aiConfig.apiKey,
                model = aiConfig.model,
                baseUrl = aiConfig.baseUrl,
                systemPrompt = "Summarize these pre-redacted Android notifications concisely by priority. ${aiConfig.language.instruction}",
                history = emptyList(),
                userMessage = structuredText
            )
            Result.success(reply)
        } catch (_: Exception) {
            Result.success("Safe Notification Digest (OTPs masked):\n$structuredText")
        }
    }
}

/**
 * 8. GalleryAnalysisManager (Uses zero-permission Android Photo Picker Uri).
 */
class GalleryAnalysisManager(private val neuralLensAnalyzer: NeuralLensAnalyzer) {
    suspend fun analyzeSelectedImage(
        context: Context,
        uri: Uri,
        aiConfig: VisionAiConfig,
        question: String = ""
    ): Result<Pair<Bitmap, String>> {
        val bitmap = VisionBitmapUtils.decodeUri(context, uri)
            ?: return Result.failure(IllegalStateException("Could not decode the selected image."))
        neuralLensAnalyzer.analyzeBitmapOnDevice(bitmap)

        if (!aiConfig.hasValidKey) {
            return Result.success(bitmap to neuralLensAnalyzer.formatOfflineMlKitSummary(aiConfig.language))
        }

        return try {
            val answer = neuralLensAnalyzer.askAiAboutVision(
                provider = aiConfig.provider,
                apiKey = aiConfig.apiKey,
                model = aiConfig.model,
                baseUrl = aiConfig.baseUrl,
                language = aiConfig.language,
                question = question.ifBlank { "Analyze this gallery photo: explain subjects, visible text, and helpful context." },
                overrideBitmap = bitmap
            )
            Result.success(bitmap to answer)
        } catch (e: Exception) {
            Result.success(bitmap to "${neuralLensAnalyzer.formatOfflineMlKitSummary(aiConfig.language)}\n(AI offline: ${e.localizedMessage})")
        }
    }
}

data class VisualQaTurn(
    val role: String, // "user" or "assistant"
    val text: String,
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * 9. VisualQuestionAnswerManager (Multi-turn per-image Q&A with history & explicit reset).
 */
class VisualQuestionAnswerManager(private val providerClient: ProviderClient = ProviderClient()) {
    private var activeBitmap: Bitmap? = null
    private val _turns = MutableStateFlow<List<VisualQaTurn>>(emptyList())
    val turns: StateFlow<List<VisualQaTurn>> = _turns.asStateFlow()

    fun setActiveImage(bitmap: Bitmap) {
        activeBitmap = VisionBitmapUtils.scaleDown(bitmap, 1024)
        _turns.value = emptyList()
    }

    fun hasActiveImage(): Boolean = activeBitmap != null

    fun resetConversation() {
        _turns.value = emptyList()
    }

    suspend fun askFollowUp(
        question: String,
        aiConfig: VisionAiConfig
    ): Result<String> {
        val img = activeBitmap
            ?: return Result.failure(IllegalStateException("Select or capture an image first before asking follow-up questions."))
        val trimmed = question.trim()
        if (trimmed.isBlank()) {
            return Result.failure(IllegalArgumentException("Enter or speak a question about this image."))
        }

        if (!aiConfig.hasValidKey) {
            return Result.failure(IllegalStateException("Configure an AI provider key for multi-turn visual Q&A."))
        }

        val conversationSummary = _turns.value.takeLast(8).joinToString("\n") { "${it.role.uppercase()}: ${it.text}" }
        val prompt = buildString {
            if (conversationSummary.isNotBlank()) {
                append("Prior Visual Q&A Turns on this same image:\n")
                append(conversationSummary)
                append("\n\n")
            }
            append("New Question: ").append(trimmed)
        }

        return try {
            val answer = providerClient.analyzeImageWithProvider(
                provider = aiConfig.provider,
                apiKey = aiConfig.apiKey,
                model = aiConfig.model,
                baseUrl = aiConfig.baseUrl,
                base64Jpeg = VisionBitmapUtils.toBase64Jpeg(img),
                systemPrompt = "You are Mahi's Visual Q&A assistant. Answer follow-up questions accurately based only on the image and prior Q&A context. ${aiConfig.language.instruction}",
                userPrompt = prompt
            )
            _turns.value = _turns.value + VisualQaTurn("user", trimmed) + VisualQaTurn("assistant", answer)
            Result.success(answer)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

data class ImageComparisonResult(
    val offlineDiffSummary: String,
    val aiComparison: String? = null,
    val similarityScorePercent: Int
)

/**
 * 10. ImageComparisonManager (Two-image offline structural/ML Kit diff + optional Vision AI side-by-side summary).
 */
class ImageComparisonManager(
    private val ocrManager: OCRManager,
    private val providerClient: ProviderClient = ProviderClient()
) {
    fun computeStructuralSimilarityPercent(bmpA: Bitmap, bmpB: Bitmap): Int {
        val thumbA = Bitmap.createScaledBitmap(bmpA, 16, 16, true)
        val thumbB = Bitmap.createScaledBitmap(bmpB, 16, 16, true)
        var totalDiff = 0L
        val pixels = 16 * 16
        for (y in 0 until 16) {
            for (x in 0 until 16) {
                val pa = thumbA.getPixel(x, y)
                val pb = thumbB.getPixel(x, y)
                val dr = abs(((pa shr 16) and 0xFF) - ((pb shr 16) and 0xFF))
                val dg = abs(((pa shr 8) and 0xFF) - ((pb shr 8) and 0xFF))
                val db = abs((pa and 0xFF) - (pb and 0xFF))
                totalDiff += (dr + dg + db)
            }
        }
        val maxDiff = pixels * 255L * 3L
        val sim = 100.0 * (1.0 - (totalDiff.toDouble() / maxDiff.toDouble()))
        return sim.roundToInt().coerceIn(0, 100)
    }

    suspend fun compareImages(
        bmpA: Bitmap,
        bmpB: Bitmap,
        aiConfig: VisionAiConfig,
        userQuestion: String = ""
    ): Result<ImageComparisonResult> {
        val sim = computeStructuralSimilarityPercent(bmpA, bmpB)
        val lumA = VisionBitmapUtils.averageLuminance(bmpA)
        val lumB = VisionBitmapUtils.averageLuminance(bmpB)
        val ocrA = ocrManager.extractText(bmpA, aiConfig.copy(apiKey = "")).getOrNull()?.extractedText.orEmpty().take(240)
        val ocrB = ocrManager.extractText(bmpB, aiConfig.copy(apiKey = "")).getOrNull()?.extractedText.orEmpty().take(240)

        val offlineSummary = buildString {
            append("Visual Similarity: ~$sim%\n")
            append("Image A: ${bmpA.width}x${bmpA.height}, brightness $lumA/255\n")
            append("Image B: ${bmpB.width}x${bmpB.height}, brightness $lumB/255\n")
            if (ocrA.isNotBlank() || ocrB.isNotBlank()) {
                append("OCR Text A: ${ocrA.ifBlank { "(none)" }}\n")
                append("OCR Text B: ${ocrB.ifBlank { "(none)" }}")
            }
        }

        if (!aiConfig.hasValidKey) {
            return Result.success(
                ImageComparisonResult(
                    offlineDiffSummary = offlineSummary,
                    aiComparison = null,
                    similarityScorePercent = sim
                )
            )
        }

        return try {
            val descA = providerClient.analyzeImageWithProvider(
                provider = aiConfig.provider,
                apiKey = aiConfig.apiKey,
                model = aiConfig.model,
                baseUrl = aiConfig.baseUrl,
                base64Jpeg = VisionBitmapUtils.toBase64Jpeg(bmpA),
                systemPrompt = "Concisely list the key objects, colors, text, and layout in Image A.",
                userPrompt = "Describe Image A cleanly in 3 bullet points."
            )
            val descB = providerClient.analyzeImageWithProvider(
                provider = aiConfig.provider,
                apiKey = aiConfig.apiKey,
                model = aiConfig.model,
                baseUrl = aiConfig.baseUrl,
                base64Jpeg = VisionBitmapUtils.toBase64Jpeg(bmpB),
                systemPrompt = "Concisely list the key objects, colors, text, and layout in Image B.",
                userPrompt = "Describe Image B cleanly in 3 bullet points."
            )
            val finalComparison = providerClient.generateChatReply(
                provider = aiConfig.provider,
                apiKey = aiConfig.apiKey,
                model = aiConfig.model,
                baseUrl = aiConfig.baseUrl,
                systemPrompt = "Compare Image A and Image B clearly (similarities, differences, and which is clearer). ${aiConfig.language.instruction}",
                history = emptyList(),
                userMessage = buildString {
                    append("Metrics:\n").append(offlineSummary)
                    append("\n\nImage A Breakdown:\n").append(descA)
                    append("\n\nImage B Breakdown:\n").append(descB)
                    if (userQuestion.isNotBlank()) append("\n\nUser Question: ").append(userQuestion)
                }
            )
            Result.success(
                ImageComparisonResult(
                    offlineDiffSummary = offlineSummary,
                    aiComparison = finalComparison,
                    similarityScorePercent = sim
                )
            )
        } catch (e: Exception) {
            Result.success(
                ImageComparisonResult(
                    offlineDiffSummary = offlineSummary,
                    aiComparison = "Offline comparison only (AI error: ${e.localizedMessage})",
                    similarityScorePercent = sim
                )
            )
        }
    }
}
