package com.example.calls

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class UnknownCallerPolicy(val displayName: String, val description: String) {
    ASK_OR_NOTIFY("Ring & Notify Normally", "Let standard ringtone alert you without blocking"),
    SILENCE_UNKNOWN("Silence Unknown Numbers", "Silence unknown callers without rejecting them"),
    REJECT_UNVERIFIED_SPAM("Reject Failed STIR/SHAKEN Spam", "Reject unknown callers only when network spam verification fails"),
    AI_SCREEN_WHEN_ENABLED("AI Call Assistant Screens", "Let disclosed AI Assistant answer & ask reason if enabled")
}

enum class CallAssistantVoice(val displayName: String, val pitch: Float, val speed: Float, val geminiVoice: String) {
    MAHI_WARM("Mahi Warm & Clear", 1.10f, 1.00f, "Aoede"),
    MAHI_PROFESSIONAL("Mahi Concierge", 1.02f, 0.98f, "Kore"),
    MAHI_EXPRESSIVE("Mahi Lively", 1.18f, 1.05f, "Aoede")
}

data class CallRuleContact(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val phoneNumber: String,
    val note: String = ""
) {
    fun matchesNumber(incoming: String): Boolean {
        val cleanIncoming = incoming.filter { it.isDigit() }
        val cleanRule = phoneNumber.filter { it.isDigit() }
        if (cleanIncoming.isEmpty() || cleanRule.isEmpty()) return false
        return cleanIncoming == cleanRule ||
            (cleanIncoming.length >= 7 && cleanRule.length >= 7 &&
                cleanIncoming.takeLast(7) == cleanRule.takeLast(7))
    }
}

/**
 * All autonomous call features strictly default to OFF / manualOnlyMode=true
 * until the user explicitly enables them in Call Automation Settings.
 */
data class CallAutomationPrefs(
    val autoAnswerEnabled: Boolean = false,
    val aiCallAssistantEnabled: Boolean = false,
    val manualOnlyMode: Boolean = true,
    val unknownCallerPolicy: UnknownCallerPolicy = UnknownCallerPolicy.ASK_OR_NOTIFY,
    val importantCallerRulesEnabled: Boolean = true,
    val emergencyOverrideEnabled: Boolean = true,
    val maxAiConversationSeconds: Int = 90,
    val quietHoursEnabled: Boolean = false,
    val quietHoursStartHour: Int = 22,
    val quietHoursEndHour: Int = 7,
    val assistantVoice: CallAssistantVoice = CallAssistantVoice.MAHI_WARM,
    val speakerphoneBridgeConsent: Boolean = false,
    val trustedContacts: List<CallRuleContact> = emptyList(),
    val blockedCallers: List<CallRuleContact> = emptyList(),
    val urgencyKeywords: List<String> = listOf("urgent", "emergency", "hospital", "accident", "doctor", "family", "important")
)

class CallAutomationPrefsStore(context: Context) {
    companion object {
        private const val PREFS_NAME = "mahi_call_automation_prefs"
        private const val KEY_JSON = "call_prefs_json"
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(load())
    val state: StateFlow<CallAutomationPrefs> = _state.asStateFlow()

    private fun load(): CallAutomationPrefs {
        val raw = prefs.getString(KEY_JSON, null) ?: return CallAutomationPrefs()
        return try {
            val obj = JSONObject(raw)
            CallAutomationPrefs(
                autoAnswerEnabled = obj.optBoolean("autoAnswerEnabled", false),
                aiCallAssistantEnabled = obj.optBoolean("aiCallAssistantEnabled", false),
                manualOnlyMode = obj.optBoolean("manualOnlyMode", true),
                unknownCallerPolicy = runCatching {
                    UnknownCallerPolicy.valueOf(obj.optString("unknownCallerPolicy"))
                }.getOrDefault(UnknownCallerPolicy.ASK_OR_NOTIFY),
                importantCallerRulesEnabled = obj.optBoolean("importantCallerRulesEnabled", true),
                emergencyOverrideEnabled = obj.optBoolean("emergencyOverrideEnabled", true),
                maxAiConversationSeconds = obj.optInt("maxAiConversationSeconds", 90).coerceIn(20, 300),
                quietHoursEnabled = obj.optBoolean("quietHoursEnabled", false),
                quietHoursStartHour = obj.optInt("quietHoursStartHour", 22).coerceIn(0, 23),
                quietHoursEndHour = obj.optInt("quietHoursEndHour", 7).coerceIn(0, 23),
                assistantVoice = runCatching {
                    CallAssistantVoice.valueOf(obj.optString("assistantVoice"))
                }.getOrDefault(CallAssistantVoice.MAHI_WARM),
                speakerphoneBridgeConsent = obj.optBoolean("speakerphoneBridgeConsent", false),
                trustedContacts = decodeContacts(obj.optJSONArray("trustedContacts")),
                blockedCallers = decodeContacts(obj.optJSONArray("blockedCallers"))
            )
        } catch (_: Exception) {
            CallAutomationPrefs()
        }
    }

    fun update(transform: (CallAutomationPrefs) -> CallAutomationPrefs) {
        val updated = transform(_state.value)
        save(updated)
        _state.value = updated
    }

    private fun save(value: CallAutomationPrefs) {
        val obj = JSONObject().apply {
            put("autoAnswerEnabled", value.autoAnswerEnabled)
            put("aiCallAssistantEnabled", value.aiCallAssistantEnabled)
            put("manualOnlyMode", value.manualOnlyMode)
            put("unknownCallerPolicy", value.unknownCallerPolicy.name)
            put("importantCallerRulesEnabled", value.importantCallerRulesEnabled)
            put("emergencyOverrideEnabled", value.emergencyOverrideEnabled)
            put("maxAiConversationSeconds", value.maxAiConversationSeconds)
            put("quietHoursEnabled", value.quietHoursEnabled)
            put("quietHoursStartHour", value.quietHoursStartHour)
            put("quietHoursEndHour", value.quietHoursEndHour)
            put("assistantVoice", value.assistantVoice.name)
            put("speakerphoneBridgeConsent", value.speakerphoneBridgeConsent)
            put("trustedContacts", encodeContacts(value.trustedContacts))
            put("blockedCallers", encodeContacts(value.blockedCallers))
        }
        prefs.edit().putString(KEY_JSON, obj.toString()).commit()
    }

    private fun encodeContacts(list: List<CallRuleContact>): JSONArray = JSONArray().apply {
        list.forEach { c ->
            put(JSONObject().apply {
                put("id", c.id)
                put("name", c.name)
                put("phoneNumber", c.phoneNumber)
                put("note", c.note)
            })
        }
    }

    private fun decodeContacts(arr: JSONArray?): List<CallRuleContact> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                add(
                    CallRuleContact(
                        id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                        name = item.optString("name"),
                        phoneNumber = item.optString("phoneNumber"),
                        note = item.optString("note")
                    )
                )
            }
        }
    }
}

