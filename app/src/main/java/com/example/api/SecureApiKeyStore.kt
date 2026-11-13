package com.example.api

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Persists user-configured AI provider settings and encrypted API keys.
 * Raw API keys are always encrypted via SecureKeyCipher (AndroidKeystoreCipher in production)
 * before being saved and are never logged or stored in plaintext.
 */
class SecureApiKeyStore(
    context: Context,
    private val cipher: SecureKeyCipher = AndroidKeystoreCipher()
) {
    companion object {
        private const val TAG = "SecureApiKeyStore"
        private const val PREFS_NAME = "mahi_secure_api_store"
        private const val KEY_ACTIVE_PROVIDER = "active_provider"
        private const val KEY_CONFIGS_JSON = "provider_configs_json"
        private const val KEY_ENTRIES_JSON = "encrypted_key_entries_json"
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val encryptedSecretsById = mutableMapOf<String, String>()

    private val _state = MutableStateFlow(loadInitialState())
    val state: StateFlow<ApiManagerState> = _state.asStateFlow()

    @Synchronized
    fun reloadFromDisk(): ApiManagerState {
        val loaded = loadInitialState()
        _state.value = loaded
        return loaded
    }

    private fun loadInitialState(): ApiManagerState {
        encryptedSecretsById.clear()
        val activeProvider = AiProvider.fromId(prefs.getString(KEY_ACTIVE_PROVIDER, AiProvider.GEMINI.id))

        val configs = AiProvider.entries.associateWith { ProviderConfig(it) }.toMutableMap()
        val configsJsonRaw = prefs.getString(KEY_CONFIGS_JSON, null)
        if (!configsJsonRaw.isNullOrBlank()) {
            try {
                val obj = JSONObject(configsJsonRaw)
                AiProvider.entries.forEach { provider ->
                    val pObj = obj.optJSONObject(provider.id) ?: return@forEach
                    val selectedKeyId = pObj.optString("selectedKeyId").takeIf { it.isNotBlank() }
                    val selectedModel = pObj.optString("selectedModel")
                        .takeIf { it.isNotBlank() } ?: provider.defaultModels.first()
                    val customBaseUrl = pObj.optString("customBaseUrl")
                        .takeIf { it.isNotBlank() } ?: provider.defaultBaseUrl

                    val modelsList = mutableListOf<String>()
                    val modelsArr = pObj.optJSONArray("availableModels")
                    if (modelsArr != null) {
                        for (i in 0 until modelsArr.length()) {
                            val m = modelsArr.optString(i)
                            if (m.isNotBlank()) modelsList.add(m)
                        }
                    }
                    if (modelsList.isEmpty()) modelsList.addAll(provider.defaultModels)
                    if (!modelsList.contains(selectedModel)) modelsList.add(0, selectedModel)

                    configs[provider] = ProviderConfig(
                        provider = provider,
                        selectedKeyId = selectedKeyId,
                        selectedModel = selectedModel,
                        customBaseUrl = customBaseUrl,
                        availableModels = modelsList.distinct()
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse provider configs; falling back to defaults")
            }
        }

        val entriesByProvider = AiProvider.entries.associateWith { mutableListOf<ApiKeyEntry>() }.toMutableMap()
        val entriesJsonRaw = prefs.getString(KEY_ENTRIES_JSON, null)
        if (!entriesJsonRaw.isNullOrBlank()) {
            try {
                val arr = JSONArray(entriesJsonRaw)
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
                    val provider = AiProvider.fromId(item.optString("provider"))
                    val encryptedPayload = item.optString("cipherText").takeIf { it.isNotBlank() } ?: continue
                    val label = item.optString("label").ifBlank { "API Key" }
                    val maskedKey = item.optString("maskedKey").ifBlank { "••••••••" }
                    val status = runCatching {
                        ConnectionStatus.valueOf(item.optString("status", ConnectionStatus.DISCONNECTED.name))
                    }.getOrDefault(ConnectionStatus.DISCONNECTED)
                    val latency = if (item.has("lastLatencyMs") && !item.isNull("lastLatencyMs")) {
                        item.optLong("lastLatencyMs")
                    } else null
                    val usageInfo = item.optString("usageInfo").takeIf { it.isNotBlank() }
                    val lastError = item.optString("lastError").takeIf { it.isNotBlank() }
                    val lastCheckedAt = if (item.has("lastCheckedAt") && !item.isNull("lastCheckedAt")) {
                        item.optLong("lastCheckedAt")
                    } else null

                    encryptedSecretsById[id] = encryptedPayload
                    entriesByProvider.getValue(provider).add(
                        ApiKeyEntry(
                            id = id,
                            provider = provider,
                            label = label,
                            maskedKey = maskedKey,
                            status = status,
                            lastLatencyMs = latency,
                            usageInfo = usageInfo,
                            lastError = lastError,
                            lastCheckedAt = lastCheckedAt
                        )
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse stored API key entries")
            }
        }

        // Ensure selectedKeyId points to an existing key if one exists
        AiProvider.entries.forEach { provider ->
            val keys = entriesByProvider.getValue(provider)
            val cfg = configs.getValue(provider)
            if (cfg.selectedKeyId == null || keys.none { it.id == cfg.selectedKeyId }) {
                configs[provider] = cfg.copy(selectedKeyId = keys.firstOrNull()?.id)
            }
        }

        return ApiManagerState(
            activeProvider = activeProvider,
            providers = configs,
            keysByProvider = entriesByProvider.mapValues { it.value.toList() }
        )
    }

    @Synchronized
    private fun persist(state: ApiManagerState) {
        val configsObj = JSONObject()
        state.providers.forEach { (provider, cfg) ->
            val pObj = JSONObject().apply {
                put("selectedKeyId", cfg.selectedKeyId ?: "")
                put("selectedModel", cfg.selectedModel)
                put("customBaseUrl", cfg.customBaseUrl)
                put("availableModels", JSONArray(cfg.availableModels))
            }
            configsObj.put(provider.id, pObj)
        }

        val entriesArr = JSONArray()
        state.keysByProvider.values.flatten().forEach { entry ->
            val cipherText = encryptedSecretsById[entry.id] ?: return@forEach
            val item = JSONObject().apply {
                put("id", entry.id)
                put("provider", entry.provider.id)
                put("label", entry.label)
                put("maskedKey", entry.maskedKey)
                put("cipherText", cipherText)
                put("status", entry.status.name)
                if (entry.lastLatencyMs != null) put("lastLatencyMs", entry.lastLatencyMs)
                if (!entry.usageInfo.isNullOrBlank()) put("usageInfo", entry.usageInfo)
                if (!entry.lastError.isNullOrBlank()) put("lastError", entry.lastError)
                if (entry.lastCheckedAt != null) put("lastCheckedAt", entry.lastCheckedAt)
            }
            entriesArr.put(item)
        }

        prefs.edit()
            .putString(KEY_ACTIVE_PROVIDER, state.activeProvider.id)
            .putString(KEY_CONFIGS_JSON, configsObj.toString())
            .putString(KEY_ENTRIES_JSON, entriesArr.toString())
            .commit()

        _state.value = state
    }

    @Synchronized
    fun addKey(
        provider: AiProvider,
        rawApiKey: String,
        label: String = "",
        selectAsActiveKey: Boolean = true
    ): ApiKeyEntry {
        val trimmedKey = rawApiKey.trim()
        require(trimmedKey.isNotEmpty()) { "API key cannot be empty" }

        val id = UUID.randomUUID().toString()
        val encrypted = cipher.encrypt(trimmedKey)
        encryptedSecretsById[id] = encrypted

        val existingKeys = _state.value.getKeys(provider)
        val resolvedLabel = label.trim().ifBlank { "Key #${existingKeys.size + 1}" }
        val entry = ApiKeyEntry(
            id = id,
            provider = provider,
            label = resolvedLabel,
            maskedKey = maskApiKey(trimmedKey),
            status = ConnectionStatus.DISCONNECTED
        )

        val updatedKeys = existingKeys + entry
        val currentCfg = _state.value.getConfig(provider)
        val updatedCfg = if (selectAsActiveKey || currentCfg.selectedKeyId == null) {
            currentCfg.copy(selectedKeyId = entry.id)
        } else currentCfg

        val nextState = _state.value.copy(
            providers = _state.value.providers + (provider to updatedCfg),
            keysByProvider = _state.value.keysByProvider + (provider to updatedKeys)
        )
        persist(nextState)
        return entry
    }

    @Synchronized
    fun updateKey(
        provider: AiProvider,
        keyId: String,
        newRawApiKey: String?,
        newLabel: String
    ): ApiKeyEntry? {
        val existingKeys = _state.value.getKeys(provider)
        val target = existingKeys.firstOrNull { it.id == keyId } ?: return null

        val trimmedNewKey = newRawApiKey?.trim()?.takeIf { it.isNotEmpty() }
        val updatedMasked = if (trimmedNewKey != null) {
            encryptedSecretsById[keyId] = cipher.encrypt(trimmedNewKey)
            maskApiKey(trimmedNewKey)
        } else {
            target.maskedKey
        }

        val updatedEntry = target.copy(
            label = newLabel.trim().ifBlank { target.label },
            maskedKey = updatedMasked,
            status = if (trimmedNewKey != null) ConnectionStatus.DISCONNECTED else target.status,
            lastError = if (trimmedNewKey != null) null else target.lastError,
            usageInfo = if (trimmedNewKey != null) null else target.usageInfo
        )

        val updatedList = existingKeys.map { if (it.id == keyId) updatedEntry else it }
        val nextState = _state.value.copy(
            keysByProvider = _state.value.keysByProvider + (provider to updatedList)
        )
        persist(nextState)
        return updatedEntry
    }

    @Synchronized
    fun deleteKey(provider: AiProvider, keyId: String) {
        encryptedSecretsById.remove(keyId)
        val remaining = _state.value.getKeys(provider).filterNot { it.id == keyId }
        val cfg = _state.value.getConfig(provider)
        val nextSelectedId = if (cfg.selectedKeyId == keyId) remaining.firstOrNull()?.id else cfg.selectedKeyId
        val nextCfg = cfg.copy(selectedKeyId = nextSelectedId)

        val nextState = _state.value.copy(
            providers = _state.value.providers + (provider to nextCfg),
            keysByProvider = _state.value.keysByProvider + (provider to remaining)
        )
        persist(nextState)
    }

    @Synchronized
    fun selectKey(provider: AiProvider, keyId: String) {
        val keys = _state.value.getKeys(provider)
        if (keys.none { it.id == keyId }) return
        val cfg = _state.value.getConfig(provider).copy(selectedKeyId = keyId)
        persist(
            _state.value.copy(
                providers = _state.value.providers + (provider to cfg)
            )
        )
    }

    @Synchronized
    fun updateKeyStatus(
        provider: AiProvider,
        keyId: String,
        status: ConnectionStatus,
        latencyMs: Long? = null,
        usageInfo: String? = null,
        lastError: String? = null
    ) {
        val keys = _state.value.getKeys(provider)
        if (keys.none { it.id == keyId }) return
        val updated = keys.map { entry ->
            if (entry.id == keyId) {
                entry.copy(
                    status = status,
                    lastLatencyMs = latencyMs ?: entry.lastLatencyMs,
                    usageInfo = usageInfo,
                    lastError = lastError,
                    lastCheckedAt = System.currentTimeMillis()
                )
            } else entry
        }
        persist(
            _state.value.copy(
                keysByProvider = _state.value.keysByProvider + (provider to updated)
            )
        )
    }

    @Synchronized
    fun setActiveProvider(provider: AiProvider) {
        persist(_state.value.copy(activeProvider = provider))
    }

    @Synchronized
    fun setSelectedModel(provider: AiProvider, model: String) {
        val trimmed = model.trim()
        if (trimmed.isEmpty()) return
        val cfg = _state.value.getConfig(provider)
        val mergedModels = (listOf(trimmed) + cfg.availableModels).distinct()
        val updated = cfg.copy(selectedModel = trimmed, availableModels = mergedModels)
        persist(
            _state.value.copy(
                providers = _state.value.providers + (provider to updated)
            )
        )
    }

    @Synchronized
    fun setCustomBaseUrl(provider: AiProvider, baseUrl: String) {
        val normalized = baseUrl.trim().trimEnd('/').ifBlank { provider.defaultBaseUrl }
        val cfg = _state.value.getConfig(provider).copy(customBaseUrl = normalized)
        persist(
            _state.value.copy(
                providers = _state.value.providers + (provider to cfg)
            )
        )
    }

    @Synchronized
    fun updateAvailableModels(provider: AiProvider, models: List<String>) {
        if (models.isEmpty()) return
        val cfg = _state.value.getConfig(provider)
        val combined = (models + provider.defaultModels).distinct()
        val selected = if (combined.contains(cfg.selectedModel)) cfg.selectedModel else combined.first()
        val updated = cfg.copy(availableModels = combined, selectedModel = selected)
        persist(
            _state.value.copy(
                providers = _state.value.providers + (provider to updated)
            )
        )
    }

    fun getDecryptedKey(keyId: String): String? {
        val encrypted = encryptedSecretsById[keyId] ?: return null
        return try {
            cipher.decrypt(encrypted)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt API key")
            null
        }
    }

    fun getActiveDecryptedKey(provider: AiProvider = _state.value.activeProvider): String? {
        val activeEntry = _state.value.getSelectedKey(provider) ?: return null
        return getDecryptedKey(activeEntry.id)
    }
}
