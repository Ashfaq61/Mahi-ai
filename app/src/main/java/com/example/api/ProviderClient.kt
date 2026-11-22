package com.example.api

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class ProviderChatMessage(
    val role: String, // "user" | "assistant"
    val content: String
)

data class ProviderAttemptResult(
    val text: String,
    val providerUsed: AiProvider,
    val modelUsed: String,
    val latencyMs: Long
)

class ProviderException(
    val category: ApiErrorCategory,
    val httpCode: Int = 0,
    message: String
) : Exception(message)

class ProviderClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }

    suspend fun testConnection(
        provider: AiProvider,
        apiKey: String,
        model: String = provider.defaultModels.first(),
        baseUrl: String = provider.defaultBaseUrl
    ): Result<String> = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            val reply = generateChatReply(
                provider = provider,
                apiKey = apiKey,
                model = model,
                baseUrl = baseUrl,
                systemPrompt = "You are a connectivity probe. Reply only with OK.",
                history = emptyList(),
                userMessage = "Reply OK."
            )
            val ms = System.currentTimeMillis() - start
            Result.success("Connected ($model) in ${ms}ms -> ${reply.take(40)}")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun generateWithFallback(
        keyStore: SecureApiKeyStore,
        systemPrompt: String,
        history: List<ProviderChatMessage>,
        userMessage: String,
        modelResolver: ((AiProvider) -> String)? = null
    ): ProviderAttemptResult {
        val candidates = keyStore.allDecryptedSlotsForFallback()
        if (candidates.isEmpty()) {
            throw ProviderException(
                ApiErrorCategory.INVALID_KEY,
                message = "No API key configured. Open API Settings and add a Gemini, Groq, OpenRouter, or OpenAI-compatible key."
            )
        }
        var lastErr: Exception? = null
        for ((prov, slot, rawKey) in candidates) {
            val resolvedModel = modelResolver?.invoke(prov)?.takeIf { it.isNotBlank() }
                ?: keyStore.state.value.selectedModels[prov]
                ?: slot.selectedModel
            val baseUrl = keyStore.state.value.customBaseUrls[prov] ?: slot.baseUrl
            val start = System.currentTimeMillis()
            try {
                val reply = generateChatReply(
                    provider = prov,
                    apiKey = rawKey,
                    model = resolvedModel,
                    baseUrl = baseUrl,
                    systemPrompt = systemPrompt,
                    history = history,
                    userMessage = userMessage
                )
                return ProviderAttemptResult(
                    text = reply,
                    providerUsed = prov,
                    modelUsed = resolvedModel,
                    latencyMs = System.currentTimeMillis() - start
                )
            } catch (e: ProviderException) {
                lastErr = e
                if (e.category == ApiErrorCategory.INVALID_KEY ||
                    e.category == ApiErrorCategory.QUOTA_EXCEEDED ||
                    e.category == ApiErrorCategory.MODEL_NOT_FOUND ||
                    e.httpCode >= 500 ||
                    e.category == ApiErrorCategory.NETWORK_ERROR
                ) {
                    continue
                }
                throw e
            } catch (e: Exception) {
                lastErr = e
            }
        }
        throw (lastErr ?: ProviderException(ApiErrorCategory.NETWORK_ERROR, message = "All configured API slots failed."))
    }

    suspend fun generateChatReply(
        provider: AiProvider,
        apiKey: String,
        model: String,
        baseUrl: String,
        systemPrompt: String,
        history: List<ProviderChatMessage>,
        userMessage: String
    ): String = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) {
            throw ProviderException(ApiErrorCategory.INVALID_KEY, 401, "API key is empty for ${provider.displayName}")
        }
        val cleanBase = baseUrl.trim().trimEnd('/').ifBlank { provider.defaultBaseUrl }
        if (!cleanBase.startsWith("https://")) {
            throw ProviderException(ApiErrorCategory.NETWORK_ERROR, 0, "Only HTTPS provider endpoints are permitted.")
        }

        if (provider == AiProvider.GEMINI) {
            callGeminiGenerateContent(cleanBase, cleanKey, model, systemPrompt, history, userMessage, null)
        } else {
            callOpenAiCompatibleChat(provider, cleanBase, cleanKey, model, systemPrompt, history, userMessage)
        }
    }

    suspend fun generateVisionReply(
        provider: AiProvider,
        apiKey: String,
        model: String,
        baseUrl: String,
        systemPrompt: String,
        question: String,
        imageJpegBytes: ByteArray
    ): String = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        val cleanBase = baseUrl.trim().trimEnd('/').ifBlank { provider.defaultBaseUrl }
        if (cleanKey.isBlank()) {
            throw ProviderException(ApiErrorCategory.INVALID_KEY, 401, "API key is required for Vision AI.")
        }
        if (!cleanBase.startsWith("https://")) {
            throw ProviderException(ApiErrorCategory.NETWORK_ERROR, 0, "Only HTTPS provider endpoints are permitted.")
        }
        if (provider == AiProvider.GEMINI) {
            callGeminiGenerateContent(cleanBase, cleanKey, model, systemPrompt, emptyList(), question, imageJpegBytes)
        } else {
            val b64 = Base64.encodeToString(imageJpegBytes, Base64.NO_WRAP)
            val messages = JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", systemPrompt))
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", JSONArray().apply {
                        put(JSONObject().put("type", "text").put("text", question))
                        put(
                            JSONObject().put("type", "image_url")
                                .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64"))
                        )
                    })
                })
            }
            executeOpenAiStyle(cleanBase, cleanKey, model, messages)
        }
    }

    suspend fun fetchRawJsonGet(url: String, headers: Map<String, String> = emptyMap()): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            if (!url.startsWith("https://")) {
                throw ProviderException(ApiErrorCategory.NETWORK_ERROR, 0, "Cleartext HTTP blocked by security policy.")
            }
            val reqBuilder = Request.Builder().url(url).get()
            headers.forEach { (k, v) -> reqBuilder.addHeader(k, v) }
            httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                resp.code to (resp.body?.string().orEmpty())
            }
        }

    private fun callGeminiGenerateContent(
        baseUrl: String,
        apiKey: String,
        model: String,
        systemPrompt: String,
        history: List<ProviderChatMessage>,
        userMessage: String,
        jpegBytes: ByteArray?
    ): String {
        val cleanModel = model.removePrefix("models/").ifBlank { "gemini-2.5-flash" }
        val url = "$baseUrl/v1beta/models/$cleanModel:generateContent"
        val contents = JSONArray().apply {
            history.takeLast(12).forEach { msg ->
                val role = if (msg.role == "assistant") "model" else "user"
                put(JSONObject().put("role", role).put("parts", JSONArray().put(JSONObject().put("text", msg.content))))
            }
            val userParts = JSONArray().apply {
                put(JSONObject().put("text", userMessage))
                if (jpegBytes != null && jpegBytes.isNotEmpty()) {
                    put(
                        JSONObject().put(
                            "inline_data",
                            JSONObject()
                                .put("mime_type", "image/jpeg")
                                .put("data", Base64.encodeToString(jpegBytes, Base64.NO_WRAP))
                        )
                    )
                }
            }
            put(JSONObject().put("role", "user").put("parts", userParts))
        }
        val bodyJson = JSONObject().apply {
            if (systemPrompt.isNotBlank()) {
                put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
            }
            put("contents", contents)
        }

        val req = Request.Builder()
            .url(url)
            .addHeader("x-goog-api-key", apiKey)
            .post(bodyJson.toString().toRequestBody(JSON_MEDIA))
            .build()

        return httpClient.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw mapHttpFailure("Gemini", resp.code, raw)
            val root = JSONObject(raw)
            val candidates = root.optJSONArray("candidates") ?: throw ProviderException(ApiErrorCategory.UNKNOWN, resp.code, "Empty Gemini response")
            val first = candidates.optJSONObject(0) ?: throw ProviderException(ApiErrorCategory.UNKNOWN, resp.code, "No candidate returned")
            val parts = first.optJSONObject("content")?.optJSONArray("parts")
            val text = buildString {
                if (parts != null) {
                    for (i in 0 until parts.length()) {
                        append(parts.optJSONObject(i)?.optString("text").orEmpty())
                    }
                }
            }.trim()
            if (text.isBlank()) throw ProviderException(ApiErrorCategory.UNKNOWN, resp.code, "Gemini returned blank reply")
            text
        }
    }

    private fun callOpenAiCompatibleChat(
        provider: AiProvider,
        baseUrl: String,
        apiKey: String,
        model: String,
        systemPrompt: String,
        history: List<ProviderChatMessage>,
        userMessage: String
    ): String {
        val messages = JSONArray().apply {
            if (systemPrompt.isNotBlank()) {
                put(JSONObject().put("role", "system").put("content", systemPrompt))
            }
            history.takeLast(12).forEach { msg ->
                put(JSONObject().put("role", if (msg.role == "assistant") "assistant" else "user").put("content", msg.content))
            }
            put(JSONObject().put("role", "user").put("content", userMessage))
        }
        return executeOpenAiStyle(baseUrl, apiKey, model.ifBlank { provider.defaultModels.first() }, messages)
    }

    private fun executeOpenAiStyle(baseUrl: String, apiKey: String, model: String, messages: JSONArray): String {
        val endpoint = if (baseUrl.endsWith("/chat/completions")) baseUrl else "$baseUrl/chat/completions"
        val payload = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("temperature", 0.5)
        }
        val req = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("HTTP-Referer", "https://mahi-ai.local")
            .addHeader("X-Title", "Mahi AI")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()

        return httpClient.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw mapHttpFailure("OpenAI-Compatible", resp.code, raw)
            val root = JSONObject(raw)
            val content = root.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                ?.trim()
                .orEmpty()
            if (content.isBlank()) throw ProviderException(ApiErrorCategory.UNKNOWN, resp.code, "Empty model reply")
            content
        }
    }

    private fun mapHttpFailure(tag: String, code: Int, body: String): ProviderException {
        val category = when (code) {
            401, 403 -> ApiErrorCategory.INVALID_KEY
            404 -> ApiErrorCategory.MODEL_NOT_FOUND
            429 -> ApiErrorCategory.QUOTA_EXCEEDED
            in 500..599 -> ApiErrorCategory.NETWORK_ERROR
            else -> ApiErrorCategory.UNKNOWN
        }
        val cleanErr = runCatching {
            JSONObject(body).optJSONObject("error")?.optString("message").orEmpty()
        }.getOrDefault("").ifBlank { body.take(180) }
        return ProviderException(category, code, "$tag HTTP $code: $cleanErr")
    }
}
