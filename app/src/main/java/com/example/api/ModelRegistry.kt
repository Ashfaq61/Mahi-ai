package com.example.api

import com.example.data.CachedModelEntity
import com.example.data.MahiDataRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

enum class ModelCapability(val key: String) {
    CHAT("chat"),
    LIVE("live"),
    VISION("vision"),
    TOOLS("tools"),
    EMBEDDINGS("embeddings")
}

data class RegisteredModelInfo(
    val provider: AiProvider,
    val modelId: String,
    val displayName: String,
    val capabilities: Set<ModelCapability>,
    val pricingSummary: String = "Standard",
    val contextWindow: Int = 32_768,
    val lastProbedOk: Boolean = false,
    val probeStatusNote: String = "Discovered",
    val latencyMs: Long = 0L,
    val verifiedAtMs: Long = 0L,
    val isManualOverride: Boolean = false
)

class ModelRegistry(
    private val providerClient: ProviderClient,
    private val keyStore: SecureApiKeyStore,
    private val repository: MahiDataRepository? = null
) {
    companion object {
        const val REFRESH_INTERVAL_MS = 24L * 60L * 60L * 1000L

        fun classifyCapabilities(
            provider: AiProvider,
            modelId: String,
            supportedMethods: List<String> = emptyList()
        ): Set<ModelCapability> {
            val lower = modelId.lowercase()
            val caps = mutableSetOf<ModelCapability>()
            if (lower.contains("embed") || supportedMethods.any { it.contains("embedContent", true) }) {
                caps.add(ModelCapability.EMBEDDINGS)
            }
            if (supportedMethods.any { it.contains("bidiGenerateContent", true) } ||
                (provider == AiProvider.GEMINI && lower.contains("live")) ||
                (provider == AiProvider.GEMINI && (lower.contains("2.5-flash") || lower.contains("2.0-flash-exp")))
            ) {
                caps.add(ModelCapability.LIVE)
            }
            if (supportedMethods.any { it.contains("generateContent", true) } ||
                (!caps.contains(ModelCapability.EMBEDDINGS) && !lower.contains("whisper") && !lower.contains("tts"))
            ) {
                caps.add(ModelCapability.CHAT)
                caps.add(ModelCapability.TOOLS)
            }
            if (lower.contains("gemini") || lower.contains("vision") || lower.contains("4o") ||
                lower.contains("vl") || lower.contains("scout") || lower.contains("maverick")
            ) {
                caps.add(ModelCapability.VISION)
            }
            return caps
        }
    }

    private val _models = MutableStateFlow<List<RegisteredModelInfo>>(emptyList())
    val models: StateFlow<List<RegisteredModelInfo>> = _models.asStateFlow()

    private val _status = MutableStateFlow("ModelRegistry idle")
    val status: StateFlow<String> = _status.asStateFlow()

    private var lastFullRefreshMs: Long = 0L

    suspend fun refreshIfStale(force: Boolean = false): List<RegisteredModelInfo> {
        val now = System.currentTimeMillis()
        if (!force && lastFullRefreshMs > 0L && (now - lastFullRefreshMs) < REFRESH_INTERVAL_MS && _models.value.isNotEmpty()) {
            return _models.value
        }
        val allDiscovered = mutableListOf<RegisteredModelInfo>()
        for (prov in AiProvider.entries) {
            val key = keyStore.decryptSlotKey(prov) ?: continue
            val list = discoverProviderModels(prov, key)
            allDiscovered.addAll(list)
        }
        if (allDiscovered.isNotEmpty()) {
            _models.value = allDiscovered
            lastFullRefreshMs = now
            repository?.saveModels(
                allDiscovered.map {
                    CachedModelEntity(
                        compositeKey = "${it.provider.name}:${it.modelId}",
                        provider = it.provider.name,
                        modelId = it.modelId,
                        displayName = it.displayName,
                        capabilitiesCsv = it.capabilities.joinToString(",") { c -> c.key },
                        pricingSummary = it.pricingSummary,
                        contextWindow = it.contextWindow,
                        lastProbedOk = it.lastProbedOk,
                        probeStatusNote = it.probeStatusNote,
                        latencyMs = it.latencyMs,
                        verifiedAtMs = it.verifiedAtMs,
                        isManualOverride = it.isManualOverride
                    )
                }
            )
        }
        return allDiscovered
    }

    suspend fun discoverProviderModels(provider: AiProvider, rawKey: String): List<RegisteredModelInfo> {
        val baseUrl = (keyStore.state.value.customBaseUrls[provider] ?: provider.defaultBaseUrl).trimEnd('/')
        return try {
            val discovered = when (provider) {
                AiProvider.GEMINI -> fetchGeminiModels(baseUrl, rawKey)
                AiProvider.GROQ, AiProvider.OPENROUTER, AiProvider.OPENAI_COMPAT -> fetchOpenAiModels(provider, baseUrl, rawKey)
                AiProvider.HUGGINGFACE -> fetchHuggingFaceModels()
            }
            val probed = probeTopCandidates(provider, rawKey, baseUrl, discovered)
            _status.value = "${provider.displayName}: ${probed.size} models discovered"
            probed
        } catch (e: Exception) {
            _status.value = "${provider.displayName} discovery fallback (${e.localizedMessage})"
            provider.defaultModels.map { id ->
                RegisteredModelInfo(
                    provider = provider,
                    modelId = id,
                    displayName = id,
                    capabilities = classifyCapabilities(provider, id),
                    probeStatusNote = "Fallback default (${e.localizedMessage?.take(40)})"
                )
            }
        }
    }

    private suspend fun fetchGeminiModels(baseUrl: String, apiKey: String): List<RegisteredModelInfo> {
        val (code, body) = providerClient.fetchRawJsonGet(
            "$baseUrl/v1beta/models?pageSize=50",
            mapOf("x-goog-api-key" to apiKey)
        )
        if (code !in 200..299) throw IllegalStateException("HTTP $code")
        val arr = JSONObject(body).optJSONArray("models") ?: JSONArray()
        return buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("name").removePrefix("models/").trim()
                if (id.isBlank()) continue
                val methodsArr = obj.optJSONArray("supportedGenerationMethods")
                val methods = buildList {
                    if (methodsArr != null) {
                        for (j in 0 until methodsArr.length()) add(methodsArr.optString(j))
                    }
                }
                add(
                    RegisteredModelInfo(
                        provider = AiProvider.GEMINI,
                        modelId = id,
                        displayName = obj.optString("displayName", id),
                        capabilities = classifyCapabilities(AiProvider.GEMINI, id, methods),
                        pricingSummary = "Google AI tier",
                        contextWindow = obj.optInt("inputTokenLimit", 32_768)
                    )
                )
            }
        }.take(25)
    }

    private suspend fun fetchOpenAiModels(provider: AiProvider, baseUrl: String, apiKey: String): List<RegisteredModelInfo> {
        val url = if (provider == AiProvider.OPENROUTER) "https://openrouter.ai/api/v1/models" else "$baseUrl/models"
        val (code, body) = providerClient.fetchRawJsonGet(url, mapOf("Authorization" to "Bearer $apiKey"))
        if (code !in 200..299) throw IllegalStateException("HTTP $code")
        val arr = JSONObject(body).optJSONArray("data") ?: JSONArray()
        return buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id").trim()
                if (id.isBlank()) continue
                val pricingObj = obj.optJSONObject("pricing")
                val pricing = if (pricingObj != null) {
                    val promptPrice = pricingObj.optString("prompt", "")
                    if (promptPrice == "0") "Free" else if (promptPrice.isNotBlank()) "$$promptPrice/tok" else "Metered"
                } else "Standard"
                add(
                    RegisteredModelInfo(
                        provider = provider,
                        modelId = id,
                        displayName = obj.optString("name", id),
                        capabilities = classifyCapabilities(provider, id),
                        pricingSummary = pricing,
                        contextWindow = obj.optInt("context_length", 32_768)
                    )
                )
            }
        }.take(25)
    }

    private suspend fun fetchHuggingFaceModels(): List<RegisteredModelInfo> {
        val (code, body) = providerClient.fetchRawJsonGet(
            "https://huggingface.co/api/models?pipeline_tag=text-generation&sort=downloads&direction=-1&limit=15"
        )
        if (code !in 200..299) throw IllegalStateException("HTTP $code")
        val arr = JSONArray(body)
        return buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id").trim()
                if (id.isBlank()) continue
                add(
                    RegisteredModelInfo(
                        provider = AiProvider.HUGGINGFACE,
                        modelId = id,
                        displayName = id,
                        capabilities = classifyCapabilities(AiProvider.HUGGINGFACE, id),
                        pricingSummary = "HF Serverless / GGUF"
                    )
                )
            }
        }
    }

    private suspend fun probeTopCandidates(
        provider: AiProvider,
        rawKey: String,
        baseUrl: String,
        items: List<RegisteredModelInfo>
    ): List<RegisteredModelInfo> {
        val chatCandidate = items.firstOrNull { ModelCapability.CHAT in it.capabilities } ?: return items
        val probeStart = System.currentTimeMillis()
        val res = providerClient.testConnection(provider, rawKey, chatCandidate.modelId, baseUrl)
        val latency = System.currentTimeMillis() - probeStart
        return items.map { item ->
            if (item.modelId == chatCandidate.modelId) {
                item.copy(
                    lastProbedOk = res.isSuccess,
                    probeStatusNote = if (res.isSuccess) "Verified (${latency}ms)" else "Probe failed: ${res.exceptionOrNull()?.localizedMessage?.take(50)}",
                    latencyMs = latency,
                    verifiedAtMs = System.currentTimeMillis()
                )
            } else {
                item
            }
        }
    }

    fun bestModelFor(provider: AiProvider, capability: ModelCapability): String {
        val override = keyStore.state.value.selectedModels[provider]
        val list = _models.value.filter { it.provider == provider && capability in it.capabilities }
        val verified = list.firstOrNull { it.lastProbedOk }
        if (capability == ModelCapability.LIVE) {
            return list.firstOrNull { it.modelId.contains("live", true) || it.modelId.contains("2.0-flash-exp", true) }?.modelId
                ?: "gemini-2.0-flash-live-001"
        }
        return verified?.modelId ?: list.firstOrNull()?.modelId ?: override ?: provider.defaultModels.first()
    }
}
