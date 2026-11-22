package com.example.calls

import android.Manifest
import android.annotation.SuppressLint
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.CallScreeningService
import android.telecom.InCallService
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import androidx.core.content.ContextCompat
import com.example.ai.MahiAiEngine
import com.example.ai.MahiMood
import com.example.api.ProviderChatMessage
import com.example.api.SecureApiKeyStore
import com.example.audio.MahiSpeechSynthesizer
import com.example.automation.AutomationOutcome
import com.example.preferences.AppLanguage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.UUID

enum class LiveCallStatus {
    IDLE,
    RINGING_INCOMING,
    DIALING_OUTGOING,
    ACTIVE,
    HOLDING,
    DISCONNECTED
}

enum class AiCallAssistantPhase {
    OFF,
    DISCLOSING_AI,
    LISTENING_FOR_INTENT,
    URGENT_ESCALATION,
    MANUAL_HUMAN_CONTROL
}

enum class CallAssistantVoice(val displayName: String) {
    PROFESSIONAL("Professional"),
    FRIENDLY("Friendly"),
    CONCISE("Concise")
}

enum class UnknownCallerPolicy(val displayName: String) {
    ASK_BEFORE_ANSWER("Ask Me"),
    SCREEN_WITH_AI("Screen AI"),
    SILENCE_UNKNOWN("Silence")
}

data class ContactRuleEntry(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val phoneNumber: String
)

data class CallAutomationPrefs(
    val manualOnlyMode: Boolean = true,
    val autoAnswerEnabled: Boolean = false,
    val aiCallAssistantEnabled: Boolean = false,
    val speakerphoneBridgeConsent: Boolean = false,
    val emergencyOverrideEnabled: Boolean = true,
    val importantCallerRulesEnabled: Boolean = true,
    val quietHoursEnabled: Boolean = false,
    val quietHoursStartHour: Int = 23,
    val quietHoursEndHour: Int = 7,
    val maxAiConversationSeconds: Int = 45,
    val unknownCallerPolicy: UnknownCallerPolicy = UnknownCallerPolicy.ASK_BEFORE_ANSWER,
    val assistantVoice: CallAssistantVoice = CallAssistantVoice.PROFESSIONAL,
    val trustedContacts: List<ContactRuleEntry> = emptyList(),
    val blockedCallers: List<ContactRuleEntry> = emptyList()
)

data class ActiveCallState(
    val status: LiveCallStatus = LiveCallStatus.IDLE,
    val callerDisplayName: String = "",
    val phoneNumber: String = "",
    val isMuted: Boolean = false,
    val isSpeakerphone: Boolean = false,
    val aiPhase: AiCallAssistantPhase = AiCallAssistantPhase.OFF,
    val aiDialogueLog: List<String> = emptyList(),
    val isEmergencyCaller: Boolean = false
)

data class CallAutomationRecord(
    val id: String = UUID.randomUUID().toString(),
    val direction: String,
    val callerName: String,
    val phoneNumber: String,
    val actionTaken: String,
    val wasUrgent: Boolean = false,
    val timestampMs: Long = System.currentTimeMillis()
)

data class TelecomCapabilityStatus(
    val hasPhoneStatePermission: Boolean = false,
    val hasCallPhonePermission: Boolean = false,
    val hasAnswerCallsPermission: Boolean = false,
    val hasContactsPermission: Boolean = false,
    val isDefaultDialer: Boolean = false,
    val isCallScreeningApp: Boolean = false,
    val audioLimitationExplanation: String = "Android does not allow standard apps to inject AI audio directly into the cellular uplink stream. With explicit Speakerphone Bridge Consent, Mahi uses Speakerphone + TTS + Mic to speak with callers aloud and never secretly records calls."
)

object CallBridgeBus {
    private val _activeCall = MutableStateFlow(ActiveCallState())
    val activeCall: StateFlow<ActiveCallState> = _activeCall.asStateFlow()

    @Volatile
    var currentTelecomCall: Call? = null

    @Volatile
    var activeInCallService: MahiInCallService? = null

