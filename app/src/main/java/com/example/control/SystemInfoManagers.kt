package com.example.control

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.math.roundToInt

data class BatteryInfoSnapshot(
    val levelPercent: Int = 0,
    val isCharging: Boolean = false,
    val chargeSource: String = "Battery",
    val temperatureCelsius: Float = 0f,
    val healthLabel: String = "Unknown",
    val statusSummary: String = "Unavailable"
)

class BatteryInformationManager(private val context: Context) {
    fun getBatteryInfo(): BatteryInfoSnapshot {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (intent == null) {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 0
            return BatteryInfoSnapshot(
                levelPercent = pct.coerceIn(0, 100),
                statusSummary = "Battery at $pct%"
            )
        }
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val pct = if (level >= 0) ((level * 100f) / scale).roundToInt().coerceIn(0, 100) else 0
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val plugLabel = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "AC Adapter"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
            else -> if (charging) "Charging" else "On Battery"
        }
        val tempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
        val tempC = tempTenths / 10f
        val health = when (intent.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over Voltage"
            BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
            else -> "Normal"
        }
        val summary = "Battery is $pct% ($plugLabel, ${"%.1f".format(Locale.US, tempC)}°C, Health: $health)."
        return BatteryInfoSnapshot(
            levelPercent = pct,
            isCharging = charging,
            chargeSource = plugLabel,
            temperatureCelsius = tempC,
            healthLabel = health,
            statusSummary = summary
        )
    }
}

data class StorageInfoSnapshot(
    val totalGb: Float = 0f,
    val freeGb: Float = 0f,
    val usedGb: Float = 0f,
    val usedPercent: Int = 0,
    val summary: String = "Unavailable"
)

class StorageInformationManager {
    fun getStorageInfo(): StorageInfoSnapshot {
        return try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val totalBytes = stat.totalBytes
            val availBytes = stat.availableBytes
            val usedBytes = (totalBytes - availBytes).coerceAtLeast(0L)
            val gbDiv = 1024f * 1024f * 1024f
            val totalGb = totalBytes / gbDiv
            val freeGb = availBytes / gbDiv
            val usedGb = usedBytes / gbDiv
            val pct = if (totalBytes > 0) ((usedBytes * 100f) / totalBytes).roundToInt().coerceIn(0, 100) else 0
            val summary = String.format(
                Locale.US,
                "Storage: %.1f GB free of %.1f GB (%d%% used).",
                freeGb,
                totalGb,
                pct
            )
            StorageInfoSnapshot(
                totalGb = totalGb,
                freeGb = freeGb,
                usedGb = usedGb,
                usedPercent = pct,
                summary = summary
            )
        } catch (e: Exception) {
            StorageInfoSnapshot(summary = "Storage check failed: ${e.localizedMessage}")
        }
    }
}

data class NetworkInfoSnapshot(
    val isConnected: Boolean = false,
    val transportLabel: String = "Offline",
    val wifiSsidOrNote: String = "",
    val isMetered: Boolean = false,
    val summary: String = "Offline"
)

class NetworkInformationManager(private val context: Context) {
    fun getNetworkInfo(): NetworkInfoSnapshot {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return NetworkInfoSnapshot(summary = "Connectivity service unavailable")

        val network = cm.activeNetwork
        val caps = if (network != null) cm.getNetworkCapabilities(network) else null
        if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return NetworkInfoSnapshot(
                isConnected = false,
                transportLabel = "Offline",
                summary = "No active internet connection (Wi-Fi and Cellular disconnected)."
            )
        }

        val transport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile Data"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "Connected"
        }
        val metered = cm.isActiveNetworkMetered

        val ssidNote = if (transport == "Wi-Fi") {
            val hasLoc = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            if (hasLoc) {
                @Suppress("DEPRECATION")
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                @Suppress("DEPRECATION")
                val ssid = wm?.connectionInfo?.ssid?.trim('"').orEmpty()
                if (ssid.isNotBlank() && ssid != "<unknown ssid>") ssid else "Wi-Fi Active"
            } else {
                "Connected (Android hides Wi-Fi name without Precise Location permission)"
            }
        } else {
            if (metered) "Metered cellular network" else "Unmetered connection"
        }

        return NetworkInfoSnapshot(
            isConnected = true,
            transportLabel = transport,
            wifiSsidOrNote = ssidNote,
            isMetered = metered,
            summary = "Connected via $transport ($ssidNote)."
        )
    }
}

data class DeviceInfoSnapshot(
    val manufacturerModel: String = "",
    val androidVersion: String = "",
    val sdkInt: Int = 0,
    val securityPatch: String = "",
    val totalRamGb: Float = 0f,
    val availRamGb: Float = 0f,
    val abi: String = "",
    val summary: String = ""
)

class DeviceInformationManager(private val context: Context) {
    fun getDeviceInfo(): DeviceInfoSnapshot {
        val modelName = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}".trim()
        val androidVer = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        val patch = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Build.VERSION.SECURITY_PATCH else "Unknown"
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"

        var totalGb = 0f
        var availGb = 0f
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(memInfo)
            val div = 1024f * 1024f * 1024f
            totalGb = memInfo.totalMem / div
            availGb = memInfo.availMem / div
        } catch (_: Exception) {
        }

        val summary = String.format(
            Locale.US,
            "%s running %s • RAM %.1f/%.1f GB free • Security Patch %s",
            modelName,
            androidVer,
            availGb,
            totalGb,
            patch
        )
        return DeviceInfoSnapshot(
            manufacturerModel = modelName,
            androidVersion = androidVer,
            sdkInt = Build.VERSION.SDK_INT,
            securityPatch = patch,
            totalRamGb = totalGb,
            availRamGb = availGb,
            abi = abi,
            summary = summary
        )
    }
}
