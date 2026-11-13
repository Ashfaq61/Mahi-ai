package com.example.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.example.api.AiProvider
import com.example.api.ProviderClient
import com.example.preferences.AppLanguage
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.roundToInt

data class DetectedVisionLabel(
    val label: String,
    val confidencePercent: Int
)

data class LiveVisionSnapshot(
    val labels: List<DetectedVisionLabel> = emptyList(),
    val ocrText: String = "",
    val lastUpdatedMs: Long = 0L
)

class NeuralLensAnalyzer(
    private val providerClient: ProviderClient = ProviderClient()
) {
    companion object {
        private const val TAG = "NeuralLensAnalyzer"
        private const val ANALYZE_INTERVAL_MS = 650L
    }

    private val textRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
    private val imageLabeler by lazy {
        ImageLabeling.getClient(
            ImageLabelerOptions.Builder()
                .setConfidenceThreshold(0.55f)
                .build()
        )
    }

    private val isProcessing = AtomicBoolean(false)
    private var lastAnalyzedTimestamp = 0L

    @Volatile
    var latestCapturedBitmap: Bitmap? = null
        private set

    private val _liveSnapshot = MutableStateFlow(LiveVisionSnapshot())
    val liveSnapshot: StateFlow<LiveVisionSnapshot> = _liveSnapshot.asStateFlow()

    @OptIn(ExperimentalGetImage::class)
    fun createCameraXAnalyzer(): ImageAnalysis.Analyzer {
        return ImageAnalysis.Analyzer { imageProxy ->
            val now = System.currentTimeMillis()
            if (isProcessing.get() || now - lastAnalyzedTimestamp < ANALYZE_INTERVAL_MS) {
                imageProxy.close()
                return@Analyzer
            }

            val mediaImage = imageProxy.image
            if (mediaImage == null) {
                imageProxy.close()
                return@Analyzer
            }

            isProcessing.set(true)
            lastAnalyzedTimestamp = now

            // Keep a lightweight scaled preview bitmap ready for on-demand AI capture
            try {
                imageProxyToBitmap(imageProxy)?.let { bmp ->
                    latestCapturedBitmap = scaleBitmapDown(bmp, 1024)
                }
            } catch (_: Exception) {
            }

            val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            runMlKitDetectors(inputImage) {
                isProcessing.set(false)
                imageProxy.close()
            }
        }
    }

    suspend fun analyzeBitmapOnDevice(bitmap: Bitmap): LiveVisionSnapshot = withContext(Dispatchers.Default) {
        val scaled = scaleBitmapDown(bitmap, 1024)
        latestCapturedBitmap = scaled
        val inputImage = InputImage.fromBitmap(scaled, 0)
        suspendCancellableCoroutine { cont ->
            runMlKitDetectors(inputImage) {
                if (cont.isActive) cont.resume(_liveSnapshot.value)
            }
        }
    }

    suspend fun loadAndAnalyzeUri(context: Context, uri: Uri): LiveVisionSnapshot? = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val raw = BitmapFactory.decodeStream(stream) ?: return@withContext null
                analyzeBitmapOnDevice(raw)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load selected photo", e)
            null
        }
    }

    private fun runMlKitDetectors(inputImage: InputImage, onComplete: () -> Unit) {
        var pending = 2
        var detectedLabels: List<DetectedVisionLabel> = _liveSnapshot.value.labels
        var detectedOcr: String = _liveSnapshot.value.ocrText

        fun finishOne() {
            pending -= 1
            if (pending <= 0) {
                _liveSnapshot.value = LiveVisionSnapshot(
                    labels = detectedLabels,
                    ocrText = detectedOcr,
                    lastUpdatedMs = System.currentTimeMillis()
                )
                onComplete()
            }
        }

        imageLabeler.process(inputImage)
            .addOnSuccessListener { labels ->
                detectedLabels = labels.take(6).map {
                    DetectedVisionLabel(
                        label = it.text,
                        confidencePercent = (it.confidence * 100f).roundToInt().coerceIn(1, 100)
                    )
                }
            }
            .addOnCompleteListener { finishOne() }

        textRecognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                detectedOcr = visionText.text.trim().take(800)
            }
            .addOnCompleteListener { finishOne() }
    }

    suspend fun askAiAboutVision(
        provider: AiProvider,
        apiKey: String,
        model: String,
        baseUrl: String,
        language: AppLanguage,
        question: String,
        overrideBitmap: Bitmap? = null
    ): String {
        val targetBitmap = overrideBitmap ?: latestCapturedBitmap
            ?: throw IllegalStateException("No camera frame or photo captured yet. Point the camera or pick an image first.")

        val jpegBase64 = bitmapToBase64Jpeg(scaleBitmapDown(targetBitmap, 1024))
        val snapshot = _liveSnapshot.value
        val onDeviceContext = buildString {
            if (snapshot.labels.isNotEmpty()) {
                append("On-device detected objects: ")
                append(snapshot.labels.joinToString(", ") { "${it.label} (${it.confidencePercent}%)" })
                append("\n")
            }
            if (snapshot.ocrText.isNotBlank()) {
                append("On-device OCR text: \"${snapshot.ocrText}\"\n")
            }
        }

        val systemPrompt = """
You are Mahi's Neural Lens Vision AI. Identify objects, products, electronics, documents, text, and surroundings clearly and accurately.
${language.instruction}
Keep explanations practical, concise (2-4 sentences unless more detail is asked), and directly answer the user's question about what is visible.
        """.trimIndent()

        val prompt = buildString {
            if (onDeviceContext.isNotBlank()) append(onDeviceContext).append("\n")
            append("User request: ")
            append(question.ifBlank { "Identify what you see in this scene, summarize any visible text, and explain what it is." })
        }

        return providerClient.analyzeImageWithProvider(
            provider = provider,
            apiKey = apiKey,
            model = model,
            baseUrl = baseUrl,
            base64Jpeg = jpegBase64,
            systemPrompt = systemPrompt,
            userPrompt = prompt
        )
    }

    fun formatOfflineMlKitSummary(language: AppLanguage): String {
        val snap = _liveSnapshot.value
        val labelsStr = if (snap.labels.isEmpty()) "None detected yet"
        else snap.labels.joinToString(", ") { "${it.label} (${it.confidencePercent}%)" }
        val ocrStr = snap.ocrText.ifBlank { "No text detected" }

        return when (language) {
            AppLanguage.URDU -> "آف لائن کیمرہ تجزیہ — اشیاء: $labelsStr | متن (OCR): $ocrStr"
            AppLanguage.ROMAN_URDU -> "Offline Camera Scan — Cheezein: $labelsStr | Likha hua text (OCR): $ocrStr"
            AppLanguage.ENGLISH -> "On-device Scan — Objects: $labelsStr | OCR Text: $ocrStr"
        }
    }

    private fun scaleBitmapDown(bitmap: Bitmap, maxDim: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= maxDim) return bitmap
        val scale = maxDim.toFloat() / longest.toFloat()
        val w = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val h = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, w, h, true)
    }

    private fun bitmapToBase64Jpeg(bitmap: Bitmap): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 82, stream)
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        if (imageProxy.format != ImageFormat.YUV_420_888) return null
        val yBuffer = imageProxy.planes[0].buffer
        val uBuffer = imageProxy.planes[1].buffer
        val vBuffer = imageProxy.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()
        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 75, out)
        val jpegBytes = out.toByteArray()
        val bmp = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size) ?: return null
        val rotation = imageProxy.imageInfo.rotationDegrees
        if (rotation == 0) return bmp
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
    }

    fun close() {
        try {
            textRecognizer.close()
            imageLabeler.close()
        } catch (_: Exception) {
        }
    }
}
