package com.example.control

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.example.automation.MahiAccessibilityService
import com.example.automation.MahiNotificationService

data class PermissionAuditItem(
    val id: String,
    val title: String,
    val detail: String,
    val granted: Boolean,
    val actionLabel: String
)

class PermissionAuditManager(private val context: Context) {

    fun auditAll(): List<PermissionAuditItem> {
        val mic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val camera = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val notif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        val location = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val phone = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        val a11y = MahiAccessibilityService.isAccessibilityServiceEnabledInSettings(context)
        val notifListener = MahiNotificationService.isListenerEnabled(context)
        val overlay = Settings.canDrawOverlays(context)
        val writeSettings = Settings.System.canWrite(context)
        val pm = context.getSystemService(PowerManager::class.java)
        val batteryExempt = pm?.isIgnoringBatteryOptimizations(context.packageName) == true

        return listOf(
            PermissionAuditItem("mic", "Microphone", "Required for Voice Orb, Gemini Live & Wake detection", mic, "Request"),
            PermissionAuditItem("camera", "Camera", "Required for Neural Lens & Flashlight control", camera, "Request"),
            PermissionAuditItem("notifications", "Post Notifications", "Required on Android 13+ for voice/autopilot foreground alerts", notif, "Request"),
            PermissionAuditItem("location", "Location (GPS)", "Required for Nearby Places & location triggers", location, "Request"),
            PermissionAuditItem("phone", "Phone & Contacts", "Required for Call Screening & Voice Dialing", phone, "Request"),
            PermissionAuditItem("accessibility", "Accessibility Service", "Required for Screen Reader V2 & UI Automation (Android 13-16 Restricted Settings may apply)", a11y, "Open Settings"),
            PermissionAuditItem("notif_listener", "Notification Listener", "Required to read & summarize incoming alerts", notifListener, "Open Settings"),
            PermissionAuditItem("overlay", "Display Over Other Apps", "Required for floating Emergency STOP button", overlay, "Open Settings"),
            PermissionAuditItem("write_settings", "Modify System Settings", "Required for manual screen brightness control", writeSettings, "Open Settings"),
            PermissionAuditItem("battery", "Unrestricted Battery", "Recommended for continuous voice & wake service on Motorola / ColorOS", batteryExempt, "Optimize")
        )
    }

    fun openRestrictedSettingsAppInfo() {
        runCatching {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    fun requestBatteryOptimizationExemption() {
        runCatching {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}
