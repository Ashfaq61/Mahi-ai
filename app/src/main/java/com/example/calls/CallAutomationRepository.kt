package com.example.calls

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat
import com.example.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

enum class ScreeningDecisionAction {
    ALLOW_RING,
    SILENCE_CALL,
    REJECT_CALL,
    AUTO_ANSWER_STANDARD,
    AUTO_ANSWER_WITH_AI_DISCLOSURE,
    EMERGENCY_OVERRIDE_RING
}

data class CallScreeningDecision(
    val action: ScreeningDecisionAction,
    val reason: String,
    val isTrusted: Boolean,
    val isBlocked: Boolean,
    val isEmergencyOverride: Boolean
)

data class TelecomCapabilityStatus(
    val isDefaultDialer: Boolean = false,
    val isCallScreeningApp: Boolean = false,
    val hasPhoneStatePermission: Boolean = false,
    val hasCallPhonePermission: Boolean = false,
    val hasAnswerCallsPermission: Boolean = false,
    val hasContactsPermission: Boolean = false,
    val hasMicPermission: Boolean = false,
    val hasDirectCellularAudioAccess: Boolean = false,
    val audioLimitationExplanation: String =
        "Android restricts third-party apps from capturing or injecting raw cellular RX/TX call audio directly. " +
            "Use Speakerphone Bridge (with caller disclosure & consent) or Manual Takeover anytime."
)

data class ResolvedContact(
    val displayName: String,
    val phoneNumber: String
)

object CallAutomationRepository {
    private const val CAPTURE_AUDIO_OUTPUT_PERMISSION = "android.permission.CAPTURE_AUDIO_OUTPUT"
    private const val IMPORTANT_CALL_CHANNEL_ID = "mahi_important_calls"

    @Volatile
    private var prefsStore: CallAutomationPrefsStore? = null

    @Volatile
    private var historyStore: CallHistoryStore? = null

    private val recentCallTimestampsMs = ConcurrentHashMap<String, MutableList<Long>>()

    private val _prefs = MutableStateFlow(CallAutomationPrefs())
    val prefs: StateFlow<CallAutomationPrefs> = _prefs.asStateFlow()

    private val _activeCall = MutableStateFlow(ActiveCallState())
    val activeCall: StateFlow<ActiveCallState> = _activeCall.asStateFlow()

    private val _callHistory = MutableStateFlow<List<CallAutomationRecord>>(emptyList())
    val callHistory: StateFlow<List<CallAutomationRecord>> = _callHistory.asStateFlow()

    private val _capabilities = MutableStateFlow(TelecomCapabilityStatus())
    val capabilities: StateFlow<TelecomCapabilityStatus> = _capabilities.asStateFlow()

    private val _importantCallAlert = MutableStateFlow<String?>(null)
    val importantCallAlert: StateFlow<String?> = _importantCallAlert.asStateFlow()

    fun init(context: Context) {
        if (prefsStore == null) {
            prefsStore = CallAutomationPrefsStore(context.applicationContext)
            _prefs.value = prefsStore!!.state.value
        }
        if (historyStore == null) {
            historyStore = CallHistoryStore(context.applicationContext)
            _callHistory.value = historyStore!!.records.value
        }
        refreshCapabilities(context)
    }

    fun updatePrefs(context: Context, transform: (CallAutomationPrefs) -> CallAutomationPrefs) {
        init(context)
        prefsStore?.update(transform)
        _prefs.value = prefsStore!!.state.value
    }

    fun updateActiveCall(transform: (ActiveCallState) -> ActiveCallState) {
        _activeCall.value = transform(_activeCall.value)
    }

    fun clearImportantAlert() {
        _importantCallAlert.value = null
    }

    fun recordCallEvent(context: Context, record: CallAutomationRecord) {
        init(context)
        historyStore?.record(record)
        _callHistory.value = historyStore!!.records.value
    }

    fun clearHistory(context: Context) {
        init(context)
        historyStore?.clear()
        _callHistory.value = emptyList()
    }

