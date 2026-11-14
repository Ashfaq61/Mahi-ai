package com.example.automation

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.provider.Settings
import kotlin.math.roundToInt

data class AutomationOutcome(
    val success: Boolean,
    val message: String,
    val verificationDetail: String = ""
)

class DeviceControlHelper(private val context: Context) {

    val appLauncher = AppLauncherManager(context)

    private val audioManager: AudioManager?
        get() = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    fun launchAppByName(appName: String): AutomationOutcome {
        val result = appLauncher.launchApp(appName, openStoreOrWebIfMissing = true)
        return AutomationOutcome(
            success = result.success,
            message = result.message,
            verificationDetail = result.verificationDetail
        )
    }

    fun searchYouTube(query: String): AutomationOutcome {
        val result = appLauncher.openYouTubeSearch(query)
        return AutomationOutcome(
            success = result.success,
            message = result.message,
            verificationDetail = result.verificationDetail
        )
    }

    fun openGoogleApp(): AutomationOutcome {
        val result = appLauncher.openGoogleApp()
        return AutomationOutcome(
            success = result.success,
            message = result.message,
            verificationDetail = result.verificationDetail
        )
    }

    fun googleSearch(query: String): AutomationOutcome {
        val result = appLauncher.openGoogleSearch(query)
        return AutomationOutcome(
            success = result.success,
            message = result.message,
            verificationDetail = result.verificationDetail
        )
    }

    fun openWebsite(url: String): AutomationOutcome {
        val result = appLauncher.openWebsite(url)
        return AutomationOutcome(
            success = result.success,
            message = result.message,
            verificationDetail = result.verificationDetail
        )
    }

    fun getMediaVolumePercent(): Int {
        val am = audioManager ?: return 50
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        return ((cur.toFloat() / max.toFloat()) * 100f).roundToInt().coerceIn(0, 100)
    }

    fun setMediaVolumePercent(percent: Int): AutomationOutcome {
        val am = audioManager ?: return AutomationOutcome(false, "AudioManager unavailable")
        val clamped = percent.coerceIn(0, 100)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val targetIndex = ((clamped / 100f) * max).roundToInt().coerceIn(0, max)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, targetIndex, AudioManager.FLAG_SHOW_UI)
        val actual = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        return AutomationOutcome(
            success = true,
            message = "Media volume set to $clamped%",
            verificationDetail = "Verified stream index $actual/$max"
        )
    }

    fun canWriteSystemSettings(): Boolean {
        return try {
            Settings.System.canWrite(context)
        } catch (_: Exception) {
            false
        }
    }

    fun openWriteSettingsPermission() {
        try {
            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
        }
    }

    fun getBrightnessPercent(): Int {
        return try {
            val cur = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
            ((cur / 255f) * 100f).roundToInt().coerceIn(0, 100)
        } catch (_: Exception) {
            50
        }
    }

    fun setBrightnessPercent(percent: Int): AutomationOutcome {
        if (!canWriteSystemSettings()) {
            openWriteSettingsPermission()
            return AutomationOutcome(
                success = false,
                message = "Modify System Settings permission required for brightness. Opened system settings prompt."
            )
        }
        val clamped = percent.coerceIn(5, 100)
        val value = ((clamped / 100f) * 255f).roundToInt().coerceIn(10, 255)
        Settings.System.putInt(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        )
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, value)
        val verified = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, value)
        return AutomationOutcome(
            success = true,
            message = "Screen brightness set to $clamped%",
            verificationDetail = "Verified system brightness=$verified/255"
        )
    }

    fun openSettingsScreen(category: String): AutomationOutcome {
        val action = when (category.lowercase().trim()) {
            "wifi", "wi-fi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "display", "brightness" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound", "volume" -> Settings.ACTION_SOUND_SETTINGS
            "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            "notifications" -> Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        return try {
            context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            AutomationOutcome(
                success = true,
                message = "Opened ${category.ifBlank { "system" }} settings",
                verificationDetail = "Dispatched $action"
            )
        } catch (e: Exception) {
            AutomationOutcome(
                success = false,
                message = e.localizedMessage ?: "Settings screen unavailable on this device"
            )
        }
    }
}