enum class LiveCallStatus {
    IDLE,
    RINGING_INCOMING,
    DIALING_OUTGOING,
    ACTIVE,
    HOLDING,
    DISCONNECTED
}

enum class AiCallAssistantPhase {
    INACTIVE,
    AWAITING_USER_CONSENT_OR_OFFLINE,
    DISCLOSING_AI_AND_ASKING_REASON,
    ACTIVE_SPEAKERPHONE_ASSIST,
    URGENCY_ALERT_HANDOVER,
    MANUAL_TAKEOVER_ACTIVE
}

data class ActiveCallState(
    val callId: String = "",
    val phoneNumber: String = "",
    val callerDisplayName: String = "",
    val isSavedContact: Boolean = false,
    val isTrusted: Boolean = false,
    val isBlockedRuleMatch: Boolean = false,
    val isIncoming: Boolean = true,
    val status: LiveCallStatus = LiveCallStatus.IDLE,
    val verificationStatus: String = "NOT_VERIFIED",
    val startedAtMs: Long = 0L,
    val isMuted: Boolean = false,
    val isSpeakerphone: Boolean = false,
    val aiPhase: AiCallAssistantPhase = AiCallAssistantPhase.INACTIVE,
    val callerReasonSummary: String = "",
    val isUrgent: Boolean = false,
    val aiDialogueLog: List<String> = emptyList(),
    val directCellularAudioLimitation: String =
        "Android restricts third-party apps from reading raw cellular call audio streams directly. " +
            "AI Call Assistant uses explicit speakerphone + user consent when enabled."
)

data class CallAutomationRecord(
    val id: String = UUID.randomUUID().toString(),
    val phoneNumber: String,
    val callerName: String,
    val direction: String, // INCOMING, OUTGOING, MISSED, SCREENED
    val actionTaken: String,
    val timestampMs: Long = System.currentTimeMillis(),
    val wasUrgent: Boolean = false,
    val aiHandled: Boolean = false
)

class CallHistoryStore(context: Context) {
    companion object {
        private const val PREFS_NAME = "mahi_call_history"
        private const val KEY_ITEMS = "call_history_json"
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _records = MutableStateFlow(load())
    val records: StateFlow<List<CallAutomationRecord>> = _records.asStateFlow()

    private fun load(): List<CallAutomationRecord> {
        val raw = prefs.getString(KEY_ITEMS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    add(
                        CallAutomationRecord(
                            id = o.optString("id"),
                            phoneNumber = o.optString("phoneNumber"),
                            callerName = o.optString("callerName"),
                            direction = o.optString("direction"),
                            actionTaken = o.optString("actionTaken"),
                            timestampMs = o.optLong("timestampMs"),
                            wasUrgent = o.optBoolean("wasUrgent"),
                            aiHandled = o.optBoolean("aiHandled")
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun record(entry: CallAutomationRecord) {
        val updated = (listOf(entry) + _records.value).take(40)
        val arr = JSONArray()
        updated.forEach { item ->
            arr.put(JSONObject().apply {
                put("id", item.id)
                put("phoneNumber", item.phoneNumber)
                put("callerName", item.callerName)
                put("direction", item.direction)
                put("actionTaken", item.actionTaken)
                put("timestampMs", item.timestampMs)
                put("wasUrgent", item.wasUrgent)
                put("aiHandled", item.aiHandled)
            })
        }
        prefs.edit().putString(KEY_ITEMS, arr.toString()).commit()
        _records.value = updated
    }

    fun clear() {
        prefs.edit().remove(KEY_ITEMS).commit()
        _records.value = emptyList()
    }
}
