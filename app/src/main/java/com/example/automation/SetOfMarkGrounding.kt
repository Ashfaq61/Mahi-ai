package com.example.automation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.util.Base64
import com.example.api.AiProvider
import com.example.api.ProviderClient
import com.example.vision.VisionBitmapUtils
import org.json.JSONObject
import java.io.ByteArrayOutputStream

data class SetOfMarkAnnotatedResult(
    val jpegBytes: ByteArray,
    val markMap: Map<Int, ScreenElementNode>,
    val scaledWidth: Int,
    val scaledHeight: Int
)

object SetOfMarkGrounding {

    /**
     * Scales a screenshot down cleanly and masks any password fields with solid black boxes
     * so no password text or visual keyboard digits ever leave the device.
     */
    fun redactPasswordsToJpeg(
        bitmap: Bitmap,
        snapshot: ScreenSnapshot,
        screenWidth: Int,
        screenHeight: Int,
        maxDim: Int = 768,
        quality: Int = 70
    ): ByteArray {
        val scaled = VisionBitmapUtils.scaleDown(bitmap, maxDim)
        val mutable = scaled.copy(Bitmap.Config.ARGB_8888, true)
        drawPasswordMasks(mutable, snapshot.elements, screenWidth, screenHeight)
        val out = ByteArrayOutputStream()
        mutable.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    /**
     * Draws Set-of-Mark numbered badges [1], [2]... over clickable/editable elements (max 35)
     * after masking any password regions, and returns JPEG bytes + badge index mapping.
     */
    fun annotateSnapshot(
        bitmap: Bitmap,
        snapshot: ScreenSnapshot,
        screenWidth: Int,
        screenHeight: Int,
        maxDim: Int = 768,
        quality: Int = 75
    ): SetOfMarkAnnotatedResult {
        val scaled = VisionBitmapUtils.scaleDown(bitmap, maxDim)
        val mutable = scaled.copy(Bitmap.Config.ARGB_8888, true)
        drawPasswordMasks(mutable, snapshot.elements, screenWidth, screenHeight)

        val canvas = Canvas(mutable)
        val scaleX = mutable.width.toFloat() / screenWidth.coerceAtLeast(1)
        val scaleY = mutable.height.toFloat() / screenHeight.coerceAtLeast(1)

        val candidates = snapshot.elements
            .filter { !it.isPassword && (it.isClickable || it.isEditable || it.region != "content") }
            .ifEmpty { snapshot.elements.filter { !it.isPassword } }
            .take(35)

        val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
        }
        val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }

        val markMap = LinkedHashMap<Int, ScreenElementNode>(candidates.size)
        candidates.forEachIndexed { idx, el ->
            val markNumber = idx + 1
            markMap[markNumber] = el

            val left = (el.bounds.left * scaleX).coerceIn(2f, mutable.width - 12f)
            val top = (el.bounds.top * scaleY).coerceIn(2f, mutable.height - 12f)
            val right = (el.bounds.right * scaleX).coerceIn(left + 8f, mutable.width - 2f)
            val bottom = (el.bounds.bottom * scaleY).coerceIn(top + 8f, mutable.height - 2f)

            val accent = if (el.isEditable) Color.parseColor("#FFFF2A85") else Color.parseColor("#FF00E5FF")
            boxPaint.color = accent
            badgeBgPaint.color = Color.parseColor("#DC0A1020")

            canvas.drawRect(left, top, right, bottom, boxPaint)

            val label = markNumber.toString()
            val pillW = if (markNumber >= 10) 32f else 24f
            val pillH = 22f
            val pillRect = RectF(left, (top - 2f).coerceAtLeast(0f), (left + pillW).coerceAtMost(mutable.width.toFloat()), ((top - 2f).coerceAtLeast(0f) + pillH).coerceAtMost(mutable.height.toFloat()))
            canvas.drawRoundRect(pillRect, 6f, 6f, badgeBgPaint)
            boxPaint.color = accent
            canvas.drawRoundRect(pillRect, 6f, 6f, boxPaint)
            canvas.drawText(label, pillRect.centerX(), pillRect.centerY() + 6f, textPaint)
        }

