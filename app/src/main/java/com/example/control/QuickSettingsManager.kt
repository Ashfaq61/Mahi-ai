package com.example.control

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.example.automation.AutomationOutcome
import com.example.automation.MahiAccessibilityService

/**
 * Handles Quick Settings panel expansion, Notification Shade expansion, and direct Android system
 * settings pages (Android 10+ prevents third-party apps from silently toggling Wi-Fi/Mobile Data).
 */
class QuickSettingsManager(private val context: Context) {

    fun openQuickSettingsShade(): AutomationOutcome {
        val ok = MahiAccessibilityService.openQuickSettings()
        return if (ok) {
            AutomationOutcome(true, "Expanded Quick Settings shade", "GLOBAL_ACTION_QUICK_SETTINGS")
        } else {
            openInternetOrWirelessPanel()
        }
    }

    fun openNotificationShade(): AutomationOutcome {
        val ok = MahiAccessibilityService.openNotificationShade()
        return if (ok) {
            AutomationOutcome(true, "Opened Notification shade", "GLOBAL_ACTION_NOTIFICATIONS")
        } else {
            AutomationOutcome(false, "Enable Mahi Accessibility Service to swipe down the Notification shade by voice.")
        }
    }

    fun openInternetOrWirelessPanel(): AutomationOutcome {
        val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Settings.Panel.ACTION_INTERNET_CONNECTIVITY
        } else {
            Settings.ACTION_WIFI_SETTINGS
        }
        return launchSettingsIntent(action, "Internet & Wi-Fi panel")
    }

    fun openBluetoothSettings(): AutomationOutcome =
        launchSettingsIntent(Settings.ACTION_BLUETOOTH_SETTINGS, "Bluetooth settings")

    fun openDisplaySettings(): AutomationOutcome =
        launchSettingsIntent(Settings.ACTION_DISPLAY_SETTINGS, "Display & Brightness settings")

    fun openSoundSettings(): AutomationOutcome =
        launchSettingsIntent(Settings.ACTION_SOUND_SETTINGS, "Sound & Volume settings")

    fun openVolumePanel(): AutomationOutcome {
        val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Settings.Panel.ACTION_VOLUME
        } else {
            Settings.ACTION_SOUND_SETTINGS
        }
        return launchSettingsIntent(action, "System Volume panel")
    }

    fun openBatteryOptimizationSettings(): AutomationOutcome =
        launchSettingsIntent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, "Battery Optimization settings")

    private fun launchSettingsIntent(action: String, label: String): AutomationOutcome {
        return try {
            context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            AutomationOutcome(true, "Opened $label", "Dispatched $action")
        } catch (e: Exception) {
            AutomationOutcome(false, e.localizedMessage ?: "Could not open $label")
        }
    }
}
