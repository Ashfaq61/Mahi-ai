package com.example.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

enum class ApiErrorCategory {
    MISSING_KEY,
    INVALID_KEY,
    QUOTA_EXCEEDED,
    UNSUPPORTED_MODEL,
    CONNECTION_FAILED,
    UNKNOWN
}

class ProviderApiException(
    val category: ApiErrorCategory,
    override val message: String,
    val httpCode: Int? = null
) : Exception(message)

data class ConnectionTestResult(
    val success: Boolean,
    val status: ConnectionStatus,
    val latencyMs: Long,
    val discoveredModels: List<String> = emptyList(),
    val usageInfo: String? = null,
    val errorCategory: ApiErrorCategory? = null,
    val errorMessage: String? = null
)

data class ProviderChatMessage(
    val role: String,
    val text: String
)

/**
 * Real HTTP client for testing connections, discovering models, reading usage metadata,
 * and running inference across Google Gemini, OpenRouter, Groq, Hugging Face,
 * and Custom OpenAI-compatible endpoints.
 */
class ProviderClient(
    private val okHttpClient: OkHttpClient = defaultOkHttpClient()
) {
    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun defaultOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    suspend fun testConnection(
        provider: AiProvider,
        apiKey: String,
        selectedModel: String,
        baseUrl: String = provider.defaultBaseUrl
    ): ConnectionTestResult = withContext(Dispatchers.IO) {
        val trimmedKey = apiKey.trim()
        if (trimmedKey.isEmpty()) {
            return@withContext ConnectionTestResult(
                success = false,
                status = ConnectionStatus.DISCONNECTED,
                latencyMs = 0L,
                errorCategory = ApiErrorCategory.MISSING_KEY,
                errorMessage = "No API key provided."
            )
        }

        val cleanBase = baseUrl.trim().trimEnd('/').ifBlank { provider.defaultBaseUrl }
        val startMs = System.currentTimeMillis()

        try {
            when (provider) {
                AiProvider.GEMINI -> testGeminiConnection(cleanBase, trimmedKey, selectedModel, startMs)
                AiProvider.OPENROUTER -> testOpenRouterConnection(cleanBase, trimmedKey, startMs)
                AiProvider.GROQ -> testOpenAiCompatibleModels(
                    baseUrl = cleanBase,
                    apiKey = trimmedKey,
                    startMs = startMs,
                    providerName = provider.displayName,
                    extractRateHeaders = true
                )
                AiProvider.HUGGING_FACE -> testHuggingFaceConnection(cleanBase, trimmedKey, startMs)
                AiProvider.CUSTOM_OPENAI -> testCustomOpenAiConnection(cleanBase, trimmedKey, selectedModel, startMs)
            }
        } catch (e: ProviderApiException) {
            ConnectionTestResult(
                success = false,
                status = ConnectionStatus.ERROR,
                latencyMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L),
                errorCategory = e.category,
                errorMessage = e.message
            )
        } catch (e: SocketTimeoutException) {
            ConnectionTestResult(
                success = false,
                status = ConnectionStatus.ERROR,
                latencyMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L),
                errorCategory = ApiErrorCategory.CONNECTION_FAILED,
                errorMessage = "Connection timed out reaching ${provider.shortName}."
            )
        } catch (e: UnknownHostException) {
            ConnectionTestResult(
                success = false,
                status = ConnectionStatus.ERROR,
                latencyMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L),
                errorCategory = ApiErrorCategory.CONNECTION_FAILED,
                errorMessage = "Unable to resolve host for ${provider.shortName}. Check internet or URL."
            )
        } catch (e: IOException) {
            ConnectionTestResult(
                success = false,
                status = ConnectionStatus.ERROR,
                latencyMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L),
                errorCategory = ApiErrorCategory.CONNECTION_FAILED,
                errorMessage = e.localizedMessage ?: "Network connection failure."
            )
        } catch (e: Exception) {
            ConnectionTestResult(
                success = false,
                status = ConnectionStatus.ERROR,
                latencyMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L),
                errorCategory = ApiErrorCategory.UNKNOWN,
                errorMessage = e.localizedMessage ?: "Unexpected verification failure."
            )
        }
    }

    private fun testGeminiConnection(
        baseUrl: String,
        apiKey: String,
        selectedModel: String,
        startMs: Long
    ): ConnectionTestResult {
        val request = Request.Builder()
            .url("$baseUrl/models?pageSize=50")
            .header("x-goog-api-key", apiKey)
            .get()
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            val latency = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
            val bodyStr = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw mapHttpError(response.code, bodyStr, "Google Gemini")
            }

            val json = JSONObject(bodyStr)
            val arr = json.optJSONArray("models") ?: JSONArray()
            val discovered = mutableListOf<String>()
            var selectedSupportedForLive = false
            var selectedFound = false

            for (i in 0 until arr.length()) {
                val mObj = arr.optJSONObject(i) ?: continue
                val fullName = mObj.optString("name") // e.g. "models/gemini-2.5-flash"
                val shortName = fullName.removePrefix("models/")
                if (shortName.isNotBlank()) {
                    discovered.add(shortName)
                    if (shortName.equals(selectedModel, ignoreCase = true)) {
                        selectedFound = true
                        val methods = mObj.optJSONArray("supportedGenerationMethods")
                        if (methods != null) {
                            for (j in 0 until methods.length()) {
                                if (methods.optString(j) == "bidiGenerateContent") {
                                    selectedSupportedForLive = true
                                }
                            }
                        }
                    }
                }
            }

            val liveNote = when {
                selectedFound && selectedSupportedForLive -> "Live Audio supported on $selectedModel"
                isGeminiLiveAudioModel(selectedModel) && selectedFound -> "Model $selectedModel verified"
                isGeminiLiveAudioModel(selectedModel) -> "Key valid (${discovered.size} models). Live model ready"
                else -> "Key valid (${discovered.size} models available)"
            }

            return ConnectionTestResult(
                success = true,
                status = ConnectionStatus.CONNECTED,
                latencyMs = latency,
                discoveredModels = discovered,
                usageInfo = "$liveNote • ${latency}ms"
            )
        }
    }

    private fun testOpenRouterConnection(
        baseUrl: String,
        apiKey: String,
        startMs: Long
    ): ConnectionTestResult {
        // 1. Validate key and retrieve credit/usage info from /auth/key
        val keyRequest = Request.Builder()
            .url("$baseUrl/auth/key")
            .header("Authorization", "Bearer $apiKey")
            .get()
            .build()

        var usageSummary: String? = null
        okHttpClient.newCall(keyRequest).execute().use { keyResponse ->
            val keyBody = keyResponse.body?.string().orEmpty()
            if (!keyResponse.isSuccessful) {
                throw mapHttpError(keyResponse.code, keyBody, "OpenRouter")
            }
            val data = JSONObject(keyBody).optJSONObject("data")
            if (data != null) {
                val usage = data.optDouble("usage", Double.NaN)
                val limit = if (!data.isNull("limit")) data.optDouble("limit", Double.NaN) else Double.NaN
                val isFree = data.optBoolean("is_free_tier", false)
                usageSummary = buildString {
                    if (!usage.isNaN()) append("Used: \$${String.format("%.4f", usage)}")
                    if (!limit.isNaN()) append(" / Limit: \$${String.format("%.2f", limit)}")
                    else if (isFree) append(" • Free tier")
                }.ifBlank { "Key verified" }
            }
        }

        // 2. Fetch available models
        val modelsRes = testOpenAiCompatibleModels(
            baseUrl = baseUrl,
            apiKey = apiKey,
            startMs = startMs,
            providerName = "OpenRouter",
            extractRateHeaders = false
        )
        return modelsRes.copy(
            usageInfo = listOfNotNull(usageSummary, "${modelsRes.discoveredModels.size} models", "${modelsRes.latencyMs}ms")
                .joinToString(" • ")
        )
    }

    private fun testHuggingFaceConnection(
        baseUrl: String,
        apiKey: String,
        startMs: Long
    ): ConnectionTestResult {
        val whoamiRequest = Request.Builder()
            .url("https://huggingface.co/api/whoami-v2")
            .header("Authorization", "Bearer $apiKey")
            .get()
            .build()

        okHttpClient.newCall(whoamiRequest).execute().use { response ->
            val latency = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
            val bodyStr = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw mapHttpError(response.code, bodyStr, "Hugging Face")
            }
            val json = runCatching { JSONObject(bodyStr) }.getOrNull()
            val user = json?.optString("name")?.takeIf { it.isNotBlank() } ?: "Authenticated"
            val type = json?.optString("type")?.takeIf { it.isNotBlank() } ?: "token"

            // Optionally try router models endpoint, ignoring failure since whoami already succeeded
            val discovered = tryFetchOpenAiModelsSilently("$baseUrl/models", apiKey)
            return ConnectionTestResult(
                success = true,
                status = ConnectionStatus.CONNECTED,
                latencyMs = latency,
                discoveredModels = discovered,
                usageInfo = "Account: $user ($type) • ${latency}ms"
            )
        }
    }

    private fun testCustomOpenAiConnection(
        baseUrl: String,
        apiKey: String,
        selectedModel: String,
        startMs: Long
    ): ConnectionTestResult {
        return try {
            testOpenAiCompatibleModels(
                baseUrl = baseUrl,
                apiKey = apiKey,
                startMs = startMs,
                providerName = "Custom OpenAI API",
                extractRateHeaders = true
            )
        } catch (e: ProviderApiException) {
            // Some custom OpenAI-compatible servers don't expose GET /models (404/405);
            // fall back to minimal chat completion verification ping.
            if (e.httpCode == 404 || e.httpCode == 405) {
                val pingPayload = JSONObject().apply {
                    put("model", selectedModel)
                    put("max_tokens", 1)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", "hi")
                        })
                    })
                }.toString().toRequestBody(JSON_MEDIA_TYPE)

                val pingReq = Request.Builder()
                    .url("$baseUrl/chat/completions")
                    .header("Authorization", "Bearer $apiKey")
                    .post(pingPayload)
                    .build()

                okHttpClient.newCall(pingReq).execute().use { resp ->
                    val latency = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        throw mapHttpError(resp.code, body, "Custom OpenAI API")
                    }
                    ConnectionTestResult(
                        success = true,
                        status = ConnectionStatus.CONNECTED,
                        latencyMs = latency,
                        usageInfo = "Endpoint reachable (${selectedModel}) • ${latency}ms"
                    )
                }
            } else {
                throw e
            }
        }
    }

    private fun testOpenAiCompatibleModels(
        baseUrl: String,
        apiKey: String,
        startMs: Long,
        providerName: String,
        extractRateHeaders: Boolean
    ): ConnectionTestResult {
        val request = Request.Builder()
            .url("$baseUrl/models")
            .header("Authorization", "Bearer $apiKey")
            .get()
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            val latency = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
            val bodyStr = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw mapHttpError(response.code, bodyStr, providerName)
            }

            val models = parseOpenAiModelsList(bodyStr)
            val rateNote = if (extractRateHeaders) {
                val remReq = response.header("x-ratelimit-remaining-requests")
                val remTok = response.header("x-ratelimit-remaining-tokens")
                listOfNotNull(
                    remReq?.let { "Req left: $it" },
                    remTok?.let { "Tok left: $it" }
                ).joinToString(" • ").takeIf { it.isNotBlank() }
            } else null

            val usage = listOfNotNull(
                "${models.size} models",
                rateNote,
                "${latency}ms"
            ).joinToString(" • ")

            return ConnectionTestResult(
                success = true,
                status = ConnectionStatus.CONNECTED,
                latencyMs = latency,
                discoveredModels = models,
                usageInfo = usage
            )
        }
    }

    private fun tryFetchOpenAiModelsSilently(url: String, apiKey: String): List<String> {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $apiKey")
                .get()
                .build()
            okHttpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) parseOpenAiModelsList(resp.body?.string().orEmpty()) else emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseOpenAiModelsList(bodyStr: String): List<String> {
        return try {
            val json = JSONObject(bodyStr)
            val arr = json.optJSONArray("data") ?: return emptyList()
            buildList {
                for (i in 0 until arr.length()) {
                    val id = arr.optJSONObject(i)?.optString("id")
                    if (!id.isNullOrBlank()) add(id)
                }
            }.take(40)
        } catch (_: Exception) {
            emptyList()
        }
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
        val trimmedKey = apiKey.trim()
        if (trimmedKey.isEmpty()) {
            throw ProviderApiException(
                ApiErrorCategory.MISSING_KEY,
                "${provider.displayName} API key is missing. Add a key in API Keys & Providers."
            )
        }
        val cleanBase = baseUrl.trim().trimEnd('/').ifBlank { provider.defaultBaseUrl }

        try {
            when (provider) {
                AiProvider.GEMINI -> callGeminiGenerateContent(cleanBase, trimmedKey, model, systemPrompt, history, userMessage)
                AiProvider.OPENROUTER,
                AiProvider.GROQ,
                AiProvider.HUGGING_FACE,
                AiProvider.CUSTOM_OPENAI -> callOpenAiChatCompletions(
                    provider = provider,
                    baseUrl = cleanBase,
                    apiKey = trimmedKey,
                    model = model,
                    systemPrompt = systemPrompt,
                    history = history,
                    userMessage = userMessage
                )
            }
        } catch (e: ProviderApiException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw ProviderApiException(
                ApiErrorCategory.CONNECTION_FAILED,
                "${provider.shortName} request timed out. Check your connection and retry."
            )
        } catch (e: IOException) {
            throw ProviderApiException(
                ApiErrorCategory.CONNECTION_FAILED,
                e.localizedMessage ?: "Network error contacting ${provider.shortName}."
            )
        }
    }

    private fun callGeminiGenerateContent(
        baseUrl: String,
        apiKey: String,
        model: String,
        systemPrompt: String,
        history: List<ProviderChatMessage>,
        userMessage: String
    ): String {
        val contents = JSONArray()
        history.takeLast(12).forEach { msg ->
            contents.put(JSONObject().apply {
                put("role", if (msg.role == "assistant") "model" else "user")
                put("parts", JSONArray().apply {
                    put(JSONObject().apply { put("text", msg.text) })
                })
            })
        }
        contents.put(JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().apply {
                put(JSONObject().apply { put("text", userMessage) })
            })
        })

        val payload = JSONObject().apply {
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().apply { put("text", systemPrompt) })
                })
            })
            put("contents", contents)
        }.toString().toRequestBody(JSON_MEDIA_TYPE)

        val request = Request.Builder()
            .url("$baseUrl/models/$model:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(payload)
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw mapHttpError(response.code, bodyStr, "Google Gemini")
            }
            val json = JSONObject(bodyStr)
            val candidates = json.optJSONArray("candidates")
            val text = candidates?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?.optJSONObject(0)
                ?.optString("text")
                ?.trim()

            if (text.isNullOrBlank()) {
                throw ProviderApiException(ApiErrorCategory.UNKNOWN, "Gemini returned an empty response.")
            }
            return text
        }
    }

    private fun callOpenAiChatCompletions(
        provider: AiProvider,
        baseUrl: String,
        apiKey: String,
        model: String,
        systemPrompt: String,
        history: List<ProviderChatMessage>,
        userMessage: String
    ): String {
        val messages = JSONArray()
        messages.put(JSONObject().apply {
            put("role", "system")
            put("content", systemPrompt)
        })
        history.takeLast(12).forEach { msg ->
            messages.put(JSONObject().apply {
                put("role", msg.role)
                put("content", msg.text)
            })
        }
        messages.put(JSONObject().apply {
            put("role", "user")
            put("content", userMessage)
        })

        val payload = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("temperature", 0.85)
            put("max_tokens", 220)
        }.toString().toRequestBody(JSON_MEDIA_TYPE)

        val reqBuilder = Request.Builder()
            .url("$baseUrl/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(payload)

        if (provider == AiProvider.OPENROUTER) {
            reqBuilder.header("X-Title", "Mahi AI Voice Assistant")
        }

        okHttpClient.newCall(reqBuilder.build()).execute().use { response ->
            val bodyStr = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw mapHttpError(response.code, bodyStr, provider.displayName)
            }
            val json = JSONObject(bodyStr)
            val content = json.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                ?.trim()

            if (content.isNullOrBlank()) {
                throw ProviderApiException(ApiErrorCategory.UNKNOWN, "${provider.shortName} returned an empty response.")
            }
            return content
        }
    }

    suspend fun analyzeImageWithProvider(
        provider: AiProvider,
        apiKey: String,
        model: String,
        baseUrl: String,
        base64Jpeg: String,
        systemPrompt: String,
        userPrompt: String
    ): String = withContext(Dispatchers.IO) {
        val trimmedKey = apiKey.trim()
        if (trimmedKey.isEmpty()) {
            throw ProviderApiException(
                ApiErrorCategory.MISSING_KEY,
                "${provider.displayName} API key is missing. Add your key in API Keys & Providers to run Neural Lens AI vision."
            )
        }
        val cleanBase = baseUrl.trim().trimEnd('/').ifBlank { provider.defaultBaseUrl }

        try {
            when (provider) {
                AiProvider.GEMINI -> {
                    val restModel = if (isGeminiLiveAudioModel(model)) "gemini-2.5-flash" else model
                    callGeminiVision(cleanBase, trimmedKey, restModel, base64Jpeg, systemPrompt, userPrompt)
                }
                AiProvider.OPENROUTER,
                AiProvider.GROQ,
                AiProvider.HUGGING_FACE,
                AiProvider.CUSTOM_OPENAI -> callOpenAiVision(
                    provider = provider,
                    baseUrl = cleanBase,
                    apiKey = trimmedKey,
                    model = model,
                    base64Jpeg = base64Jpeg,
                    systemPrompt = systemPrompt,
                    userPrompt = userPrompt
                )
            }
        } catch (e: ProviderApiException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw ProviderApiException(
                ApiErrorCategory.CONNECTION_FAILED,
                "${provider.shortName} vision request timed out."
            )
        } catch (e: IOException) {
            throw ProviderApiException(
                ApiErrorCategory.CONNECTION_FAILED,
                e.localizedMessage ?: "Network error contacting ${provider.shortName} vision."
            )
        }
    }

    private fun callGeminiVision(
        baseUrl: String,
        apiKey: String,
        model: String,
        base64Jpeg: String,
        systemPrompt: String,
        userPrompt: String
    ): String {
        val payload = JSONObject().apply {
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().apply { put("text", systemPrompt) })
                })
            })
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", userPrompt) })
                        put(JSONObject().apply {
                            put("inlineData", JSONObject().apply {
                                put("mimeType", "image/jpeg")
                                put("data", base64Jpeg)
                            })
                        })
                    })
                })
            })
        }.toString().toRequestBody(JSON_MEDIA_TYPE)

        val request = Request.Builder()
            .url("$baseUrl/models/$model:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(payload)
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw mapHttpError(response.code, bodyStr, "Google Gemini")
            }
            val text = JSONObject(bodyStr).optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?.optJSONObject(0)
                ?.optString("text")
                ?.trim()

            if (text.isNullOrBlank()) {
                throw ProviderApiException(ApiErrorCategory.UNKNOWN, "Gemini Vision returned an empty explanation.")
            }
            return text
        }
    }

    private fun callOpenAiVision(
        provider: AiProvider,
        baseUrl: String,
        apiKey: String,
        model: String,
        base64Jpeg: String,
        systemPrompt: String,
        userPrompt: String
    ): String {
        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "text")
                        put("text", userPrompt)
                    })
                    put(JSONObject().apply {
                        put("type", "image_url")
                        put("image_url", JSONObject().apply {
                            put("url", "data:image/jpeg;base64,$base64Jpeg")
                        })
                    })
                })
            })
        }

        val payload = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("max_tokens", 320)
        }.toString().toRequestBody(JSON_MEDIA_TYPE)

        val reqBuilder = Request.Builder()
            .url("$baseUrl/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(payload)
        if (provider == AiProvider.OPENROUTER) {
            reqBuilder.header("X-Title", "Mahi AI Neural Lens")
        }

        okHttpClient.newCall(reqBuilder.build()).execute().use { response ->
            val bodyStr = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw mapHttpError(response.code, bodyStr, provider.displayName)
            }
            val text = JSONObject(bodyStr).optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                ?.trim()

            if (text.isNullOrBlank()) {
                throw ProviderApiException(ApiErrorCategory.UNKNOWN, "${provider.shortName} Vision returned an empty response.")
            }
            return text
        }
    }

    fun mapHttpError(code: Int, rawBody: String, providerName: String): ProviderApiException {
        val extractedMessage = extractApiErrorMessage(rawBody)
        val lower = extractedMessage.lowercase()

        return when {
            code == 401 || code == 403 || lower.contains("api key") || lower.contains("invalid_api_key") ||
                lower.contains("unauthorized") || lower.contains("invalid token") -> {
                ProviderApiException(
                    ApiErrorCategory.INVALID_KEY,
                    "Invalid $providerName API key or unauthorized access${detailSuffix(extractedMessage)}",
                    code
                )
            }
            code == 429 || lower.contains("quota") || lower.contains("rate limit") ||
                lower.contains("resource_exhausted") || lower.contains("insufficient_quota") -> {
                ProviderApiException(
                    ApiErrorCategory.QUOTA_EXCEEDED,
                    "$providerName quota or rate limit exceeded${detailSuffix(extractedMessage)}",
                    code
                )
            }
            code == 404 || lower.contains("model") && (lower.contains("not found") || lower.contains("unsupported") || lower.contains("does not exist")) -> {
                ProviderApiException(
                    ApiErrorCategory.UNSUPPORTED_MODEL,
                    "Unsupported or unavailable model on $providerName${detailSuffix(extractedMessage)}",
                    code
                )
            }
            else -> {
                ProviderApiException(
                    ApiErrorCategory.CONNECTION_FAILED,
                    "$providerName HTTP $code${detailSuffix(extractedMessage)}",
                    code
                )
            }
        }
    }

    private fun extractApiErrorMessage(rawBody: String): String {
        if (rawBody.isBlank()) return ""
        return try {
            val json = JSONObject(rawBody)
            when {
                json.has("error") -> {
                    val err = json.get("error")
                    if (err is JSONObject) err.optString("message") else err.toString()
                }
                json.has("message") -> json.optString("message")
                else -> rawBody.take(160)
            }
        } catch (_: Exception) {
            rawBody.take(160)
        }
    }

    private fun detailSuffix(msg: String): String =
        if (msg.isBlank()) "." else ": ${msg.take(140)}"
}