        val out = ByteArrayOutputStream()
        mutable.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return SetOfMarkAnnotatedResult(
            jpegBytes = out.toByteArray(),
            markMap = markMap,
            scaledWidth = mutable.width,
            scaledHeight = mutable.height
        )
    }

    private fun drawPasswordMasks(
        mutableBitmap: Bitmap,
        elements: List<ScreenElementNode>,
        screenWidth: Int,
        screenHeight: Int
    ) {
        val pwdElements = elements.filter { it.isPassword }
        if (pwdElements.isEmpty()) return
        val canvas = Canvas(mutableBitmap)
        val maskPaint = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.FILL
        }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.LTGRAY
            textSize = 14f
            textAlign = Paint.Align.CENTER
        }
        val sx = mutableBitmap.width.toFloat() / screenWidth.coerceAtLeast(1)
        val sy = mutableBitmap.height.toFloat() / screenHeight.coerceAtLeast(1)
        pwdElements.forEach { el ->
            val r = Rect(
                (el.bounds.left * sx).toInt().coerceIn(0, mutableBitmap.width),
                (el.bounds.top * sy).toInt().coerceIn(0, mutableBitmap.height),
                (el.bounds.right * sx).toInt().coerceIn(0, mutableBitmap.width),
                (el.bounds.bottom * sy).toInt().coerceIn(0, mutableBitmap.height)
            )
            if (!r.isEmpty) {
                canvas.drawRect(r, maskPaint)
                canvas.drawText("PASSWORD", r.exactCenterX(), r.exactCenterY() + 4f, labelPaint)
            }
        }
    }

    fun parseSetOfMarkResponse(
        raw: String,
        markMap: Map<Int, ScreenElementNode>,
        screenWidth: Int,
        screenHeight: Int,
        providerName: String
    ): GroundingPoint? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start >= 0 && end > start) {
            try {
                val obj = JSONObject(raw.substring(start, end + 1))
                val idx = obj.optInt("index", obj.optInt("mark", obj.optInt("box", -1)))
                val matchedNode = markMap[idx]
                if (matchedNode != null) {
                    val xPx = matchedNode.centerX.toFloat().coerceIn(4f, screenWidth - 4f)
                    val yPx = matchedNode.centerY.toFloat().coerceIn(4f, screenHeight - 4f)
                    return GroundingPoint(
                        xPx = xPx,
                        yPx = yPx,
                        xNorm = xPx / screenWidth.coerceAtLeast(1),
                        yNorm = yPx / screenHeight.coerceAtLeast(1),
                        providerName = "$providerName [SoM #$idx]",
                        rawResponse = raw.take(180)
                    )
                }
            } catch (_: Exception) {
            }
        }
        return GuiGroundingParser.parsePoint(raw, screenWidth, screenHeight, "$providerName [SoM]")
    }

    suspend fun locateTarget(
        bitmap: Bitmap,
        snapshot: ScreenSnapshot,
        targetDescription: String,
        screenWidth: Int,
        screenHeight: Int,
        fallbackGrounding: GuiGroundingProvider,
        providerClient: ProviderClient,
        provider: AiProvider?,
        apiKey: String?,
        model: String?,
        baseUrl: String?
    ): Result<GroundingPoint> {
        val annotated = annotateSnapshot(bitmap, snapshot, screenWidth, screenHeight)
        if (provider != null && !apiKey.isNullOrBlank() && !model.isNullOrBlank() && !baseUrl.isNullOrBlank()) {
            try {
                val b64 = Base64.encodeToString(annotated.jpegBytes, Base64.NO_WRAP)
                val markSummary = annotated.markMap.entries.take(25).joinToString(", ") { (k, v) ->
                    "[$k]=${v.bestLabel.ifBlank { v.className }.take(24)}"
                }
                val reply = providerClient.analyzeImageWithProvider(
                    provider = provider,
                    apiKey = apiKey,
                    model = model,
                    baseUrl = baseUrl,
                    base64Jpeg = b64,
                    systemPrompt = """
You are a precise Android Set-of-Mark GUI grounding assistant.
The screenshot has numbered boxes [1], [2], ... drawn over UI controls.
If one of the numbered boxes matches the target, return ONLY: {"index": N}
If the target has no numbered box, return ONLY: {"point":[y,x]} with y,x normalized 0..1000.
Do not add prose.
                    """.trimIndent(),
                    userPrompt = "Target: $targetDescription\nBoxes: $markSummary"
                )
                parseSetOfMarkResponse(
                    raw = reply,
                    markMap = annotated.markMap,
                    screenWidth = screenWidth,
                    screenHeight = screenHeight,
                    providerName = provider.shortName
                )?.let { return Result.success(it) }
            } catch (_: Exception) {
            }
        }
        return fallbackGrounding.locateTarget(annotated.jpegBytes, targetDescription, screenWidth, screenHeight)
    }

    fun isThinAccessibilityTree(snapshot: ScreenSnapshot): Boolean {
        val meaningful = snapshot.elements.count {
            !it.isPassword && (it.bestLabel.isNotBlank() || it.isClickable || it.isEditable)
        }
        val unlabeledClickables = snapshot.elements.count { it.isClickable && it.bestLabel.isBlank() }
        val isWebOrCanvas = snapshot.elements.any {
            it.className.contains("WebView", ignoreCase = true) ||
                it.className.contains("SurfaceView", ignoreCase = true) ||
                it.className.contains("Flutter", ignoreCase = true)
        }
        return meaningful < 8 || (isWebOrCanvas && meaningful < 14) || (unlabeledClickables >= 4 && meaningful < 10)
    }
}
