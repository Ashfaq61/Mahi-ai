package com.example.automation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

data class GroundingPoint(
    val x: Float,
    val y: Float,
    val confidence: Float = 0.8f,
    val label: String = "",
    val providerName: String = "gemini"
)

interface GuiGroundingProvider {
    val providerName: String
    suspend fun groundTarget(
        screenshotJpeg: ByteArray,
        targetQuery: String,
        screenWidth: Int,
        screenHeight: Int
    ): GroundingPoint?
}

class GeminiGroundingProvider(
    private val apiKey: String,
    private val modelId: String = "gemini-2.5-flash",
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()
) : GuiGroundingProvider {
    override val providerName: String = "Gemini Vision SoM"

    override suspend fun groundTarget(
        screenshotJpeg: ByteArray,
        targetQuery: String,
        screenWidth: Int,
        screenHeight: Int
    ): GroundingPoint? {
        if (apiKey.isBlank() || screenshotJpeg.isEmpty()) return null
        return try {
            val b64 = Base64.encodeToString(screenshotJpeg, Base64.NO_WRAP)
            val prompt = "Locate UI element matching: '$targetQuery' on this ${screenWidth}x${screenHeight} screenshot. " +
                "Return strict JSON only: {\"x\": <pixel_x>, \"y\": <pixel_y>, \"confidence\": <0..1>, \"label\": \"...\"}"
            val payload = JSONObject().apply {
                put(
                    "contents",
                    JSONArray().put(
                        JSONObject().put(
                            "parts",
                            JSONArray()
                                .put(JSONObject().put("text", prompt))
                                .put(
                                    JSONObject().put(
                                        "inlineData",
                                        JSONObject().put("mimeType", "image/jpeg").put("data", b64)
                                    )
                                )
                        )
                    )
                )
            }
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelId:generateContent"
            val req = Request.Builder()
                .url(url)
                .header("x-goog-api-key", apiKey)
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val raw = resp.body?.string().orEmpty()
                val text = JSONObject(raw)
                    .optJSONArray("candidates")?.optJSONObject(0)
                    ?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)
                    ?.optString("text").orEmpty()
                SetOfMarkGrounding.parseSetOfMarkResponse(text, providerName)
            }
        } catch (_: Exception) {
            null
        }
    }
}

class RemoteHttpGroundingProvider(
    private val endpointUrl: String,
    private val bearerToken: String,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()
) : GuiGroundingProvider {
    override val providerName: String = "Remote HTTP Grounding"

    override suspend fun groundTarget(
        screenshotJpeg: ByteArray,
        targetQuery: String,
        screenWidth: Int,
        screenHeight: Int
    ): GroundingPoint? {
        if (!endpointUrl.startsWith("https://")) return null
        return try {
            val payload = JSONObject().apply {
                put("query", targetQuery)
                put("width", screenWidth)
                put("height", screenHeight)
                put("image_base64", Base64.encodeToString(screenshotJpeg, Base64.NO_WRAP))
            }
            val builder = Request.Builder()
                .url(endpointUrl)
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
            if (bearerToken.isNotBlank()) {
                builder.header("Authorization", "Bearer $bearerToken")
            }
            httpClient.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val raw = resp.body?.string().orEmpty()
                SetOfMarkGrounding.parseSetOfMarkResponse(raw, providerName)
            }
        } catch (_: Exception) {
            null
        }
    }
}

class CompositeGuiGroundingProvider(
    private val providers: List<GuiGroundingProvider>
) : GuiGroundingProvider {
    override val providerName: String = "Composite Grounding"

    override suspend fun groundTarget(
        screenshotJpeg: ByteArray,
        targetQuery: String,
        screenWidth: Int,
        screenHeight: Int
    ): GroundingPoint? {
        for (provider in providers) {
            val hit = provider.groundTarget(screenshotJpeg, targetQuery, screenWidth, screenHeight)
            if (hit != null && hit.x > 0f && hit.y > 0f) return hit
        }
        return null
    }
}

object SetOfMarkGrounding {
    fun isThinAccessibilityTree(snapshot: ScreenSnapshot): Boolean {
        return snapshot.elements.count { it.isClickable || it.isEditable } < 2
    }

    fun redactPasswordsToJpeg(bitmap: Bitmap, snapshot: ScreenSnapshot): ByteArray {
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(copy)
        val blackPaint = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.FILL
        }
        snapshot.elements.filter { it.isPassword }.forEach { pwd ->
            canvas.drawRect(Rect(pwd.bounds), blackPaint)
        }
        val out = ByteArrayOutputStream()
        copy.compress(Bitmap.CompressFormat.JPEG, 78, out)
        return out.toByteArray()
    }

    fun annotateSnapshot(bitmap: Bitmap, snapshot: ScreenSnapshot): Bitmap {
        val annotated = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(annotated)
        val boxPaint = Paint().apply {
            color = Color.CYAN
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        val badgePaint = Paint().apply {
            color = Color.MAGENTA
            style = Paint.Style.FILL
        }
        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 26f
            isFakeBoldText = true
        }
        val blackPaint = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }

        snapshot.elements.take(40).forEach { el ->
            if (el.isPassword) {
                canvas.drawRect(el.bounds, blackPaint)
            } else {
                canvas.drawRect(el.bounds, boxPaint)
                val bx = el.bounds.left.toFloat().coerceAtLeast(4f)
                val by = el.bounds.top.toFloat().coerceAtLeast(32f)
                canvas.drawRect(bx, by - 28f, bx + 44f, by, badgePaint)
                canvas.drawText(el.index.toString(), bx + 8f, by - 6f, textPaint)
            }
        }
        return annotated
    }

    fun parseSetOfMarkResponse(rawText: String, providerName: String = "SoM"): GroundingPoint? {
        val start = rawText.indexOf('{')
        val end = rawText.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            val obj = JSONObject(rawText.substring(start, end + 1))
            val x = obj.optDouble("x", Double.NaN).toFloat()
            val y = obj.optDouble("y", Double.NaN).toFloat()
            if (x.isNaN() || y.isNaN()) return null
            GroundingPoint(
                x = x,
                y = y,
                confidence = obj.optDouble("confidence", 0.8).toFloat().coerceIn(0f, 1f),
                label = obj.optString("label"),
                providerName = providerName
            )
        } catch (_: Exception) {
            null
        }
    }
}
