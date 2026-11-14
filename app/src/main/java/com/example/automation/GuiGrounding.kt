package com.example.automation

import android.util.Base64
import com.example.api.AiProvider
import com.example.api.ProviderClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class GroundingPoint(
    val xPx: Float,
    val yPx: Float,
    val xNorm: Float,
    val yNorm: Float,
    val providerName: String,
    val rawResponse: String = ""
)

/**
 * Pluggable visual GUI point grounding interface.
 * Converts a target UI element description + downscaled screenshot JPEG into exact pixel coordinates.
 */
interface GuiGroundingProvider {
    val name: String
    suspend fun locateTarget(
        jpegBytes: ByteArray,
        targetDescription: String,
        screenWidth: Int,
        screenHeight: Int
    ): Result<GroundingPoint>
}

object GuiGroundingParser {
    /**
     * Supports standard vision-grounding responses:
     * 1) {"point":[y, x]} in 0..1000 normalized scale (Gemini / UI-TARS / Qwen-UI-R1) or 0..1 scale
     * 2) {"xNorm":0.42,"yNorm":0.76}
     * 3) {"box_2d":[ymin,xmin,ymax,xmax]} or {"bbox":[ymin,xmin,ymax,xmax]}
     * 4) Plain coordinate pair inside text like [720, 890]
     */
    fun parsePoint(
        raw: String,
        screenWidth: Int,
        screenHeight: Int,
        providerName: String
    ): GroundingPoint? {
        val w = screenWidth.coerceAtLeast(1)
        val h = screenHeight.coerceAtLeast(1)
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start >= 0 && end > start) {
            try {
                val obj = JSONObject(raw.substring(start, end + 1))

                // Case 1: {"point":[y, x]}
                val pointArr = obj.optJSONArray("point")
                if (pointArr != null && pointArr.length() >= 2) {
                    val yVal = pointArr.optDouble(0, Double.NaN)
                    val xVal = pointArr.optDouble(1, Double.NaN)
                    toGroundingPoint(xVal, yVal, w, h, providerName, raw, assumeYXScale = true)?.let { return it }
                }

                // Case 2: {"xNorm":..., "yNorm":...} or {"x":..., "y":...}
                val xDirect = when {
                    obj.has("xNorm") && !obj.isNull("xNorm") -> obj.optDouble("xNorm", Double.NaN)
                    obj.has("x") && !obj.isNull("x") -> obj.optDouble("x", Double.NaN)
                    else -> Double.NaN
                }
                val yDirect = when {
                    obj.has("yNorm") && !obj.isNull("yNorm") -> obj.optDouble("yNorm", Double.NaN)
                    obj.has("y") && !obj.isNull("y") -> obj.optDouble("y", Double.NaN)
                    else -> Double.NaN
                }
                if (!xDirect.isNaN() && !yDirect.isNaN()) {
                    toGroundingPoint(xDirect, yDirect, w, h, providerName, raw, assumeYXScale = false)?.let { return it }
                }

                // Case 3: bounding box [ymin, xmin, ymax, xmax]
                val boxArr = obj.optJSONArray("box_2d") ?: obj.optJSONArray("bbox")
                if (boxArr != null && boxArr.length() >= 4) {
                    val yMin = boxArr.optDouble(0, Double.NaN)
                    val xMin = boxArr.optDouble(1, Double.NaN)
                    val yMax = boxArr.optDouble(2, Double.NaN)
                    val xMax = boxArr.optDouble(3, Double.NaN)
                    if (!yMin.isNaN() && !xMin.isNaN() && !yMax.isNaN() && !xMax.isNaN()) {
                        val cx = (xMin + xMax) / 2.0
                        val cy = (yMin + yMax) / 2.0
                        toGroundingPoint(cx, cy, w, h, providerName, raw, assumeYXScale = true)?.let { return it }
                    }
                }
            } catch (_: Exception) {
            }
        }

        // Fallback regex match for [y, x] in 0..1000
        val bracketMatch = Regex("""\[\s*(\d{1,4})\s*,\s*(\d{1,4})\s*]""").find(raw)
        if (bracketMatch != null) {
            val yVal = bracketMatch.groupValues[1].toDoubleOrNull() ?: Double.NaN
            val xVal = bracketMatch.groupValues[2].toDoubleOrNull() ?: Double.NaN
            toGroundingPoint(xVal, yVal, w, h, providerName, raw, assumeYXScale = true)?.let { return it }
        }
        return null
    }

    private fun toGroundingPoint(
        xRaw: Double,
        yRaw: Double,
        screenWidth: Int,
        screenHeight: Int,
        providerName: String,
        rawResponse: String,
        assumeYXScale: Boolean
    ): GroundingPoint? {
        if (xRaw.isNaN() || yRaw.isNaN() || xRaw < 0.0 || yRaw < 0.0) return null
        val (xNorm, yNorm) = when {
            xRaw <= 1.0 && yRaw <= 1.0 -> xRaw.toFloat() to yRaw.toFloat()
            xRaw <= 1000.0 && yRaw <= 1000.0 -> (xRaw / 1000.0).toFloat() to (yRaw / 1000.0).toFloat()
            !assumeYXScale && xRaw <= screenWidth && yRaw <= screenHeight ->
                (xRaw / screenWidth).toFloat() to (yRaw / screenHeight).toFloat()
            else -> return null
        }
        val clampedX = xNorm.coerceIn(0.02f, 0.98f)
        val clampedY = yNorm.coerceIn(0.02f, 0.98f)
        return GroundingPoint(
            xPx = clampedX * screenWidth,
            yPx = clampedY * screenHeight,
            xNorm = clampedX,
            yNorm = clampedY,
            providerName = providerName,
            rawResponse = rawResponse.take(180)
        )
    }
}

