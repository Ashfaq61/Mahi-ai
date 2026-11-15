package com.example.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives the Stop action from the Autopilot ongoing notification without risking
 * Android 12–15 BackgroundServiceStartNotAllowedException when Voice FGS is not running.
 */
class AutopilotStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == MahiVoiceForegroundService.ACTION_STOP_AUTOPILOT) {
            MahiVoiceForegroundService.clearAutopilotNotification(context)
            MahiVoiceForegroundService.onStopAutopilotRequested?.invoke()
        }
    }
}
