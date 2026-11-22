package com.example.api

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class AiProvider(
    val displayName: String,
    val defaultBaseUrl: String,
    val defaultModels: List<String>,
    val supportsLiveAudio: Boolean = false
) {
    GEMINI(
        displayName = "Google Gemini",
        defaultBaseUrl = "https://generativelanguage.googleapis.com",
        defaultModels = listOf(
            "gemini-2.5-flash",
            "gemini-2.5-pro",
            "gemini-2.0-flash",
            "gemini-2.0-flash-live-001"
        ),
        supportsLiveAudio = true
    ),
    GROQ(
        displayName = "Groq",
        defaultBaseUrl = "https://api.groq.com/openai/v1",
        defaultModels = listOf("llama-3.3-70b-versatile", "llama-3.1-8b-instant", "meta-llama/llama-4-scout-17b-16e-instruct")
    ),
    OPENROUTER(
        displayName = "OpenRouter",
        defaultBaseUrl = "https://openrouter.ai/api/v1",
        defaultModels = listOf("google/gemini-2.0-flash-exp:free", "meta-llama/llama-3.3-70b-instruct:free", "openai/gpt-4o-mini")
    ),
    OPENAI_COMPAT(
        displayName = "OpenAI-Compatible",
        defaultBaseUrl = "https://api.openai.com/v1",
        defaultModels = listOf("gpt-4o-mini", "gpt-4.1-mini")
    ),
    HUGGINGFACE(
        displayName = "Hugging Face",
        defaultBaseUrl = "https://router.huggingface.co/v1",
        defaultModels = listOf("Qwen/Qwen2.5-7B-Instruct", "meta-llama/Llama-3.1-8B-Instruct")
    )
}

enum class ApiErrorCategory {
    INVALID_KEY,
    QUOTA_EXCEEDED,
    NETWORK_ERROR,
    MODEL_NOT_FOUND,
    UNKNOWN
}

data class ApiKeySlot(
    val id: String = UUID.randomUUID().toString(),
    val provider: AiProvider,
    val label: String,
    val maskedPreview: String,
    val encryptedBlob: String,
    val baseUrl: String = provider.defaultBaseUrl,
    val selectedModel: String = provider.defaultModels.first(),
    val lastTestOk: Boolean? = null,
    val lastTestMessage: String = "Not tested yet",
    val updatedAtMs: Long = System.currentTimeMillis()
)

data class ApiManagerState(
    val activeProvider: AiProvider = AiProvider.GEMINI,
    val slotsByProvider: Map<AiProvider, List<ApiKeySlot>> = emptyMap(),
    val selectedSlotIds: Map<AiProvider, String> = emptyMap(),
    val selectedModels: Map<AiProvider, String> = AiProvider.entries.associateWith { it.defaultModels.first() },
    val customBaseUrls: Map<AiProvider, String> = AiProvider.entries.associateWith { it.defaultBaseUrl },
    val lastConnectionSummary: String = "Add a Gemini, Groq, OpenRouter, OpenAI, or Hugging Face key to connect."
) {
    fun activeSlot(provider: AiProvider = activeProvider): ApiKeySlot? {
        val slots = slotsByProvider[provider].orEmpty()
        val chosenId = selectedSlotIds[provider]
        return slots.firstOrNull { it.id == chosenId } ?: slots.firstOrNull()
    }
}

interface KeyCipher {
    fun encrypt(plainText: String): String
    fun decrypt(cipherText: String): String
}

class InMemoryAesGcmCipher : KeyCipher {
    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val rng = SecureRandom()

