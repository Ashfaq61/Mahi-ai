package com.example.memory

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class MemoryFact(
    val id: String = UUID.randomUUID().toString(),
    val category: String, // "Identity", "Language", "Preference", "Custom"
    val content: String,
    val updatedAtMs: Long = System.currentTimeMillis()
)

data class UserMemoryProfile(
    val userName: String = "",
    val preferredLanguageNote: String = "",
    val tonePreference: String = "",
    val facts: List<MemoryFact> = emptyList()
)

/**
 * Consent-gated persistent User Memory store in filesDir/user_memory.json.
 * Memory is NEVER written or injected into AI prompts unless memoryConsentGranted is true.
 */
class UserMemoryStore(context: Context) {
    companion object {
        private const val FILE_NAME = "user_memory.json"
        private const val MAX_FACTS = 40
    }

    private val file = File(context.applicationContext.filesDir, FILE_NAME)
    private val _profile = MutableStateFlow(load())
    val profile: StateFlow<UserMemoryProfile> = _profile.asStateFlow()

    private fun load(): UserMemoryProfile {
        if (!file.exists()) return UserMemoryProfile()
        return try {
            val root = JSONObject(file.readText())
            val arr = root.optJSONArray("facts") ?: JSONArray()
            val facts = buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    add(
                        MemoryFact(
                            id = obj.optString("id", UUID.randomUUID().toString()),
                            category = obj.optString("category", "Custom"),
                            content = obj.optString("content", ""),
                            updatedAtMs = obj.optLong("updatedAtMs", 0L)
                        )
                    )
                }
            }
            UserMemoryProfile(
                userName = root.optString("userName", ""),
                preferredLanguageNote = root.optString("preferredLanguageNote", ""),
                tonePreference = root.optString("tonePreference", ""),
                facts = facts
            )
        } catch (_: Exception) {
            UserMemoryProfile()
        }
    }

    private fun persist(profile: UserMemoryProfile) {
        val arr = JSONArray()
        profile.facts.take(MAX_FACTS).forEach { f ->
            arr.put(
                JSONObject().apply {
                    put("id", f.id)
                    put("category", f.category)
                    put("content", f.content)
                    put("updatedAtMs", f.updatedAtMs)
                }
            )
        }
        val root = JSONObject().apply {
            put("userName", profile.userName)
            put("preferredLanguageNote", profile.preferredLanguageNote)
            put("tonePreference", profile.tonePreference)
            put("facts", arr)
        }
        file.writeText(root.toString())
    }

    fun updateProfileFields(
        consentGranted: Boolean,
        userName: String = _profile.value.userName,
        preferredLanguageNote: String = _profile.value.preferredLanguageNote,
        tonePreference: String = _profile.value.tonePreference
    ): Boolean {
        if (!consentGranted) return false
        val updated = _profile.value.copy(
            userName = userName.trim(),
            preferredLanguageNote = preferredLanguageNote.trim(),
            tonePreference = tonePreference.trim()
        )
        _profile.value = updated
        persist(updated)
        return true
    }

    fun addOrUpdateFact(
        consentGranted: Boolean,
        category: String,
        content: String,
        existingId: String? = null
    ): Boolean {
        if (!consentGranted) return false
        val clean = content.trim()
        if (clean.isBlank()) return false

        val fact = MemoryFact(
            id = existingId ?: UUID.randomUUID().toString(),
            category = category.trim().ifBlank { "Custom" },
            content = clean
        )
        val updatedFacts = (_profile.value.facts.filterNot { it.id == fact.id } + fact).takeLast(MAX_FACTS)
        val updated = _profile.value.copy(facts = updatedFacts)
        _profile.value = updated
        persist(updated)
        return true
    }

    fun deleteFact(factId: String) {
        val updated = _profile.value.copy(
            facts = _profile.value.facts.filterNot { it.id == factId }
        )
        _profile.value = updated
        persist(updated)
    }

    fun forgetEverything() {
        _profile.value = UserMemoryProfile()
        if (file.exists()) file.delete()
    }

    fun buildPromptContext(consentGranted: Boolean): String {
        if (!consentGranted) return ""
        val p = _profile.value
        val lines = mutableListOf<String>()
        if (p.userName.isNotBlank()) lines.add("User's name: ${p.userName}")
        if (p.preferredLanguageNote.isNotBlank()) lines.add("Language preference: ${p.preferredLanguageNote}")
        if (p.tonePreference.isNotBlank()) lines.add("Tone preference: ${p.tonePreference}")
        p.facts.takeLast(12).forEach { fact ->
            lines.add("[${fact.category}] ${fact.content}")
        }
        return if (lines.isEmpty()) "" else "Consented User Memory:\n" + lines.joinToString("\n")
    }
}