    fun updateActiveCall(transform: (ActiveCallState) -> ActiveCallState) {
        _activeCall.value = transform(_activeCall.value)
    }
}

class MahiInCallService : InCallService() {
    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            publishCall(call, state)
        }
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        CallBridgeBus.activeInCallService = this
        CallBridgeBus.currentTelecomCall = call
        call.registerCallback(callback)
        publishCall(call, call.state)
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        call.unregisterCallback(callback)
        if (CallBridgeBus.currentTelecomCall === call) {
            CallBridgeBus.currentTelecomCall = null
            CallBridgeBus.updateActiveCall { ActiveCallState() }
        }
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        super.onCallAudioStateChanged(audioState)
        audioState ?: return
        CallBridgeBus.updateActiveCall {
            it.copy(
                isMuted = audioState.isMuted,
                isSpeakerphone = (audioState.route and CallAudioState.ROUTE_SPEAKER) != 0
            )
        }
    }

    private fun publishCall(call: Call, state: Int) {
        val number = call.details?.handle?.schemeSpecificPart.orEmpty()
        val display = call.details?.callerDisplayName?.takeIf { it.isNotBlank() } ?: number.ifBlank { "Unknown Caller" }
        val status = when (state) {
            Call.STATE_RINGING -> LiveCallStatus.RINGING_INCOMING
            Call.STATE_DIALING, Call.STATE_CONNECTING -> LiveCallStatus.DIALING_OUTGOING
            Call.STATE_ACTIVE -> LiveCallStatus.ACTIVE
            Call.STATE_HOLDING -> LiveCallStatus.HOLDING
            Call.STATE_DISCONNECTED, Call.STATE_DISCONNECTING -> LiveCallStatus.DISCONNECTED
            else -> LiveCallStatus.IDLE
        }
        CallBridgeBus.updateActiveCall { cur ->
            cur.copy(
                status = status,
                callerDisplayName = display,
                phoneNumber = number
            )
        }
    }
}

class MahiCallScreeningService : CallScreeningService() {
    private val recentCallTimestamps = mutableMapOf<String, MutableList<Long>>()

    override fun onScreenCall(callDetails: Call.Details) {
        val number = callDetails.handle?.schemeSpecificPart.orEmpty()
        val prefs = CallAssistantManager(applicationContext).prefsFlow.value
        val now = System.currentTimeMillis()
        val history = recentCallTimestamps.getOrPut(number) { mutableListOf() }
        history.removeAll { now - it > 180_000L }
        history.add(now)
        val isRepeatedEmergency = prefs.emergencyOverrideEnabled && history.size >= 2

        val isBlocked = !isRepeatedEmergency && prefs.blockedCallers.any {
            numbersMatch(it.phoneNumber, number)
        }
        val isTrusted = prefs.trustedContacts.any {
            numbersMatch(it.phoneNumber, number)
        }
        val inQuietHours = prefs.quietHoursEnabled && isNowInQuietHours(prefs.quietHoursStartHour, prefs.quietHoursEndHour)

        val response = CallResponse.Builder()
            .setDisallowCall(isBlocked)
            .setRejectCall(isBlocked)
            .setSilenceCall(!isRepeatedEmergency && !isTrusted && inQuietHours && !prefs.manualOnlyMode)
            .setSkipCallLog(false)
            .setSkipNotification(isBlocked)
            .build()
        respondToCall(callDetails, response)
    }

    private fun numbersMatch(a: String, b: String): Boolean {
        val da = a.filter { it.isDigit() }
        val db = b.filter { it.isDigit() }
        return da.isNotBlank() && db.isNotBlank() && (da.endsWith(db.takeLast(8)) || db.endsWith(da.takeLast(8)))
    }

    private fun isNowInQuietHours(start: Int, end: Int): Boolean {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return if (start <= end) hour in start until end else (hour >= start || hour < end)
    }
}