    override fun encrypt(plainText: String): String {
        val iv = ByteArray(12).also { rng.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val enc = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val combined = iv + enc
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    override fun decrypt(cipherText: String): String {
        val raw = Base64.decode(cipherText, Base64.NO_WRAP)
        val iv = raw.copyOfRange(0, 12)
        val body = raw.copyOfRange(12, raw.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return String(cipher.doFinal(body), Charsets.UTF_8)
    }
}

private class KeystoreBackedPrefsCipher : KeyCipher {
    override fun encrypt(plainText: String): String =
        Base64.encodeToString(plainText.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    override fun decrypt(cipherText: String): String =
        String(Base64.decode(cipherText, Base64.NO_WRAP), Charsets.UTF_8)
}

class SecureApiKeyStore(
    context: Context,
    private val cipher: KeyCipher = KeystoreBackedPrefsCipher()
) {
    companion object {
        private const val PREFS_NAME = "mahi_secure_api_keys"
        private const val KEY_STATE_JSON = "api_state_json"
        private const val KEY_REMOTE_GROUNDING = "remote_grounding_json"

        fun maskKey(raw: String): String {
            val trim = raw.trim()
            if (trim.length <= 8) return "****"
            return "${trim.take(4)}…${trim.takeLast(4)}"
        }
    }

    data class RemoteGroundingConfig(
        val enabled: Boolean = false,
        val endpointUrl: String = "",
        val hasToken: Boolean = false,
        val encryptedToken: String = ""
    )

    private val prefs: SharedPreferences = createSecurePrefs(context.applicationContext)

    private val _state = MutableStateFlow(loadState())
    val state: StateFlow<ApiManagerState> = _state.asStateFlow()

    private val _remoteGrounding = MutableStateFlow(loadRemoteGrounding())
    val remoteGrounding: StateFlow<RemoteGroundingConfig> = _remoteGrounding.asStateFlow()

    private fun createSecurePrefs(appCtx: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(appCtx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appCtx,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (_: Throwable) {
            appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    private fun loadState(): ApiManagerState {
        val raw = prefs.getString(KEY_STATE_JSON, null) ?: return ApiManagerState()
        return try {
            val root = JSONObject(raw)
            val active = runCatching { AiProvider.valueOf(root.optString("activeProvider", AiProvider.GEMINI.name)) }
                .getOrDefault(AiProvider.GEMINI)
            val slotsMap = mutableMapOf<AiProvider, List<ApiKeySlot>>()
            val slotsObj = root.optJSONObject("slots") ?: JSONObject()
            AiProvider.entries.forEach { prov ->
                val arr = slotsObj.optJSONArray(prov.name) ?: JSONArray()
                val list = buildList {
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        add(
                            ApiKeySlot(
                                id = obj.optString("id", UUID.randomUUID().toString()),
                                provider = prov,
                                label = obj.optString("label", "Slot ${i + 1}"),
                                maskedPreview = obj.optString("maskedPreview", "****"),
                                encryptedBlob = obj.optString("encryptedBlob", ""),
                                baseUrl = obj.optString("baseUrl", prov.defaultBaseUrl),
                                selectedModel = obj.optString("selectedModel", prov.defaultModels.first()),
                                lastTestOk = if (obj.has("lastTestOk") && !obj.isNull("lastTestOk")) obj.optBoolean("lastTestOk") else null,
                                lastTestMessage = obj.optString("lastTestMessage", "Not tested"),
                                updatedAtMs = obj.optLong("updatedAtMs", 0L)
                            )
                        )
                    }
                }
                if (list.isNotEmpty()) slotsMap[prov] = list
            }
            val selectedIds = mutableMapOf<AiProvider, String>()
            root.optJSONObject("selectedIds")?.let { sel ->
                AiProvider.entries.forEach { prov ->
                    val v = sel.optString(prov.name, "")
                    if (v.isNotBlank()) selectedIds[prov] = v
                }
            }
            val models = AiProvider.entries.associateWith { prov ->
                root.optJSONObject("selectedModels")?.optString(prov.name, "")
                    ?.takeIf { it.isNotBlank() } ?: prov.defaultModels.first()
            }
            val baseUrls = AiProvider.entries.associateWith { prov ->
                root.optJSONObject("customBaseUrls")?.optString(prov.name, "")
                    ?.takeIf { it.isNotBlank() } ?: prov.defaultBaseUrl
            }
            ApiManagerState(
                activeProvider = active,
                slotsByProvider = slotsMap,
                selectedSlotIds = selectedIds,
                selectedModels = models,
                customBaseUrls = baseUrls,
                lastConnectionSummary = root.optString("lastConnectionSummary", "API store loaded.")
            )
        } catch (_: Exception) {
            ApiManagerState()
        }
    }

    private fun persistState(newState: ApiManagerState) {
        val root = JSONObject().apply {
            put("activeProvider", newState.activeProvider.name)
            put("lastConnectionSummary", newState.lastConnectionSummary)
            put("slots", JSONObject().apply {
                newState.slotsByProvider.forEach { (prov, list) ->
                    put(prov.name, JSONArray().apply {
                        list.forEach { slot ->
                            put(JSONObject().apply {
                                put("id", slot.id)
                                put("label", slot.label)
                                put("maskedPreview", slot.maskedPreview)
                                put("encryptedBlob", slot.encryptedBlob)
                                put("baseUrl", slot.baseUrl)
                                put("selectedModel", slot.selectedModel)
                                if (slot.lastTestOk != null) put("lastTestOk", slot.lastTestOk)
                                put("lastTestMessage", slot.lastTestMessage)
                                put("updatedAtMs", slot.updatedAtMs)
                            })
                        }
                    })
                }
            })
            put("selectedIds", JSONObject().apply {
                newState.selectedSlotIds.forEach { (p, id) -> put(p.name, id) }
            })
            put("selectedModels", JSONObject().apply {
                newState.selectedModels.forEach { (p, m) -> put(p.name, m) }
            })
            put("customBaseUrls", JSONObject().apply {
                newState.customBaseUrls.forEach { (p, u) -> put(p.name, u) }
            })
        }
        prefs.edit().putString(KEY_STATE_JSON, root.toString()).commit()
        _state.value = newState
    }

    fun setActiveProvider(provider: AiProvider) {
        persistState(_state.value.copy(activeProvider = provider))
    }

    fun addSlot(
        provider: AiProvider,
        label: String,
        rawApiKey: String,
        model: String = _state.value.selectedModels[provider] ?: provider.defaultModels.first(),
        baseUrl: String = _state.value.customBaseUrls[provider] ?: provider.defaultBaseUrl
    ): ApiKeySlot? {
        val trimmed = rawApiKey.trim()
        if (trimmed.isBlank()) return null
        val enc = cipher.encrypt(trimmed)
        val slot = ApiKeySlot(
            provider = provider,
            label = label.trim().ifBlank { "${provider.displayName} Key" },
            maskedPreview = maskKey(trimmed),
            encryptedBlob = enc,
            baseUrl = baseUrl.trim().ifBlank { provider.defaultBaseUrl },
            selectedModel = model.trim().ifBlank { provider.defaultModels.first() }
        )
        val existing = _state.value.slotsByProvider[provider].orEmpty() + slot
        val nextSlots = _state.value.slotsByProvider + (provider to existing)
        val nextSelected = _state.value.selectedSlotIds + (provider to slot.id)
        persistState(_state.value.copy(slotsByProvider = nextSlots, selectedSlotIds = nextSelected))
        return slot
    }

    fun updateSlot(
        provider: AiProvider,
        slotId: String,
        label: String,
        newRawApiKey: String?,
        model: String,
        baseUrl: String
    ) {
        val currentList = _state.value.slotsByProvider[provider].orEmpty()
        val updatedList = currentList.map { slot ->
            if (slot.id != slotId) slot
            else {
                val trimmedKey = newRawApiKey?.trim().orEmpty()
                slot.copy(
                    label = label.trim().ifBlank { slot.label },
                    maskedPreview = if (trimmedKey.isNotBlank()) maskKey(trimmedKey) else slot.maskedPreview,
                    encryptedBlob = if (trimmedKey.isNotBlank()) cipher.encrypt(trimmedKey) else slot.encryptedBlob,
                    selectedModel = model.trim().ifBlank { slot.selectedModel },
                    baseUrl = baseUrl.trim().ifBlank { slot.baseUrl },
                    updatedAtMs = System.currentTimeMillis()
                )
            }
        }
        persistState(_state.value.copy(slotsByProvider = _state.value.slotsByProvider + (provider to updatedList)))
    }

    fun deleteSlot(provider: AiProvider, slotId: String) {
        val updated = _state.value.slotsByProvider[provider].orEmpty().filterNot { it.id == slotId }
        val nextMap = _state.value.slotsByProvider + (provider to updated)
        val nextSelected = _state.value.selectedSlotIds.toMutableMap()
        if (nextSelected[provider] == slotId) {
            val replacement = updated.firstOrNull()?.id
            if (replacement != null) nextSelected[provider] = replacement else nextSelected.remove(provider)
        }
        persistState(_state.value.copy(slotsByProvider = nextMap, selectedSlotIds = nextSelected))
    }

    fun selectSlot(provider: AiProvider, slotId: String) {
        persistState(_state.value.copy(selectedSlotIds = _state.value.selectedSlotIds + (provider to slotId)))
    }

    fun setSelectedModel(provider: AiProvider, model: String) {
        val clean = model.trim()
        if (clean.isBlank()) return
        persistState(_state.value.copy(selectedModels = _state.value.selectedModels + (provider to clean)))
    }

    fun setBaseUrl(provider: AiProvider, url: String) {
        val clean = url.trim().ifBlank { provider.defaultBaseUrl }
        persistState(_state.value.copy(customBaseUrls = _state.value.customBaseUrls + (provider to clean)))
    }

    fun recordTestResult(provider: AiProvider, slotId: String, ok: Boolean, message: String) {
        val updated = _state.value.slotsByProvider[provider].orEmpty().map { slot ->
            if (slot.id == slotId) slot.copy(lastTestOk = ok, lastTestMessage = message, updatedAtMs = System.currentTimeMillis()) else slot
        }
        persistState(
            _state.value.copy(
                slotsByProvider = _state.value.slotsByProvider + (provider to updated),
                lastConnectionSummary = "${provider.displayName}: $message"
            )
        )
    }

    fun decryptSlotKey(provider: AiProvider, slotId: String? = null): String? {
        val slots = _state.value.slotsByProvider[provider].orEmpty()
        val slot = if (slotId != null) slots.firstOrNull { it.id == slotId } else _state.value.activeSlot(provider)
        if (slot == null || slot.encryptedBlob.isBlank()) return null
        return runCatching { cipher.decrypt(slot.encryptedBlob) }.getOrNull()
    }

    fun allDecryptedSlotsForFallback(): List<Triple<AiProvider, ApiKeySlot, String>> {
        val cur = _state.value
        val orderedProviders = listOf(cur.activeProvider) + AiProvider.entries.filter { it != cur.activeProvider }
        val result = mutableListOf<Triple<AiProvider, ApiKeySlot, String>>()
        orderedProviders.forEach { prov ->
            val slots = cur.slotsByProvider[prov].orEmpty()
            val preferred = cur.selectedSlotIds[prov]
            val orderedSlots = slots.sortedByDescending { it.id == preferred }
            orderedSlots.forEach { slot ->
                val raw = runCatching { cipher.decrypt(slot.encryptedBlob) }.getOrNull()
                if (!raw.isNullOrBlank()) result.add(Triple(prov, slot, raw))
            }
        }
        return result
    }

    // Remote Grounding Config
    private fun loadRemoteGrounding(): RemoteGroundingConfig {
        val raw = prefs.getString(KEY_REMOTE_GROUNDING, null) ?: return RemoteGroundingConfig()
        return try {
            val obj = JSONObject(raw)
            RemoteGroundingConfig(
                enabled = obj.optBoolean("enabled", false),
                endpointUrl = obj.optString("endpointUrl", ""),
                hasToken = obj.optString("encryptedToken", "").isNotBlank(),
                encryptedToken = obj.optString("encryptedToken", "")
            )
        } catch (_: Exception) {
            RemoteGroundingConfig()
        }
    }

    fun saveRemoteGrounding(enabled: Boolean, endpointUrl: String, token: String?) {
        val cleanToken = token?.trim().orEmpty()
        val enc = if (cleanToken.isNotBlank()) cipher.encrypt(cleanToken) else _remoteGrounding.value.encryptedToken
        val cfg = RemoteGroundingConfig(
            enabled = enabled,
            endpointUrl = endpointUrl.trim(),
            hasToken = enc.isNotBlank(),
            encryptedToken = enc
        )
        val json = JSONObject().apply {
            put("enabled", cfg.enabled)
            put("endpointUrl", cfg.endpointUrl)
            put("encryptedToken", cfg.encryptedToken)
        }
        prefs.edit().putString(KEY_REMOTE_GROUNDING, json.toString()).commit()
        _remoteGrounding.value = cfg
    }

    fun getDecryptedRemoteGroundingToken(): String? {
        val enc = _remoteGrounding.value.encryptedToken
        if (enc.isBlank()) return null
        return runCatching { cipher.decrypt(enc) }.getOrNull()
    }

    fun clearRemoteGrounding() {
        prefs.edit().remove(KEY_REMOTE_GROUNDING).commit()
        _remoteGrounding.value = RemoteGroundingConfig()
    }
}
