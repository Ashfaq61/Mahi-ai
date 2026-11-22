package com.example.control

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import com.example.automation.AutomationOutcome
import com.example.automation.MahiAccessibilityService
import com.example.automation.SuggestedRetryStrategy

enum class VolumeStreamType(val displayName: String, val androidStream: Int) {
    MEDIA("Media", AudioManager.STREAM_MUSIC),
    RINGTONE("Ringtone", AudioManager.STREAM_RING),
    NOTIFICATION("Notification", AudioManager.STREAM_NOTIFICATION),
    ALARM("Alarm", AudioManager.STREAM_ALARM),
    CALL("In-Call", AudioManager.STREAM_VOICE_CALL)
}

data class AllVolumeLevels(
    val mediaPercent: Int = 50,
    val ringtonePercent: Int = 50,
    val notificationPercent: Int = 50,
    val alarmPercent: Int = 50,
    val callPercent: Int = 50
)

data class BatteryInfoSnapshot(
    val levelPercent: Int = 0,
    val isCharging: Boolean = false,
    val temperatureC: Float = 0f,
    val statusSummary: String = ""
)

data class StorageInfoSnapshot(
    val totalGb: Float = 0f,
    val freeGb: Float = 0f,
    val usedPercent: Int = 0,
    val summary: String = ""
)

data class NetworkInfoSnapshot(
    val isConnected: Boolean = false,
    val transportLabel: String = "Offline",
    val summary: String = ""
)

data class DeviceInfoSnapshot(
    val manufacturerModel: String = "${Build.MANUFACTURER} ${Build.MODEL}",
    val androidVersion: String = "Android ${Build.VERSION.RELEASE}",
    val sdkInt: Int = Build.VERSION.SDK_INT,
    val uptimeFormatted: String = "0m",
    val totalRamGb: Float = 0f,
    val summary: String = ""
)

class QuickSettingsManager(private val context: Context) {
    private fun startSettings(action: String, label: String): AutomationOutcome {
        return try {
            val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            AutomationOutcome(true, "Opened $label settings")
        } catch (e: Exception) {
            AutomationOutcome(false, "Unable to open $label settings: ${e.localizedMessage}")
        }
    }

    fun openInternetOrWirelessPanel(): AutomationOutcome {
        val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Settings.Panel.ACTION_INTERNET_CONNECTIVITY
        } else {
            Settings.ACTION_WIFI_SETTINGS
        }
        return startSettings(action, "Internet / Wi-Fi")
    }

    fun openBluetoothSettings(): AutomationOutcome = startSettings(Settings.ACTION_BLUETOOTH_SETTINGS, "Bluetooth")
    fun openMobileDataSettings(): AutomationOutcome = startSettings(Settings.ACTION_DATA_ROAMING_SETTINGS, "Mobile Data")
    fun openAirplaneModeSettings(): AutomationOutcome = startSettings(Settings.ACTION_AIRPLANE_MODE_SETTINGS, "Airplane Mode")
    fun openNfcSettings(): AutomationOutcome = startSettings(Settings.ACTION_NFC_SETTINGS, "NFC")
    fun openDndSettings(): AutomationOutcome = startSettings(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS, "Do Not Disturb")

    fun openQuickSettingsShade(): AutomationOutcome = MahiAccessibilityService.openQuickSettings()
    fun openNotificationShade(): AutomationOutcome = MahiAccessibilityService.openNotificationsShade()

    fun setTorch(enabled: Boolean): AutomationOutcome {
        val cm = context.getSystemService(CameraManager::class.java)
            ?: return AutomationOutcome(false, "CameraManager unavailable on this device.")
        return try {
            val camId = cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return AutomationOutcome(false, "No camera flash hardware detected.")
            cm.setTorchMode(camId, enabled)
            AutomationOutcome(true, if (enabled) "Flashlight ON" else "Flashlight OFF")
        } catch (e: Exception) {
            AutomationOutcome(false, "Flashlight control failed: ${e.localizedMessage}")
        }
    }
}

class VolumeManager(private val context: Context) {
    private val audioManager: AudioManager? = context.getSystemService(AudioManager::class.java)

    fun getStreamPercent(stream: VolumeStreamType): Int {
        val am = audioManager ?: return 50
        val max = am.getStreamMaxVolume(stream.androidStream).coerceAtLeast(1)
        val cur = am.getStreamVolume(stream.androidStream)
        return ((cur * 100f) / max).toInt().coerceIn(0, 100)
    }

    fun getAllVolumeLevels(): AllVolumeLevels = AllVolumeLevels(
        mediaPercent = getStreamPercent(VolumeStreamType.MEDIA),
        ringtonePercent = getStreamPercent(VolumeStreamType.RINGTONE),
        notificationPercent = getStreamPercent(VolumeStreamType.NOTIFICATION),
        alarmPercent = getStreamPercent(VolumeStreamType.ALARM),
        callPercent = getStreamPercent(VolumeStreamType.CALL)
    )

