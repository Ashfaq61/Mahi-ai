package com.example.calls

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import androidx.core.content.ContextCompat
import com.example.audio.MahiSpeechSynthesizer

/**
 * Native Android Telecom InCallService:
 * Active when the user grants Mahi AI the Default Dialer role (RoleManager.ROLE_DIALER).
 * Manages incoming, outgoing, missed, answered, rejected, muted, and speakerphone calls,
 * plus disclosed AI Call Assistant handoff with an always-available Manual Takeover control.
 */
class MahiInCallService : InCallService() {

    companion object {
        @Volatile
        private var instance: MahiInCallService? = null

        @Volatile
        private var currentCall: Call? = null

        fun isBound(): Boolean = instance != null

        fun answerIncomingCall(useSpeakerphoneBridge: Boolean = false): Boolean {
            val call = currentCall ?: return false
            val ok = try {
                call.answer(VideoProfile.STATE_AUDIO_ONLY)
                true
            } catch (_: Exception) {
                false
            }
            if (ok && useSpeakerphoneBridge) {
                setSpeakerphoneEnabled(true)
            }
            return ok
        }

        fun rejectOrEndCall(): Boolean {
            val svc = instance
            val call = currentCall
            if (call != null) {
                return try {
                    if (call.state == Call.STATE_RINGING) {
                        call.reject(false, null)
                    } else {
                        call.disconnect()
                    }
                    true
                } catch (_: Exception) {
                    false
                }
            }
            // Fallback for Android 9+ TelecomManager.endCall() if ANSWER_PHONE_CALLS is granted
            if (svc != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val tm = svc.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                if (ContextCompat.checkSelfPermission(svc, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                    @Suppress("DEPRECATION")
                    return runCatching { tm?.endCall() ?: false }.getOrDefault(false)
                }
            }
            return false
        }

        fun setCallMuted(muted: Boolean): Boolean {
            val svc = instance ?: return false
            return try {
                svc.setMuted(muted)
                CallAutomationRepository.updateActiveCall { it.copy(isMuted = muted) }
                true
            } catch (_: Exception) {
                false
            }
        }

        fun setSpeakerphoneEnabled(speaker: Boolean): Boolean {
            val svc = instance ?: return false
            return try {
                val route = if (speaker) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_EARPIECE
                svc.setAudioRoute(route)
                CallAutomationRepository.updateActiveCall { it.copy(isSpeakerphone = speaker) }
                true
            } catch (_: Exception) {
                false
            }
        }

        fun placeOutgoingCall(context: Context, targetRaw: String): Pair<Boolean, String> {
            CallAutomationRepository.init(context)
            val trimmed = targetRaw.trim()
            if (trimmed.isEmpty()) return false to "Provide a phone number or contact name."

            // Resolve contact name if user spoke or typed a name rather than digits
            val isPhoneDigits = trimmed.count { it.isDigit() } >= 3 && trimmed.all { it.isDigit() || it in "+-() " }
            val resolvedContact = if (!isPhoneDigits) {
                CallAutomationRepository.searchContactsByName(context, trimmed).firstOrNull()
            } else null

            val dialNumber = resolvedContact?.phoneNumber ?: trimmed
            val displayLabel = resolvedContact?.displayName ?: dialNumber
            val uri = Uri.fromParts("tel", dialNumber, null)

            val telecomMgr = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            val hasCallPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
            val caps = CallAutomationRepository.refreshCapabilities(context)

            if (hasCallPerm && telecomMgr != null) {
                return try {
                    telecomMgr.placeCall(uri, Bundle())
                    CallAutomationRepository.recordCallEvent(
                        context,
                        CallAutomationRecord(
                            phoneNumber = dialNumber,
                            callerName = displayLabel,
                            direction = "OUTGOING",
                            actionTaken = if (caps.isDefaultDialer) "Placed via TelecomManager" else "Placed outgoing call"
                        )
                    )
                    true to "Calling $displayLabel..."
                } catch (e: SecurityException) {
                    launchDialerFallback(context, dialNumber, displayLabel)
                } catch (e: Exception) {
                    launchDialerFallback(context, dialNumber, displayLabel)
                }
            }

            return launchDialerFallback(context, dialNumber, displayLabel)
        }

        private fun launchDialerFallback(context: Context, number: String, label: String): Pair<Boolean, String> {
            return try {
                val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number)}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(dialIntent)
                CallAutomationRepository.recordCallEvent(
                    context,
                    CallAutomationRecord(
                        phoneNumber = number,
                        callerName = label,
                        direction = "OUTGOING",
                        actionTaken = "Opened Phone Dialer (grant CALL_PHONE or Default Dialer for instant dial)"
                    )
                )
                true to "Opened dialer for $label ($number)"
            } catch (e: Exception) {
                false to (e.localizedMessage ?: "Failed to initiate call")
            }
        }
    }

    private var ttsHelper: MahiSpeechSynthesizer? = null

    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            syncCallState(call)
            if (state == Call.STATE_DISCONNECTED) {
                finalizeDisconnectedCall(call)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        CallAutomationRepository.init(applicationContext)
        ttsHelper = MahiSpeechSynthesizer(applicationContext).apply { initialize() }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
        currentCall?.unregisterCallback(callCallback)
        currentCall = null
        ttsHelper?.release()
        ttsHelper = null
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        instance = this
        currentCall?.unregisterCallback(callCallback)
        currentCall = call
        call.registerCallback(callCallback)
        syncCallState(call)

        val details = call.details
        if (details?.callDirection == Call.Details.DIRECTION_INCOMING) {
            val number = details.handle?.schemeSpecificPart.orEmpty()
            val contact = CallAutomationRepository.lookupContactName(applicationContext, number)
            val callerName = contact?.displayName ?: details.callerDisplayName?.toString().orEmpty()
            val prefs = CallAutomationRepository.prefs.value
            val recent = CallAutomationRepository.registerIncomingAttemptTimestamp(number)
            val decision = CallAutomationRepository.evaluateIncomingCallPolicy(
                phoneNumber = number,
                isSavedContact = contact != null,
                verificationFailed = false,
                prefs = prefs,
                recentCallsInWindow = recent
            )

            when (decision.action) {
                ScreeningDecisionAction.AUTO_ANSWER_STANDARD -> {
                    answerIncomingCall(useSpeakerphoneBridge = false)
                }
                ScreeningDecisionAction.AUTO_ANSWER_WITH_AI_DISCLOSURE -> {
                    if (prefs.speakerphoneBridgeConsent) {
                        answerIncomingCall(useSpeakerphoneBridge = true)
                        startDisclosedAiAssistantOnCall(callerName)
                    } else {
                        CallAutomationRepository.updateActiveCall {
                            it.copy(
                                aiPhase = AiCallAssistantPhase.AWAITING_USER_CONSENT_OR_OFFLINE,
                                aiDialogueLog = it.aiDialogueLog +
                                    "Waiting for user consent: Direct cellular audio is restricted by Android; tap 'Answer with AI (Speaker)' to bridge."
                            )
                        }
                    }
                }
                ScreeningDecisionAction.EMERGENCY_OVERRIDE_RING -> {
                    CallAutomationRepository.notifyImportantCall(
                        applicationContext,
                        "Urgent / Repeat Incoming Call",
                        callerName.ifBlank { number }
                    )
                }
                else -> Unit
            }
        }
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        call.unregisterCallback(callCallback)
        if (currentCall === call) {
            finalizeDisconnectedCall(call)
            currentCall = null
        }
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        super.onCallAudioStateChanged(audioState)
        if (audioState != null) {
            CallAutomationRepository.updateActiveCall {
                it.copy(
                    isMuted = audioState.isMuted,
                    isSpeakerphone = (audioState.route and CallAudioState.ROUTE_SPEAKER) != 0
                )
            }
        }
    }

    private fun syncCallState(call: Call) {
        val details = call.details
        val number = details?.handle?.schemeSpecificPart.orEmpty()
        val contact = CallAutomationRepository.lookupContactName(applicationContext, number)
        val callerName = contact?.displayName
            ?: details?.callerDisplayName?.toString().orEmpty().ifBlank { if (number.isBlank()) "Unknown Caller" else number }
        val prefs = CallAutomationRepository.prefs.value
        val isTrusted = prefs.trustedContacts.any { it.matchesNumber(number) }
        val isBlocked = prefs.blockedCallers.any { it.matchesNumber(number) }

        val mappedStatus = when (call.state) {
            Call.STATE_RINGING -> LiveCallStatus.RINGING_INCOMING
            Call.STATE_DIALING, Call.STATE_CONNECTING -> LiveCallStatus.DIALING_OUTGOING
            Call.STATE_ACTIVE -> LiveCallStatus.ACTIVE
            Call.STATE_HOLDING -> LiveCallStatus.HOLDING
            Call.STATE_DISCONNECTED, Call.STATE_DISCONNECTING -> LiveCallStatus.DISCONNECTED
            else -> LiveCallStatus.ACTIVE
        }

        val directionIncoming = details?.callDirection != Call.Details.DIRECTION_OUTGOING
        CallAutomationRepository.updateActiveCall { cur ->
            cur.copy(
                callId = "call_${details?.creationTimeMillis ?: System.currentTimeMillis()}",
                phoneNumber = number,
                callerDisplayName = callerName,
                isSavedContact = contact != null,
                isTrusted = isTrusted,
                isBlockedRuleMatch = isBlocked,
                isIncoming = directionIncoming,
                status = mappedStatus,
                startedAtMs = if (cur.startedAtMs == 0L) System.currentTimeMillis() else cur.startedAtMs
            )
        }
    }

    private fun startDisclosedAiAssistantOnCall(callerName: String) {
        val greeting = CallAutomationRepository.buildAiDisclosureGreeting(callerName)
        val voice = CallAutomationRepository.prefs.value.assistantVoice
        ttsHelper?.pitch = voice.pitch
        ttsHelper?.speechRate = voice.speed
        CallAutomationRepository.updateActiveCall {
            it.copy(
                aiPhase = AiCallAssistantPhase.DISCLOSING_AI_AND_ASKING_REASON,
                aiDialogueLog = it.aiDialogueLog + "Mahi (Disclosed AI): $greeting"
            )
        }
        ttsHelper?.speak(greeting)
    }

    private fun finalizeDisconnectedCall(call: Call) {
        val prev = CallAutomationRepository.activeCall.value
        val wasMissed = prev.isIncoming && prev.status == LiveCallStatus.RINGING_INCOMING &&
            call.details?.disconnectCause?.code == android.telecom.DisconnectCause.MISSED
        val dir = if (wasMissed) "MISSED" else if (prev.isIncoming) "INCOMING" else "OUTGOING"

        if (prev.phoneNumber.isNotBlank() || prev.callerDisplayName.isNotBlank()) {
            CallAutomationRepository.recordCallEvent(
                applicationContext,
                CallAutomationRecord(
                    phoneNumber = prev.phoneNumber.ifBlank { "Private Number" },
                    callerName = prev.callerDisplayName.ifBlank { "Caller" },
                    direction = dir,
                    actionTaken = if (wasMissed) "Missed incoming call" else "Call ended",
                    wasUrgent = prev.isUrgent,
                    aiHandled = prev.aiPhase != AiCallAssistantPhase.INACTIVE
                )
            )
        }
        CallAutomationRepository.updateActiveCall { ActiveCallState() }
    }
}
