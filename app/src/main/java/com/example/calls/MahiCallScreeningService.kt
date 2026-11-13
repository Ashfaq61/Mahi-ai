package com.example.calls

import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import android.telecom.Connection
import androidx.annotation.RequiresApi

/**
 * Android 10+ (API 29+) CallScreeningService implementation.
 * Evaluates incoming unknown & blocked callers using carrier STIR/SHAKEN verification
 * and user-configured rules (defaulting safely to ALLOW_RING / manual-only).
 */
@RequiresApi(Build.VERSION_CODES.Q)
class MahiCallScreeningService : CallScreeningService() {

    override fun onScreenCall(callDetails: Call.Details) {
        if (callDetails.callDirection != Call.Details.DIRECTION_INCOMING) {
            respondToCall(callDetails, CallResponse.Builder().build())
            return
        }

        CallAutomationRepository.init(applicationContext)
        val number = callDetails.handle?.schemeSpecificPart.orEmpty()
        val resolved = CallAutomationRepository.lookupContactName(applicationContext, number)
        val callerName = resolved?.displayName ?: callDetails.callerDisplayName?.toString().orEmpty()
        val isSavedContact = resolved != null

        val verificationStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            callDetails.callerNumberVerificationStatus
        } else {
            Connection.VERIFICATION_STATUS_NOT_VERIFIED
        }
        val verificationFailed = verificationStatus == Connection.VERIFICATION_STATUS_FAILED
        val recentAttempts = CallAutomationRepository.registerIncomingAttemptTimestamp(number)
        val prefs = CallAutomationRepository.prefs.value

        val decision = CallAutomationRepository.evaluateIncomingCallPolicy(
            phoneNumber = number,
            isSavedContact = isSavedContact,
            verificationFailed = verificationFailed,
            prefs = prefs,
            recentCallsInWindow = recentAttempts
        )

        val responseBuilder = CallResponse.Builder()
        when (decision.action) {
            ScreeningDecisionAction.REJECT_CALL -> {
                responseBuilder
                    .setDisallowCall(true)
                    .setRejectCall(true)
                    .setSkipNotification(false)
            }
            ScreeningDecisionAction.SILENCE_CALL -> {
                responseBuilder
                    .setDisallowCall(false)
                    .setSilenceCall(true)
            }
            ScreeningDecisionAction.EMERGENCY_OVERRIDE_RING -> {
                CallAutomationRepository.notifyImportantCall(
                    applicationContext,
                    "Emergency / Repeat Caller: ${callerName.ifBlank { number }}",
                    decision.reason
                )
            }
            else -> Unit
        }

        val verLabel = when (verificationStatus) {
            Connection.VERIFICATION_STATUS_PASSED -> "STIR_PASSED"
            Connection.VERIFICATION_STATUS_FAILED -> "STIR_FAILED"
            else -> "NOT_VERIFIED"
        }

        CallAutomationRepository.recordCallEvent(
            applicationContext,
            CallAutomationRecord(
                phoneNumber = number.ifBlank { "Private Number" },
                callerName = callerName.ifBlank { if (isSavedContact) "Saved Contact" else "Unknown Caller" },
                direction = "SCREENED",
                actionTaken = "${decision.action.name} ($verLabel): ${decision.reason}",
                wasUrgent = decision.isEmergencyOverride
            )
        )

        respondToCall(callDetails, responseBuilder.build())
    }
}
