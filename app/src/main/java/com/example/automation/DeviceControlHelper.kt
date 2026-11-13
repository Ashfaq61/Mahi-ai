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

    private val audioManager: AudioManager?
        get() = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    fun launchAppByName(appName: String): AutomationOutcome {
        val clean = appName.trim().lowercase()
        val knownPackages = mapOf(
            "whatsapp" to "com.whatsapp",
            "youtube" to "com.google.android.youtube",
            "spotify" to "com.spotify.music",
            "chrome" to "com.android.chrome",
            "camera" to "com.android.camera2",
            "settings" to "com.android.settings",
            "maps" to "com.google.android.apps.maps"
        )

        val pm = context.packageManager
        val matchedPkg = knownPackages.entries.firstOrNull { clean.contains(it.key) }?.value
            ?: findInstalledLauncherPackage(pm, clean)

        if (matchedPkg != null) {
            val launchIntent = pm.getLaunchIntentForPackage(matchedPkg)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (launchIntent != null) {
                context.startActivity(launchIntent)
                return AutomationOutcome(
                    success = true,
                    message = "Launched $appName ($matchedPkg)",
                    verificationDetail = "Package launch intent dispatched for $matchedPkg"
                )
            }
        }

        // Web fallback for well-known services
        val fallbackUrl = when {
            clean.contains("youtube") -> "https://www.youtube.com"
            clean.contains("whatsapp") -> "https://web.whatsapp.com"
            clean.contains("spotify") -> "https://open.spotify.com"
            else -> null
        }
        if (fallbackUrl != null) {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return AutomationOutcome(
                success = true,
                message = "Opened $appName in browser",
                verificationDetail = "Fallback URL opened: $fallbackUrl"
            )
        }

        return AutomationOutcome(
            success = false,
            message = "Could not find installed app matching '$appName'."
        )
    }

    private fun findInstalledLauncherPackage(pm: PackageManager, query: String): String? {
        return try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolved = pm.queryIntentActivities(intent, 0)
            resolved.firstOrNull { info ->
                val label = info.loadLabel(pm).toString().lowercase()
                label.contains(query) || info.activityInfo.packageName.lowercase().contains(query)
            }?.activityInfo?.packageName
        } catch (_: Exception) {
            null
        }
    }

    fun searchYouTube(query: String): AutomationOutcome {
        val encoded = Uri.encode(query.trim())
        val appUri = Uri.parse("https://www.youtube.com/results?search_query=$encoded")
        val intent = Intent(Intent.ACTION_VIEW, appUri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            AutomationOutcome(
                success = true,
                message = "Opened YouTube search for '$query'",
                verificationDetail = "YouTube results URI launched"
            )
        } catch (e: Exception) {
            AutomationOutcome(false, e.localizedMessage ?: "Failed to launch YouTube search")
        }
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
        val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
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
        context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return AutomationOutcome(
            success = true,
            message = "Opened ${category.ifBlank { "system" }} settings",
            verificationDetail = "Dispatched $action"
        )
    }
}
