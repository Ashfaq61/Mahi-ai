package com.example.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.example.api.ProviderClient
import com.example.api.SecureApiKeyStore
import com.example.automation.MahiAccessibilityService
import com.example.automation.MahiNotificationService
import com.example.automation.ScreenSnapshot
import com.example.preferences.AppLanguage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

data class LiveVisionSnapshot(
    val meanLuminance: Int = 0,
    val contrastLevel: String = "Balanced",
    val dominantHue: String = "Neutral",
    val sceneHint: String = "Point camera or pick an image to inspect",
    val ocrDetectedText: String = "",
    val capturedAtMs: Long = System.currentTimeMillis()
)

class NeuralLensAnalyzer {
    private val _liveSnapshot = MutableStateFlow(LiveVisionSnapshot())
    val liveSnapshot: StateFlow<LiveVisionSnapshot> = _liveSnapshot.asStateFlow()

    fun analyzeBitmapSample(bitmap: Bitmap): LiveVisionSnapshot {
        val w = bitmap.width.coerceAtLeast(1)
        val h = bitmap.height.coerceAtLeast(1)
        val stepX = (w / 16).coerceAtLeast(1)
        val stepY = (h / 16).coerceAtLeast(1)
        var count = 0
        var sumLum = 0L
        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        var minLum = 255
        var maxLum = 0

        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val px = bitmap.getPixel(x, y)
                val r = Color.red(px)
                val g = Color.green(px)
                val b = Color.blue(px)
                val lum = ((0.299f * r) + (0.587f * g) + (0.114f * b)).roundToInt()
                sumLum += lum
                sumR += r
                sumG += g
                sumB += b
                if (lum < minLum) minLum = lum
                if (lum > maxLum) maxLum = lum
                count++
                x += stepX
            }
            y += stepY
        }

        val mean = if (count > 0) (sumLum / count).toInt() else 128
        val avgR = if (count > 0) (sumR / count).toInt() else 0
        val avgG = if (count > 0) (sumG / count).toInt() else 0
        val avgB = if (count > 0) (sumB / count).toInt() else 0
        val dominant = when {
            avgR > avgG + 25 && avgR > avgB + 25 -> "Warm Red/Orange"
            avgG > avgR + 20 && avgG > avgB + 20 -> "Green Foliage/Surface"
            avgB > avgR + 20 && avgB > avgG + 20 -> "Cool Blue"
            mean > 205 -> "Bright White"
            mean < 45 -> "Low Light / Dark"
            else -> "Neutral Balanced"
        }
        val contrast = when {
            maxLum - minLum > 160 -> "High Contrast (Crisp Text/Edges)"
            maxLum - minLum < 60 -> "Low Contrast"
            else -> "Balanced"
        }
        val hint = "${bitmap.width}x${bitmap.height} frame • $dominant • Luminance $mean/255 ($contrast)"
        val snap = LiveVisionSnapshot(
            meanLuminance = mean,
            contrastLevel = contrast,
            dominantHue = dominant,
            sceneHint = hint,
            capturedAtMs = System.currentTimeMillis()
        )
        _liveSnapshot.value = snap
        return snap
    }
}

object NotificationUnderstandingManager {
    private val OTP_REGEX = Regex("\\b\\d{4,8}\\b")
    private val CARD_REGEX = Regex("\\b(?:\\d[ -]*?){13,19}\\b")

    fun redactSensitiveText(raw: String): String {
        if (raw.isBlank()) return raw
        var safe = raw.replace(CARD_REGEX, "[REDACTED_CARD_NUMBER]")
        val hasOtpWord = safe.contains("otp", true) || safe.contains("code", true) || safe.contains("pin", true) || safe.contains("verification", true)
        if (hasOtpWord) {
            safe = safe.replace(OTP_REGEX, "[REDACTED_OTP]")
        }
        return safe
    }

    fun summarizeActiveNotifications(): String {
        val list = MahiNotificationService.notifications.value
        if (list.isEmpty()) {
            return if (MahiNotificationService.isConnected.value) {
                "No active notifications right now."
            } else {
                "Notification Listener permission is off. Enable Mahi Notification Service in Settings."
            }
        }
        return list.take(8).joinToString("\n") { n ->
            "• [${n.appLabel}] ${n.sender}: ${n.bodyRedacted}"
        }
    }
}

class ScreenVisionManager(private val context: Context) {
    suspend fun readCurrentScreen(): Result<ScreenSnapshot> {
        val guard = MahiAccessibilityService.evaluateForegroundState(context)
        if (!guard.success) {
            return Result.failure(IllegalStateException(guard.message))
        }
        val snap = MahiAccessibilityService.readScreenWithRetry(context)
        return Result.success(snap)
    }

    suspend fun captureScreenJpeg(): Result<ByteArray> =
        MahiAccessibilityService.takeScreenshotJpeg()
}

class ScreenshotVisionManager(
    private val providerClient: ProviderClient = ProviderClient(),
    private val keyStore: SecureApiKeyStore? = null
) {
    suspend fun analyzeScreenshotWithAi(jpeg: ByteArray, question: String, language: AppLanguage): Result<String> {
        val ks = keyStore ?: return Result.failure(IllegalStateException("API KeyStore not initialized"))
        val key = ks.decryptSlotKey(com.example.api.AiProvider.GEMINI)
            ?: return Result.failure(IllegalStateException("Add a Gemini API key in API Providers to analyze images/screenshots."))
        return runCatching {
            providerClient.generateVisionReply(
                provider = com.example.api.AiProvider.GEMINI,
                apiKey = key,
                model = ks.state.value.selectedModels[com.example.api.AiProvider.GEMINI] ?: "gemini-2.5-flash",
                baseUrl = ks.state.value.customBaseUrls[com.example.api.AiProvider.GEMINI]
                    ?: com.example.api.AiProvider.GEMINI.defaultBaseUrl,
                systemPrompt = "You are Mahi AI Vision Analyzer. ${language.instruction}",
                question = question,
                imageJpegBytes = jpeg
            )
        }
    }
}

class CameraVisionManager(
    private val screenshotVisionManager: ScreenshotVisionManager
) {
    suspend fun analyzeCameraFrame(bitmap: Bitmap, question: String, language: AppLanguage): Result<String> {
        val bytes = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
            out.toByteArray()
        }
        return screenshotVisionManager.analyzeScreenshotWithAi(bytes, question, language)
    }
}

class VisualQuestionAnswerManager(
    private val screenshotVisionManager: ScreenshotVisionManager
) {
    var lastImageBytes: ByteArray? = null
        private set

    fun rememberImage(bytes: ByteArray) {
        lastImageBytes = bytes
    }

    suspend fun askAboutLastImage(question: String, language: AppLanguage): Result<String> {
        val bytes = lastImageBytes
            ?: return Result.failure(IllegalStateException("No image captured or selected yet. Open Neural Lens first."))
        return screenshotVisionManager.analyzeScreenshotWithAi(bytes, question, language)
    }
}