    fun registerIncomingAttemptTimestamp(phoneNumber: String, nowMs: Long = System.currentTimeMillis()): Int {
        val clean = phoneNumber.filter { it.isDigit() }.takeLast(10)
        if (clean.isEmpty()) return 0
        val list = recentCallTimestampsMs.getOrPut(clean) { mutableListOf() }
        synchronized(list) {
            list.removeAll { nowMs - it > 180_000L } // 3-minute window for emergency repeat calls
            val priorCount = list.size
            list.add(nowMs)
            return priorCount
        }
    }

    fun isHourInQuietWindow(hour24: Int, startHour: Int, endHour: Int): Boolean {
        return if (startHour == endHour) {
            false
        } else if (startHour < endHour) {
            hour24 in startHour until endHour
        } else {
            hour24 >= startHour || hour24 < endHour
        }
    }

    fun evaluateIncomingCallPolicy(
        phoneNumber: String,
        isSavedContact: Boolean,
        verificationFailed: Boolean,
        prefs: CallAutomationPrefs,
        currentHourOfDay: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
        recentCallsInWindow: Int = 0
    ): CallScreeningDecision {
        val isTrusted = prefs.trustedContacts.any { it.matchesNumber(phoneNumber) }
        val isBlocked = prefs.blockedCallers.any { it.matchesNumber(phoneNumber) }
        val isRepeatEmergency = prefs.emergencyOverrideEnabled && recentCallsInWindow >= 1

        // 1. Emergency override always takes top priority over quiet hours and blocking
        if (isRepeatEmergency && (isTrusted || isSavedContact)) {
            return CallScreeningDecision(
                action = ScreeningDecisionAction.EMERGENCY_OVERRIDE_RING,
                reason = "Repeated call within 3 minutes — Emergency Override prioritizes owner connection.",
                isTrusted = isTrusted,
                isBlocked = false,
                isEmergencyOverride = true
            )
        }

        // 2. Manual-only safety mode keeps all calls manual
        if (prefs.manualOnlyMode) {
            val blockWarning = if (isBlocked) "Blocked rule match (Manual-only mode active)" else "Manual-only mode active"
            return CallScreeningDecision(
                action = ScreeningDecisionAction.ALLOW_RING,
                reason = blockWarning,
                isTrusted = isTrusted,
                isBlocked = isBlocked,
                isEmergencyOverride = false
            )
        }

        // 3. Blocked callers list
        if (isBlocked && !isTrusted) {
            return CallScreeningDecision(
                action = ScreeningDecisionAction.REJECT_CALL,
                reason = "Matched user-defined Blocked Callers list.",
                isTrusted = false,
                isBlocked = true,
                isEmergencyOverride = false
            )
        }

        // 4. Quiet hours (trusted emergency contacts override when enabled)
        val inQuietHours = prefs.quietHoursEnabled &&
            isHourInQuietWindow(currentHourOfDay, prefs.quietHoursStartHour, prefs.quietHoursEndHour)
        if (inQuietHours && !(prefs.emergencyOverrideEnabled && isTrusted)) {
            return CallScreeningDecision(
                action = ScreeningDecisionAction.SILENCE_CALL,
                reason = "Quiet hours active (${prefs.quietHoursStartHour}:00–${prefs.quietHoursEndHour}:00).",
                isTrusted = isTrusted,
                isBlocked = false,
                isEmergencyOverride = false
            )
        }

        // 5. Unknown caller policies
        if (!isSavedContact && !isTrusted) {
            when (prefs.unknownCallerPolicy) {
                UnknownCallerPolicy.REJECT_UNVERIFIED_SPAM -> {
                    if (verificationFailed) {
                        return CallScreeningDecision(
                            action = ScreeningDecisionAction.REJECT_CALL,
                            reason = "Carrier STIR/SHAKEN verification failed for unknown caller.",
                            isTrusted = false,
                            isBlocked = false,
                            isEmergencyOverride = false
                        )
                    }
                }
                UnknownCallerPolicy.SILENCE_UNKNOWN -> {
                    return CallScreeningDecision(
                        action = ScreeningDecisionAction.SILENCE_CALL,
                        reason = "Unknown caller silenced per user rule.",
                        isTrusted = false,
                        isBlocked = false,
                        isEmergencyOverride = false
                    )
                }
                UnknownCallerPolicy.AI_SCREEN_WHEN_ENABLED -> {
                    if (prefs.aiCallAssistantEnabled && prefs.autoAnswerEnabled) {
                        return CallScreeningDecision(
                            action = ScreeningDecisionAction.AUTO_ANSWER_WITH_AI_DISCLOSURE,
                            reason = "Unknown caller eligible for disclosed AI Call Assistant screening.",
                            isTrusted = false,
                            isBlocked = false,
                            isEmergencyOverride = false
                        )
                    }
                }
                UnknownCallerPolicy.ASK_OR_NOTIFY -> Unit
            }
        }

        // 6. Auto-answer / AI Call Assistant only when explicitly enabled
        if (prefs.autoAnswerEnabled) {
            return if (prefs.aiCallAssistantEnabled) {
                CallScreeningDecision(
                    action = ScreeningDecisionAction.AUTO_ANSWER_WITH_AI_DISCLOSURE,
                    reason = "User enabled Auto-Answer + AI Call Assistant.",
                    isTrusted = isTrusted,
                    isBlocked = false,
                    isEmergencyOverride = false
                )
            } else {
                CallScreeningDecision(
                    action = ScreeningDecisionAction.AUTO_ANSWER_STANDARD,
                    reason = "User enabled Auto-Answer.",
                    isTrusted = isTrusted,
                    isBlocked = false,
                    isEmergencyOverride = false
                )
            }
        }

        return CallScreeningDecision(
            action = ScreeningDecisionAction.ALLOW_RING,
            reason = if (isTrusted) "Trusted caller" else "Standard incoming call",
            isTrusted = isTrusted,
            isBlocked = false,
            isEmergencyOverride = false
        )
    }