class CallAssistantManager(
    private val context: Context,
    private val aiEngine: MahiAiEngine = MahiAiEngine(),
    private val keyStore: SecureApiKeyStore = SecureApiKeyStore(context)
) {
    companion object {
        private const val PREFS_FILE = "call_automation_prefs.json"
        private const val HISTORY_FILE = "call_automation_history.json"
        private val URGENCY_WORDS = listOf("urgent", "emergency", "hospital", "accident", "police", "help", "jaldi", "zaroori")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefsFile = File(context.applicationContext.filesDir, PREFS_FILE)
    private val historyFile = File(context.applicationContext.filesDir, HISTORY_FILE)

    private val _prefsFlow = MutableStateFlow(loadPrefs())
    val prefsFlow: StateFlow<CallAutomationPrefs> = _prefsFlow.asStateFlow()

    private val _history = MutableStateFlow(loadHistory())
    val history: StateFlow<List<CallAutomationRecord>> = _history.asStateFlow()

    private val _capabilities = MutableStateFlow(inspectCapabilities())
    val capabilities: StateFlow<TelecomCapabilityStatus> = _capabilities.asStateFlow()

    private val _importantAlert = MutableStateFlow<String?>(null)
    val importantAlert: StateFlow<String?> = _importantAlert.asStateFlow()

    private val _feedback = MutableStateFlow<String?>(null)
    val feedback: StateFlow<String?> = _feedback.asStateFlow()

    val activeCall: StateFlow<ActiveCallState> = CallBridgeBus.activeCall
    private var ttsBridge: MahiSpeechSynthesizer? = null
    private var maxDurationJob: Job? = null

    fun refreshCapabilities() {
        _capabilities.value = inspectCapabilities()
    }

    fun inspectCapabilities(): TelecomCapabilityStatus {
        val tm = context.getSystemService(TelecomManager::class.java)
        val hasPhoneState = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        val hasCallPhone = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val hasAnswer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        val hasContacts = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        val isDialer = tm?.defaultDialerPackage == context.packageName
        val isScreening = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = context.getSystemService(RoleManager::class.java)
            rm?.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) == true
        } else {
            false
        }
        return TelecomCapabilityStatus(
            hasPhoneStatePermission = hasPhoneState,
            hasCallPhonePermission = hasCallPhone,
            hasAnswerCallsPermission = hasAnswer,
            hasContactsPermission = hasContacts,
            isDefaultDialer = isDialer,
            isCallScreeningApp = isScreening
        )
    }

    fun updatePrefs(mutator: (CallAutomationPrefs) -> CallAutomationPrefs) {
        val next = mutator(_prefsFlow.value)
        _prefsFlow.value = next
        persistPrefs(next)
    }

    fun addTrustedContact(name: String, phone: String) {
        val cleanPhone = phone.trim()
        if (cleanPhone.isBlank()) return
        val entry = ContactRuleEntry(name = name.trim().ifBlank { cleanPhone }, phoneNumber = cleanPhone)
        updatePrefs { it.copy(trustedContacts = (it.trustedContacts + entry).distinctBy { r -> r.phoneNumber }) }
    }

    fun removeTrustedContact(id: String) {
        updatePrefs { it.copy(trustedContacts = it.trustedContacts.filterNot { r -> r.id == id }) }
    }

    fun addBlockedCaller(name: String, phone: String) {
        val cleanPhone = phone.trim()
        if (cleanPhone.isBlank()) return
        val entry = ContactRuleEntry(name = name.trim().ifBlank { cleanPhone }, phoneNumber = cleanPhone)
        updatePrefs { it.copy(blockedCallers = (it.blockedCallers + entry).distinctBy { r -> r.phoneNumber }) }
    }

    fun removeBlockedCaller(id: String) {
        updatePrefs { it.copy(blockedCallers = it.blockedCallers.filterNot { r -> r.id == id }) }
    }

    fun dismissAlert() {
        _importantAlert.value = null
    }

    fun clearHistory() {
        _history.value = emptyList()
        if (historyFile.exists()) historyFile.delete()
    }

    @SuppressLint("MissingPermission")
    fun resolveAndPlaceCall(rawTarget: String): AutomationOutcome {
        val trimmed = rawTarget.trim()
        if (trimmed.isBlank()) return AutomationOutcome(false, "Specify a phone number or contact name.")
        val isDigits = trimmed.any { it.isDigit() } && trimmed.none { it.isLetter() }
        val resolvedNumber = if (isDigits) trimmed else lookupContactNumber(trimmed)
        if (resolvedNumber.isNullOrBlank()) {
            _feedback.value = "No contact matched '$trimmed'."
            return AutomationOutcome(false, "No contact found for '$trimmed'.")
        }

        val uri = Uri.fromParts("tel", resolvedNumber, null)
        val hasCallPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        return try {
            if (hasCallPermission) {
                val tm = context.getSystemService(TelecomManager::class.java)
                tm?.placeCall(uri, null)
            } else {
                val intent = Intent(Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
            val msg = if (hasCallPermission) "Calling $trimmed ($resolvedNumber)" else "Opened dialer for $resolvedNumber"
            _feedback.value = msg
            appendHistory(CallAutomationRecord(direction = "Outgoing", callerName = trimmed, phoneNumber = resolvedNumber, actionTaken = msg))
            AutomationOutcome(true, msg)
        } catch (e: Exception) {
            val err = "Unable to place call: ${e.localizedMessage}"
            _feedback.value = err
            AutomationOutcome(false, err)
        }
    }

    @SuppressLint("MissingPermission")
    fun answerActiveCall(useSpeakerAi: Boolean) {
        val call = CallBridgeBus.currentTelecomCall
        if (call != null) {
            call.answer(VideoProfile.STATE_AUDIO_ONLY)
            if (useSpeakerAi) {
                if (!_prefsFlow.value.speakerphoneBridgeConsent) {
                    _feedback.value = "Enable Speakerphone Bridge Consent in Call Settings before AI speaks aloud."
                } else {
                    setSpeakerphone(true)
                    startDisclosedAiAssistant()
                }
            }
            _feedback.value = if (useSpeakerAi) "Answered call with disclosed AI Speaker Bridge" else "Answered call"
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED
        ) {
            runCatching {
                @Suppress("DEPRECATION")
                context.getSystemService(TelecomManager::class.java)?.acceptRingingCall()
                _feedback.value = "Accepted incoming call via TelecomManager"
            }.onFailure {
                _feedback.value = "Could not accept call: ${it.localizedMessage}"
            }
        } else {
            _feedback.value = "No active call or missing ANSWER_PHONE_CALLS permission."
        }
    }

    @SuppressLint("MissingPermission")
    fun rejectOrEndActiveCall() {
        maxDurationJob?.cancel()
        val call = CallBridgeBus.currentTelecomCall
        if (call != null) {
            if (call.state == Call.STATE_RINGING) {
                call.reject(false, null)
            } else {
                call.disconnect()
            }
            _feedback.value = "Call terminated."
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED
        ) {
            @Suppress("DEPRECATION")
            context.getSystemService(TelecomManager::class.java)?.endCall()
            _feedback.value = "Ended active call."
        }
    }

    fun toggleMute() {
        val svc = CallBridgeBus.activeInCallService ?: return
        val next = !CallBridgeBus.activeCall.value.isMuted
        svc.setMuted(next)
        CallBridgeBus.updateActiveCall { it.copy(isMuted = next) }
    }

    fun toggleSpeakerphone() {
        setSpeakerphone(!CallBridgeBus.activeCall.value.isSpeakerphone)
    }

    private fun setSpeakerphone(enabled: Boolean) {
        val svc = CallBridgeBus.activeInCallService
        if (svc != null) {
            val route = if (enabled) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_EARPIECE
            svc.setAudioRoute(route)
        } else {
            val am = context.getSystemService(AudioManager::class.java)
            @Suppress("DEPRECATION")
            am?.isSpeakerphoneOn = enabled
        }
        CallBridgeBus.updateActiveCall { it.copy(isSpeakerphone = enabled) }
    }

    fun manualTakeover() {
        maxDurationJob?.cancel()
        ttsBridge?.stop()
        CallBridgeBus.updateActiveCall {
            it.copy(
                aiPhase = AiCallAssistantPhase.MANUAL_HUMAN_CONTROL,
                aiDialogueLog = it.aiDialogueLog + "[System] Manual takeover active — AI paused."
            )
        }
        _feedback.value = "Manual human takeover active."
    }

    private fun startDisclosedAiAssistant() {
        val prefs = _prefsFlow.value
        val disclosure = "Hello, this is Mahi, an AI assistant answering on behalf of the user. How can I help or take a message?"
        CallBridgeBus.updateActiveCall {
            it.copy(
                aiPhase = AiCallAssistantPhase.DISCLOSING_AI,
                aiDialogueLog = it.aiDialogueLog + "Mahi: $disclosure"
            )
        }
        speakAloudIfConsented(disclosure)
        maxDurationJob?.cancel()
        maxDurationJob = scope.launch {
            delay(prefs.maxAiConversationSeconds * 1000L)
            CallBridgeBus.updateActiveCall {
                it.copy(
                    aiPhase = AiCallAssistantPhase.MANUAL_HUMAN_CONTROL,
                    aiDialogueLog = it.aiDialogueLog + "[System] Max AI call duration (${prefs.maxAiConversationSeconds}s) reached."
                )
            }
        }
    }

    fun processCallerUtterance(utterance: String, language: AppLanguage = AppLanguage.ENGLISH) {
        val clean = utterance.trim()
        if (clean.isBlank()) return
        val urgent = URGENCY_WORDS.any { clean.contains(it, ignoreCase = true) }
        CallBridgeBus.updateActiveCall {
            it.copy(
                aiPhase = if (urgent) AiCallAssistantPhase.URGENT_ESCALATION else AiCallAssistantPhase.LISTENING_FOR_INTENT,
                aiDialogueLog = it.aiDialogueLog + "Caller: $clean"
            )
        }
        if (urgent) {
            _importantAlert.value = "URGENT CALLER MESSAGE: $clean"
        }

        scope.launch {
            val reply = if (keyStore.decryptSlotKey(com.example.api.AiProvider.GEMINI) != null) {
                aiEngine.generateReply(
                    keyStore = keyStore,
                    mood = MahiMood.PROFESSIONAL,
                    language = language,
                    history = listOf(ProviderChatMessage("user", "Caller said during phone call: $clean")),
                    userMessage = "Reply concisely in 1-2 sentences as a disclosed call assistant (${_prefsFlow.value.assistantVoice.displayName})."
                ).getOrNull()?.text
            } else {
                null
            } ?: if (urgent) {
                "I understand this is urgent. I am alerting the user immediately to take over the call."
            } else {
                "Thank you for letting me know. I have noted your message for the user."
            }

            CallBridgeBus.updateActiveCall {
                it.copy(aiDialogueLog = it.aiDialogueLog + "Mahi: $reply")
            }
            speakAloudIfConsented(reply)
            appendHistory(
                CallAutomationRecord(
                    direction = "AI Bridge",
                    callerName = CallBridgeBus.activeCall.value.callerDisplayName.ifBlank { "Caller" },
                    phoneNumber = CallBridgeBus.activeCall.value.phoneNumber,
                    actionTaken = "Caller: '$clean' -> Mahi: '$reply'",
                    wasUrgent = urgent
                )
            )
        }
    }

    private fun speakAloudIfConsented(text: String) {
        if (!_prefsFlow.value.speakerphoneBridgeConsent) return
        val tts = ttsBridge ?: MahiSpeechSynthesizer(context).also { ttsBridge = it }
        tts.speak(text, AppLanguage.ENGLISH)
    }

    private fun lookupContactNumber(nameQuery: String): String? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        return runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$nameQuery%"),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(1) else null
            }
        }.getOrNull()
    }

    private fun appendHistory(record: CallAutomationRecord) {
        val next = (listOf(record) + _history.value).take(40)
        _history.value = next
        persistHistory(next)
    }

    private fun loadPrefs(): CallAutomationPrefs {
        if (!prefsFile.exists()) return CallAutomationPrefs()
        return try {
            val o = JSONObject(prefsFile.readText())
            fun parseRules(arr: JSONArray?): List<ContactRuleEntry> {
                if (arr == null) return emptyList()
                return buildList {
                    for (i in 0 until arr.length()) {
                        val r = arr.optJSONObject(i) ?: continue
                        add(ContactRuleEntry(r.optString("id"), r.optString("name"), r.optString("phone")))
                    }
                }
            }
            CallAutomationPrefs(
                manualOnlyMode = o.optBoolean("manualOnlyMode", true),
                autoAnswerEnabled = o.optBoolean("autoAnswerEnabled", false),
                aiCallAssistantEnabled = o.optBoolean("aiCallAssistantEnabled", false),
                speakerphoneBridgeConsent = o.optBoolean("speakerphoneBridgeConsent", false),
                emergencyOverrideEnabled = o.optBoolean("emergencyOverrideEnabled", true),
                importantCallerRulesEnabled = o.optBoolean("importantCallerRulesEnabled", true),
                quietHoursEnabled = o.optBoolean("quietHoursEnabled", false),
                maxAiConversationSeconds = o.optInt("maxAiConversationSeconds", 45).coerceIn(20, 180),
                unknownCallerPolicy = runCatching { UnknownCallerPolicy.valueOf(o.optString("unknownCallerPolicy")) }
                    .getOrDefault(UnknownCallerPolicy.ASK_BEFORE_ANSWER),
                assistantVoice = runCatching { CallAssistantVoice.valueOf(o.optString("assistantVoice")) }
                    .getOrDefault(CallAssistantVoice.PROFESSIONAL),
                trustedContacts = parseRules(o.optJSONArray("trustedContacts")),
                blockedCallers = parseRules(o.optJSONArray("blockedCallers"))
            )
        } catch (_: Exception) {
            CallAutomationPrefs()
        }
    }

    private fun persistPrefs(prefs: CallAutomationPrefs) {
        runCatching {
            fun encodeRules(list: List<ContactRuleEntry>) = JSONArray().apply {
                list.forEach { r ->
                    put(JSONObject().put("id", r.id).put("name", r.name).put("phone", r.phoneNumber))
                }
            }
            val o = JSONObject().apply {
                put("manualOnlyMode", prefs.manualOnlyMode)
                put("autoAnswerEnabled", prefs.autoAnswerEnabled)
                put("aiCallAssistantEnabled", prefs.aiCallAssistantEnabled)
                put("speakerphoneBridgeConsent", prefs.speakerphoneBridgeConsent)
                put("emergencyOverrideEnabled", prefs.emergencyOverrideEnabled)
                put("importantCallerRulesEnabled", prefs.importantCallerRulesEnabled)
                put("quietHoursEnabled", prefs.quietHoursEnabled)
                put("maxAiConversationSeconds", prefs.maxAiConversationSeconds)
                put("unknownCallerPolicy", prefs.unknownCallerPolicy.name)
                put("assistantVoice", prefs.assistantVoice.name)
                put("trustedContacts", encodeRules(prefs.trustedContacts))
                put("blockedCallers", encodeRules(prefs.blockedCallers))
            }
            prefsFile.writeText(o.toString())
        }
    }

    private fun loadHistory(): List<CallAutomationRecord> {
        if (!historyFile.exists()) return emptyList()
        return try {
            val arr = JSONArray(historyFile.readText())
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    add(
                        CallAutomationRecord(
                            id = o.optString("id"),
                            direction = o.optString("direction"),
                            callerName = o.optString("callerName"),
                            phoneNumber = o.optString("phoneNumber"),
                            actionTaken = o.optString("actionTaken"),
                            wasUrgent = o.optBoolean("wasUrgent"),
                            timestampMs = o.optLong("timestampMs")
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun persistHistory(list: List<CallAutomationRecord>) {
        runCatching {
            val arr = JSONArray()
            list.forEach { r ->
                arr.put(
                    JSONObject().put("id", r.id).put("direction", r.direction)
                        .put("callerName", r.callerName).put("phoneNumber", r.phoneNumber)
                        .put("actionTaken", r.actionTaken).put("wasUrgent", r.wasUrgent)
                        .put("timestampMs", r.timestampMs)
                )
            }
            historyFile.writeText(arr.toString())
        }
    }
}