/**
 * Default visual GUI grounding provider using the user's configured Gemini / Multimodal vision provider.
 * Sends a downscaled in-memory screenshot (never persisted) asking for normalized {"point":[y,x]} on 0..1000 scale.
 */
class GeminiGroundingProvider(
    private val providerClient: ProviderClient,
    private val provider: AiProvider?,
    private val apiKey: String?,
    private val model: String?,
    private val baseUrl: String?
) : GuiGroundingProvider {
    override val name: String = provider?.shortName?.let { "$it Vision Grounding" } ?: "Gemini Vision Grounding"

    override suspend fun locateTarget(
        jpegBytes: ByteArray,
        targetDescription: String,
        screenWidth: Int,
        screenHeight: Int
    ): Result<GroundingPoint> {
        if (provider == null || apiKey.isNullOrBlank() || model.isNullOrBlank() || baseUrl.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Configure an AI provider API key for visual GUI grounding."))
        }
        return try {
            val b64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
            val reply = providerClient.analyzeImageWithProvider(
                provider = provider,
                apiKey = apiKey,
                model = model,
                baseUrl = baseUrl,
                base64Jpeg = b64,
                systemPrompt = """
You are a precise Android GUI grounding model. Locate the center of the requested UI element on screen.
Return ONLY valid JSON: {"point":[y,x]} where y and x are integers from 0 to 1000 (0=top/left edge, 1000=bottom/right edge).
Do not include any explanation.
                """.trimIndent(),
                userPrompt = "Locate target button/element: $targetDescription"
            )
            val parsed = GuiGroundingParser.parsePoint(reply, screenWidth, screenHeight, name)
            if (parsed != null) {
                Result.success(parsed)
            } else {
                Result.failure(IllegalStateException("Grounding model did not return valid coordinates ($reply)."))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

/**
 * Optional self-hosted GUI-grounding HTTP provider (e.g., UI-TARS-1.5-7B, Qwen2.5-VL-UI-R1, OmniParser).
 * Disabled by default; nothing is sent unless the user enters a hosted endpoint URL and enables it.
 */
class RemoteHttpGroundingProvider(
    private val enabled: Boolean,
    private val endpointUrl: String,
    private val bearerToken: String? = null,
    private val okHttpClient: OkHttpClient = ProviderClient.defaultOkHttpClient()
) : GuiGroundingProvider {
    override val name: String = "Hosted GUI Grounding"

    companion object {
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    override suspend fun locateTarget(
        jpegBytes: ByteArray,
        targetDescription: String,
        screenWidth: Int,
        screenHeight: Int
    ): Result<GroundingPoint> = withContext(Dispatchers.IO) {
        if (!enabled || endpointUrl.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Hosted GUI grounding is disabled."))
        }
        try {
            val payload = JSONObject().apply {
                put("image_base64", Base64.encodeToString(jpegBytes, Base64.NO_WRAP))
                put("target", targetDescription)
                put("prompt", "Locate UI element: $targetDescription. Return {\"point\":[y,x]} normalized 0..1000.")
                put("width", screenWidth)
                put("height", screenHeight)
            }.toString().toRequestBody(JSON_TYPE)

            val builder = Request.Builder()
                .url(endpointUrl.trim())
                .post(payload)
            if (!bearerToken.isNullOrBlank()) {
                builder.header("Authorization", "Bearer ${bearerToken.trim()}")
            }

            okHttpClient.newCall(builder.build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IllegalStateException("Hosted grounding HTTP ${response.code}: ${body.take(120)}")
                    )
                }
                val parsed = GuiGroundingParser.parsePoint(body, screenWidth, screenHeight, name)
                if (parsed != null) {
                    Result.success(parsed)
                } else {
                    Result.failure(IllegalStateException("Hosted grounding response had no coordinates."))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

class CompositeGuiGroundingProvider(
    private val remoteProvider: RemoteHttpGroundingProvider?,
    private val fallbackProvider: GuiGroundingProvider?
) : GuiGroundingProvider {
    override val name: String
        get() = remoteProvider?.name ?: fallbackProvider?.name ?: "GUI Grounding"

    override suspend fun locateTarget(
        jpegBytes: ByteArray,
        targetDescription: String,
        screenWidth: Int,
        screenHeight: Int
    ): Result<GroundingPoint> {
        if (remoteProvider != null) {
            val remoteRes = remoteProvider.locateTarget(jpegBytes, targetDescription, screenWidth, screenHeight)
            if (remoteRes.isSuccess) return remoteRes
        }
        if (fallbackProvider != null) {
            return fallbackProvider.locateTarget(jpegBytes, targetDescription, screenWidth, screenHeight)
        }
        return Result.failure(IllegalStateException("No GUI grounding provider configured."))
    }
}
