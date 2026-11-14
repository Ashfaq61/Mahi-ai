package com.example.control

import android.content.Context
import com.example.automation.AutomationOutcome
import com.example.automation.DeviceControlHelper
import com.example.automation.GestureSpeed
import com.example.automation.MahiAccessibilityService
import com.example.automation.VoiceGestureIntent
import kotlinx.coroutines.delay

/**
 * Central orchestrator for all Android system controls & chained multi-step voice commands
 * (e.g., "Settings kholo aur neeche scroll karo", "Open Wi-Fi and set volume 50").
 */
class SmartphoneControlManager(
    context: Context,
    val deviceControlHelper: DeviceControlHelper = DeviceControlHelper(context),
    val quickSettingsManager: QuickSettingsManager = QuickSettingsManager(context),
    val volumeManager: VolumeManager = VolumeManager(context),
    val batteryManager: BatteryInformationManager = BatteryInformationManager(context),
    val storageManager: StorageInformationManager = StorageInformationManager(),
    val networkManager: NetworkInformationManager = NetworkInformationManager(context),
    val deviceInfoManager: DeviceInformationManager = DeviceInformationManager(context)
) {
    companion object {
        private val CHAIN_SPLIT_REGEX = Regex("(?i)\\s+(?:and then|aur phir|phir|then|aur|and)\\s+")
    }

    fun splitChainedCommands(rawCommand: String): List<String> {
        val clean = rawCommand.trim()
        if (clean.isBlank()) return emptyList()
        val parts = clean.split(CHAIN_SPLIT_REGEX).map { it.trim() }.filter { it.isNotBlank() }
        return if (parts.size > 1) parts else listOf(clean)
    }

    suspend fun executeChainedCommands(
        segments: List<String>,
        singleStepExecutor: suspend (String) -> AutomationOutcome
    ): AutomationOutcome {
        if (segments.isEmpty()) return AutomationOutcome(false, "No control command recognized.")
        val results = mutableListOf<AutomationOutcome>()
        for ((idx, seg) in segments.withIndex()) {
            val outcome = singleStepExecutor(seg)
            results.add(outcome)
            if (idx < segments.lastIndex) {
                delay(700L) // Give Android window transition time before scrolling/tapping the newly opened screen
            }
        }
        val allOk = results.all { it.success }
        val summary = results.joinToString(" → ") { it.message }
        return AutomationOutcome(
            success = allOk || results.any { it.success },
            message = summary,
            verificationDetail = "${results.count { it.success }}/${results.size} steps executed"
        )
    }

    fun handleDirectControlPhrase(phrase: String): AutomationOutcome? {
        val lower = phrase.trim().lowercase()
        val number = Regex("\\d+").find(lower)?.value?.toIntOrNull()

        return when {
            lower.contains("battery") -> {
                val info = batteryManager.getBatteryInfo()
                AutomationOutcome(true, info.statusSummary, "BatteryManager verified")
            }
            lower.contains("storage") || lower.contains("space") || lower.contains("memory kitni") -> {
                val info = storageManager.getStorageInfo()
                AutomationOutcome(true, info.summary, "StatFs verified")
            }
            lower.contains("wifi") || lower.contains("internet") || lower.contains("network") -> {
                if (lower.contains("open") || lower.contains("kholo") || lower.contains("settings")) {
                    quickSettingsManager.openInternetOrWirelessPanel()
                } else {
                    val info = networkManager.getNetworkInfo()
                    AutomationOutcome(true, info.summary, "ConnectivityManager verified")
                }
            }
            lower.contains("phone model") || lower.contains("ka model") || lower.contains("device info") || lower.contains("android version") -> {
                val info = deviceInfoManager.getDeviceInfo()
                AutomationOutcome(true, info.summary, "Build/RAM verified")
            }
            lower.contains("quick settings") || lower.contains("control center") -> {
                quickSettingsManager.openQuickSettingsShade()
            }
            lower.contains("notification panel") || lower.contains("notification shade") || lower.contains("notifications kholo") -> {
                quickSettingsManager.openNotificationShade()
            }
            lower.contains("mute") && !lower.contains("unmute") -> {
                volumeManager.muteMedia(true)
            }
            lower.contains("unmute") -> {
                volumeManager.muteMedia(false)
            }
            lower.contains("ringtone") && number != null -> {
                volumeManager.setStreamPercent(VolumeStreamType.RINGTONE, number)
            }
            lower.contains("alarm volume") && number != null -> {
                volumeManager.setStreamPercent(VolumeStreamType.ALARM, number)
            }
            lower.contains("notification volume") && number != null -> {
                volumeManager.setStreamPercent(VolumeStreamType.NOTIFICATION, number)
            }
            lower.contains("call volume") && number != null -> {
                volumeManager.setStreamPercent(VolumeStreamType.CALL, number)
            }
            lower.contains("volume") && number != null -> {
                volumeManager.setStreamPercent(VolumeStreamType.MEDIA, number)
            }
            lower.contains("brightness") && number != null -> {
                deviceControlHelper.setBrightnessPercent(number)
            }
            lower.contains("neeche scroll") || lower.contains("scroll down") -> {
                MahiAccessibilityService.executeGestureIntent(
                    VoiceGestureIntent("SWIPE_UP_SCROLL", GestureSpeed.NORMAL)
                )
            }
            lower.contains("upar scroll") || lower.contains("scroll up") -> {
                MahiAccessibilityService.executeGestureIntent(
                    VoiceGestureIntent("SWIPE_DOWN_SCROLL", GestureSpeed.NORMAL)
                )
            }
            else -> null
        }
    }
}