    fun setStreamPercent(stream: VolumeStreamType, percent: Int): AutomationOutcome {
        val am = audioManager ?: return AutomationOutcome(false, "AudioManager unavailable.")
        val clamped = percent.coerceIn(0, 100)
        val max = am.getStreamMaxVolume(stream.androidStream).coerceAtLeast(1)
        val target = ((clamped / 100f) * max).toInt().coerceIn(0, max)
        return try {
            am.setStreamVolume(stream.androidStream, target, AudioManager.FLAG_SHOW_UI)
            AutomationOutcome(true, "${stream.displayName} volume set to $clamped%", "index=$target/$max")
        } catch (e: SecurityException) {
            AutomationOutcome(
                false,
                "DND / notification policy access needed to adjust ${stream.displayName} volume.",
                suggestedStrategy = SuggestedRetryStrategy.REQUIRE_USER_CONFIRMATION
            )
        }
    }

    fun increaseStreamPercent(stream: VolumeStreamType, step: Int = 15): AutomationOutcome =
        setStreamPercent(stream, getStreamPercent(stream) + step)

    fun decreaseStreamPercent(stream: VolumeStreamType, step: Int = 15): AutomationOutcome =
        setStreamPercent(stream, getStreamPercent(stream) - step)

    fun setStreamMute(stream: VolumeStreamType, mute: Boolean): AutomationOutcome {
        val am = audioManager ?: return AutomationOutcome(false, "AudioManager unavailable.")
        return try {
            val dir = if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
            am.adjustStreamVolume(stream.androidStream, dir, AudioManager.FLAG_SHOW_UI)
            AutomationOutcome(true, if (mute) "${stream.displayName} muted" else "${stream.displayName} unmuted")
        } catch (e: SecurityException) {
            AutomationOutcome(false, "Notification Policy permission required to mute ${stream.displayName}.")
        } catch (e: Exception) {
            AutomationOutcome(false, "Mute failed: ${e.localizedMessage}")
        }
    }
}

class BatteryInformationManager(private val context: Context) {
    fun getBatteryInfo(): BatteryInfoSnapshot {
        val bm = context.getSystemService(BatteryManager::class.java)
        val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.coerceIn(0, 100) ?: 0
        val charging = bm?.isCharging ?: false
        val summary = "Battery $pct% (${if (charging) "Charging" else "On battery"})"
        return BatteryInfoSnapshot(levelPercent = pct, isCharging = charging, statusSummary = summary)
    }
}

class StorageInformationManager {
    fun getStorageInfo(): StorageInfoSnapshot {
        return try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val total = stat.totalBytes.toFloat() / (1024f * 1024f * 1024f)
            val free = stat.availableBytes.toFloat() / (1024f * 1024f * 1024f)
            val usedPct = if (total > 0f) (((total - free) / total) * 100f).toInt().coerceIn(0, 100) else 0
            StorageInfoSnapshot(
                totalGb = total,
                freeGb = free,
                usedPercent = usedPct,
                summary = String.format(java.util.Locale.US, "%.1f GB free of %.1f GB (%d%% used)", free, total, usedPct)
            )
        } catch (_: Exception) {
            StorageInfoSnapshot(summary = "Storage info unavailable")
        }
    }
}

class NetworkInformationManager(private val context: Context) {
    fun getNetworkInfo(): NetworkInfoSnapshot {
        val cm = context.getSystemService(ConnectivityManager::class.java)
            ?: return NetworkInfoSnapshot(summary = "ConnectivityManager unavailable")
        val net = cm.activeNetwork ?: return NetworkInfoSnapshot(false, "Offline", "No active network")
        val caps = cm.getNetworkCapabilities(net) ?: return NetworkInfoSnapshot(false, "Offline", "No network capabilities")
        val label = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Connected"
        }
        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        return NetworkInfoSnapshot(
            isConnected = true,
            transportLabel = label,
            summary = "Connected via $label${if (validated) " (Internet verified)" else ""}"
        )
    }
}

class DeviceInformationManager(private val context: Context) {
    fun getDeviceInfo(): DeviceInfoSnapshot {
        val am = context.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(mi)
        val ramGb = mi.totalMem.toFloat() / (1024f * 1024f * 1024f)
        val mins = (SystemClock.elapsedRealtime() / 60_000L).coerceAtLeast(1L)
        val uptime = if (mins >= 60L) "${mins / 60L}h ${mins % 60L}m" else "${mins}m"
        val model = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        val ver = "Android ${Build.VERSION.RELEASE}"
        return DeviceInfoSnapshot(
            manufacturerModel = model,
            androidVersion = ver,
            sdkInt = Build.VERSION.SDK_INT,
            uptimeFormatted = uptime,
            totalRamGb = ramGb,
            summary = "$model • $ver (SDK ${Build.VERSION.SDK_INT}) • Uptime $uptime"
        )
    }
}