    fun buildAiDisclosureGreeting(callerLabel: String): String {
        val callerPrefix = if (callerLabel.isNotBlank() && callerLabel != "Unknown Caller") "Hello $callerLabel, " else "Hello, "
        return "${callerPrefix}this is Mahi, the owner's AI assistant calling assistant — I am an AI and not the owner personally. " +
            "May I ask why you are calling today? Audio is not secretly recorded, and you can ask for manual takeover anytime."
    }

    data class AiCallerEvaluation(
        val replyText: String,
        val isUrgent: Boolean,
        val shouldSuggestTakeover: Boolean,
        val shouldDisconnectSpam: Boolean = false
    )

    fun evaluateCallerMessageSafetyAndUrgency(
        callerMessage: String,
        prefs: CallAutomationPrefs
    ): AiCallerEvaluation {
        val lower = callerMessage.lowercase()

        // Privacy & authorization hard guardrails (never disclose private data or authorize commitments)
        val sensitivePatterns = listOf(
            "otp", "password", "passcode", "pin", "bank", "account number",
            "credit card", "debit card", "cvv", "payment", "transfer money",
            "contract", "authorize", "secret", "address"
        )
        if (sensitivePatterns.any { lower.contains(it) }) {
            return AiCallerEvaluation(
                replyText = "I am the AI assistant and cannot share passwords, OTPs, financial details, private information, or authorize agreements. Let me alert the owner so they can take over manually.",
                isUrgent = true,
                shouldSuggestTakeover = true
            )
        }

        // Emergency or explicit human takeover request
        val humanRequest = lower.contains("owner") || lower.contains("human") ||
            lower.contains("person") || lower.contains("connect me") || lower.contains("baat karni")
        val urgentHit = prefs.urgencyKeywords.any { kw -> kw.isNotBlank() && lower.contains(kw.lowercase()) }
        if (urgentHit || humanRequest) {
            return AiCallerEvaluation(
                replyText = "Understood — I am marking this call as important and notifying the owner immediately to take over.",
                isUrgent = true,
                shouldSuggestTakeover = true
            )
        }

        // Spam disconnect rule (only when explicitly matching telemarketing spam phrases & unknown screening)
        val obviousSpam = (lower.contains("lottery winner") || lower.contains("claim free prize") ||
            lower.contains("verify your bank otp")) && prefs.unknownCallerPolicy == UnknownCallerPolicy.REJECT_UNVERIFIED_SPAM
        if (obviousSpam) {
            return AiCallerEvaluation(
                replyText = "The owner does not accept unsolicited promotional calls. Ending this call per caller screening rules.",
                isUrgent = false,
                shouldSuggestTakeover = false,
                shouldDisconnectSpam = true
            )
        }

        return AiCallerEvaluation(
            replyText = "Thank you for sharing \"$callerMessage\". Could you confirm your name and callback preference so I can notify the owner?",
            isUrgent = false,
            shouldSuggestTakeover = false
        )
    }

