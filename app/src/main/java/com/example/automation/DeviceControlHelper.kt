package com.example.automation

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings

data class KnownAppMapping(
    val displayName: String,
    val aliases: List<String>,
    val packageNames: List<String>
)

object AppLauncherManager {
    val knownApps = listOf(
        KnownAppMapping("WhatsApp", listOf("whatsapp", "wa", "whats app", "واٹس ایپ"), listOf("com.whatsapp", "com.whatsapp.w4b")),
        KnownAppMapping("Facebook", listOf("facebook", "fb", "فیس بک"), listOf("com.facebook.katana", "com.facebook.lite")),
        KnownAppMapping("YouTube", listOf("youtube", "yt", "یوٹیوب"), listOf("com.google.android.youtube")),
        KnownAppMapping("Instagram", listOf("instagram", "insta", "ig", "انسٹاگرام"), listOf("com.instagram.android")),
        KnownAppMapping("TikTok", listOf("tiktok", "tik tok", "ٹک ٹاک"), listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill")),
        KnownAppMapping("Chrome", listOf("chrome", "browser", "google chrome"), listOf("com.android.chrome")),
        KnownAppMapping("Google", listOf("google"), listOf("com.google.android.googlequicksearchbox")),
        KnownAppMapping("Maps", listOf("maps", "google maps", "map", "نقشہ"), listOf("com.google.android.apps.maps")),
        KnownAppMapping("Camera", listOf("camera", "cam", "کیمرہ"), listOf("com.motorola.camera3", "com.android.camera2", "com.google.android.GoogleCamera")),
        KnownAppMapping("Gallery", listOf("gallery", "photos", "google photos", "گیلری"), listOf("com.google.android.apps.photos", "com.android.gallery3d")),
        KnownAppMapping("Phone", listOf("phone", "dialer", "call"), listOf("com.google.android.dialer", "com.android.dialer")),
        KnownAppMapping("Messages", listOf("messages", "sms"), listOf("com.google.android.apps.messaging", "com.android.mms")),
        KnownAppMapping("Settings", listOf("settings", "setting", "سیٹنگز"), listOf("com.android.settings")),
        KnownAppMapping("Clock", listOf("clock", "alarm", "timer"), listOf("com.google.android.deskclock", "com.android.deskclock")),
        KnownAppMapping("Calculator", listOf("calculator", "calc"), listOf("com.google.android.calculator", "com.android.calculator2")),
        KnownAppMapping("Gmail", listOf("gmail", "email", "mail"), listOf("com.google.android.gm")),
        KnownAppMapping("Play Store", listOf("play store", "playstore"), listOf("com.android.vending")),
        KnownAppMapping("Telegram", listOf("telegram"), listOf("org.telegram.messenger")),
        KnownAppMapping("Spotify", listOf("spotify"), listOf("com.spotify.music"))
    )

    fun normalizeAppQuery(raw: String): String {
        return raw.lowercase()
            .replace(Regex("(?i)\\b(open|launch|start|kholo|chalao|karo|app|application|please)\\b"), " ")
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun matchKnownApp(query: String): KnownAppMapping? {
        val norm = normalizeAppQuery(query)
        if (norm.isBlank()) return null
        return knownApps.firstOrNull { app ->
            app.aliases.any { alias -> norm == alias || norm.contains(alias) }
        }
    }

    fun getInstalledLauncherApps(context: Context): List<Pair<String, String>> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
        return resolved.mapNotNull { info ->
            val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
            val label = info.loadLabel(pm)?.toString().orEmpty()
            if (label.isBlank()) null else label to pkg
        }.distinctBy { it.second }
    }
}

class DeviceControlHelper(private val context: Context) {

    fun launchAppByName(rawName: String): AutomationOutcome {
        val query = AppLauncherManager.normalizeAppQuery(rawName)
        if (query.isBlank()) return AutomationOutcome(false, "Specify which app to open.")
        val pm = context.packageManager

        val mapping = AppLauncherManager.matchKnownApp(query)
        if (mapping != null) {
            for (pkg in mapping.packageNames) {
                val launch = pm.getLaunchIntentForPackage(pkg)
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    return try {
                        context.startActivity(launch)
                        AutomationOutcome(true, "Opened ${mapping.displayName}", "Package: $pkg", packageName = pkg)
                    } catch (e: Exception) {
                        AutomationOutcome(false, "Failed to open ${mapping.displayName}: ${e.localizedMessage}")
                    }
                }
            }
        }

        val installed = AppLauncherManager.getInstalledLauncherApps(context)
        val bestMatch = installed.firstOrNull { (label, pkg) ->
            label.equals(query, ignoreCase = true) ||
                label.contains(query, ignoreCase = true) ||
                pkg.contains(query.replace(" ", ""), ignoreCase = true)
        }
        if (bestMatch != null) {
            val (label, pkg) = bestMatch
            val launch = pm.getLaunchIntentForPackage(pkg)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                return try {
                    context.startActivity(launch)
                    AutomationOutcome(true, "Opened $label", "Package: $pkg", packageName = pkg)
                } catch (e: Exception) {
                    AutomationOutcome(false, "Unable to launch $label: ${e.localizedMessage}")
                }
            }
        }
        return AutomationOutcome(false, "App '$rawName' is not installed on this device.")
    }

    fun openSettingsScreen(section: String): AutomationOutcome {
        val action = when (section.lowercase()) {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "display", "brightness" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound", "volume" -> Settings.ACTION_SOUND_SETTINGS
            "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            "notification_listener", "notifications" -> Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
            "write_settings" -> Settings.ACTION_MANAGE_WRITE_SETTINGS
            "overlay" -> Settings.ACTION_MANAGE_OVERLAY_PERMISSION
            "app_details" -> Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        return try {
            val intent = Intent(action).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (action == Settings.ACTION_MANAGE_WRITE_SETTINGS ||
                    action == Settings.ACTION_MANAGE_OVERLAY_PERMISSION ||
                    action == Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                ) {
                    data = Uri.parse("package:${context.packageName}")
                }
            }
            context.startActivity(intent)
            AutomationOutcome(true, "Opened $section settings")
        } catch (e: Exception) {
            AutomationOutcome(false, "Unable to open $section settings: ${e.localizedMessage}")
        }
    }

    fun canModifySystemSettings(): Boolean =
        Settings.System.canWrite(context)

    fun setBrightnessPercent(percent: Int): AutomationOutcome {
        val clamped = percent.coerceIn(5, 100)
        if (!Settings.System.canWrite(context)) {
            return AutomationOutcome(
                false,
                "Android requires 'Modify System Settings' permission to change brightness.",
                "Permission WRITE_SETTINGS not granted",
                SuggestedRetryStrategy.REQUIRE_USER_CONFIRMATION
            )
        }
        return try {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            val level = ((clamped / 100f) * 255f).toInt().coerceIn(10, 255)
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, level)
            AutomationOutcome(true, "Screen brightness set to $clamped%", "System brightness=$level/255")
        } catch (e: Exception) {
            AutomationOutcome(false, "Failed setting brightness: ${e.localizedMessage}")
        }
    }

    fun openUrl(url: String): AutomationOutcome {
        val safeUrl = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(safeUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            AutomationOutcome(true, "Opened $safeUrl")
        } catch (e: Exception) {
            AutomationOutcome(false, "Could not open URL: ${e.localizedMessage}")
        }
    }
}
