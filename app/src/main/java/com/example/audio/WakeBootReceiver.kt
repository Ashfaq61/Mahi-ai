package com.example.audio

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.example.preferences.MahiPreferencesStore

/**
 * Restarts Mahi AI's opt-in background Wake Up Detection service after device reboot or app update
 * ONLY when:
 * 1) the user explicitly turned Wake Up Detection ON,
 * 2) RECORD_AUDIO permission is granted, and
 * 3) the offline Vosk model is already downloaded.
 */
class WakeBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        val prefs = MahiPreferencesStore(context).prefsFlow.value
        val hasMic = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        val modelReady = WakeModelManager(context).isModelReady()

        if (prefs.wakeDetectionEnabled && hasMic && modelReady) {
            MahiVoiceForegroundService.startWakeDetection(context)
        }
    }
}