    fun notifyImportantCall(context: Context, title: String, body: String) {
        _importantCallAlert.value = "$title: $body"
        try {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        IMPORTANT_CALL_CHANNEL_ID,
                        "Mahi Important Call Alerts",
                        NotificationManager.IMPORTANCE_HIGH
                    )
                )
            }
            val openIntent = PendingIntent.getActivity(
                context,
                901,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.app.Notification.Builder(context, IMPORTANT_CALL_CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                android.app.Notification.Builder(context)
            }
            val notif = builder
                .setSmallIcon(android.R.drawable.stat_sys_phone_call)
                .setContentTitle(title)
                .setContentText(body)
                .setContentIntent(openIntent)
                .setAutoCancel(true)
                .build()
            nm.notify(9102, notif)
        } catch (_: Exception) {
        }
    }

    fun refreshCapabilities(context: Context): TelecomCapabilityStatus {
        val roleMgr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.getSystemService(RoleManager::class.java)
        } else null

        val telecomMgr = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
        val isDialer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && roleMgr != null) {
            runCatching { roleMgr.isRoleHeld(RoleManager.ROLE_DIALER) }.getOrDefault(false)
        } else {
            runCatching { telecomMgr?.defaultDialerPackage == context.packageName }.getOrDefault(false)
        }
        val isScreening = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && roleMgr != null) {
            runCatching { roleMgr.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) }.getOrDefault(false)
        } else false

        val hasPhoneState = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        val hasCallPhone = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val hasAnswer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED
        } else true
        val hasContacts = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        val hasMic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val directCallAudio = ContextCompat.checkSelfPermission(context, CAPTURE_AUDIO_OUTPUT_PERMISSION) == PackageManager.PERMISSION_GRANTED

        val status = TelecomCapabilityStatus(
            isDefaultDialer = isDialer,
            isCallScreeningApp = isScreening,
            hasPhoneStatePermission = hasPhoneState,
            hasCallPhonePermission = hasCallPhone,
            hasAnswerCallsPermission = hasAnswer,
            hasContactsPermission = hasContacts,
            hasMicPermission = hasMic,
            hasDirectCellularAudioAccess = directCallAudio
        )
        _capabilities.value = status
        return status
    }

    @SuppressLint("Range")
    fun lookupContactName(context: Context, phoneNumber: String): ResolvedContact? {
        if (phoneNumber.isBlank()) return null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber))
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val name = cursor.getString(0)?.trim().orEmpty()
                    if (name.isNotBlank()) ResolvedContact(name, phoneNumber) else null
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    @SuppressLint("Range")
    fun searchContactsByName(context: Context, query: String): List<ResolvedContact> {
        val clean = query.trim().lowercase()
        if (clean.isEmpty()) return emptyList()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }
        return try {
            val out = mutableListOf<ResolvedContact>()
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$clean%"),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )?.use { cursor ->
                while (cursor.moveToNext() && out.size < 10) {
                    val name = cursor.getString(0)?.trim().orEmpty()
                    val num = cursor.getString(1)?.trim().orEmpty()
                    if (name.isNotBlank() && num.isNotBlank()) {
                        out.add(ResolvedContact(name, num))
                    }
                }
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }
}
