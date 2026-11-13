package com.example.api

enum class AiProvider(
    val id: String,
    val displayName: String,
    val shortName: String,
    val description: String,
    val defaultBaseUrl: String,
    val defaultModels: List<String>,
    val supportsLiveAudio: Boolean,
    val keyHint: String
) {
    GEMINI(
        id = "gemini",
        displayName = "Google Gemini",
        shortName = "Gemini",
        description = "Native Live Audio-to-Audio & multimodal intelligence",
        defaultBaseUrl = "https://generativelanguage.googleapis.com/v1beta",
        defaultModels = listOf(
            "gemini-2.5-flash-native-audio-preview-09-2025",
            "gemini-2.0-flash-live-001",
            "gemini-3.1-flash-live-preview",
            "gemini-2.5-flash",
            "gemini-2.5-pro"
        ),
        supportsLiveAudio = true,
        keyHint = "AIzaSy..."
    ),
    OPENROUTER(
        id = "openrouter",
        displayName = "OpenRouter",
        shortName = "OpenRouter",
        description = "Unified gateway to Claude, GPT, Llama & Gemini models",
        defaultBaseUrl = "https://openrouter.ai/api/v1",
        defaultModels = listOf(
            "google/gemini-2.5-flash",
            "anthropic/claude-3.5-sonnet",
            "openai/gpt-4o-mini",
            "meta-llama/llama-3.3-70b-instruct"
        ),
        supportsLiveAudio = false,
        keyHint = "sk-or-v1-..."
    ),
    GROQ(
        id = "groq",
        displayName = "Groq",
        shortName = "Groq",
        description = "Ultra-low latency LPU inference for conversational speed",
        defaultBaseUrl = "https://api.groq.com/openai/v1",
        defaultModels = listOf(
            "llama-3.3-70b-versatile",
            "llama-3.1-8b-instant",
            "mixtral-8x7b-32768",
            "gemma2-9b-it"
        ),
        supportsLiveAudio = false,
        keyHint = "gsk_..."
    ),
    HUGGING_FACE(
        id = "huggingface",
        displayName = "Hugging Face",
        shortName = "HuggingFace",
        description = "Open-source inference router across community models",
        defaultBaseUrl = "https://router.huggingface.co/v1",
        defaultModels = listOf(
            "meta-llama/Llama-3.3-70B-Instruct",
            "Qwen/Qwen2.5-72B-Instruct",
            "mistralai/Mistral-7B-Instruct-v0.3",
            "HuggingFaceH4/zephyr-7b-beta"
        ),
        supportsLiveAudio = false,
        keyHint = "hf_..."
    ),
    CUSTOM_OPENAI(
        id = "custom_openai",
        displayName = "Custom OpenAI API",
        shortName = "Custom API",
        description = "Any OpenAI-compatible endpoint (OpenAI, Ollama, vLLM, LocalAI)",
        defaultBaseUrl = "https://api.openai.com/v1",
        defaultModels = listOf(
            "gpt-4o-mini",
            "gpt-4o",
            "deepseek-chat",
            "llama3.2"
        ),
        supportsLiveAudio = false,
        keyHint = "sk-..."
    );

    companion object {
        fun fromId(id: String?): AiProvider {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: GEMINI
        }
    }
}

enum class ConnectionStatus(val label: String) {
    DISCONNECTED("Disconnected"),
    TESTING("Testing..."),
    CONNECTED("Connected"),
    ERROR("Error")
}

data class ApiKeyEntry(
    val id: String,
    val provider: AiProvider,
    val label: String,
    val maskedKey: String,
    val status: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val lastLatencyMs: Long? = null,
    val usageInfo: String? = null,
    val lastError: String? = null,
    val lastCheckedAt: Long? = null
)

data class ProviderConfig(
    val provider: AiProvider,
    val selectedKeyId: String? = null,
    val selectedModel: String = provider.defaultModels.first(),
    val customBaseUrl: String = provider.defaultBaseUrl,
    val availableModels: List<String> = provider.defaultModels
)

data class ApiManagerState(
    val activeProvider: AiProvider = AiProvider.GEMINI,
    val providers: Map<AiProvider, ProviderConfig> = AiProvider.entries.associateWith { ProviderConfig(it) },
    val keysByProvider: Map<AiProvider, List<ApiKeyEntry>> = AiProvider.entries.associateWith { emptyList() }
) {
    fun getConfig(provider: AiProvider): ProviderConfig =
        providers[provider] ?: ProviderConfig(provider)

    fun getKeys(provider: AiProvider): List<ApiKeyEntry> =
        keysByProvider[provider].orEmpty()

    fun getSelectedKey(provider: AiProvider): ApiKeyEntry? {
        val keys = getKeys(provider)
        val selectedId = getConfig(provider).selectedKeyId
        return keys.firstOrNull { it.id == selectedId } ?: keys.firstOrNull()
    }

    val activeConfig: ProviderConfig
        get() = getConfig(activeProvider)

    val activeKey: ApiKeyEntry?
        get() = getSelectedKey(activeProvider)

    val activeConnectionStatus: ConnectionStatus
        get() = activeKey?.status ?: ConnectionStatus.DISCONNECTED
}

fun maskApiKey(rawKey: String): String {
    val trimmed = rawKey.trim()
    if (trimmed.isEmpty()) return ""
    if (trimmed.length <= 8) {
        return "••••" + trimmed.takeLast(2.coerceAtMost(trimmed.length))
    }
    return "${trimmed.take(4)}••••••${trimmed.takeLast(4)}"
}

fun isGeminiLiveAudioModel(modelName: String): Boolean {
    val lower = modelName.lowercase()
    return lower.contains("live") || lower.contains("native-audio")
}
