package com.example.automation

import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.example.vision.NotificationUnderstandingManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CapturedNotification(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val sender: String,
    val bodyRedacted: String,
    val timestampMs: Long,
    val hasReplyAction: Boolean
)

class MahiNotificationService : NotificationListenerService() {

    companion object {
        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

        private val _notifications = MutableStateFlow<List<CapturedNotification>>(emptyList())
        val notifications: StateFlow<List<CapturedNotification>> = _notifications.asStateFlow()

        @Volatile
        private var instance: MahiNotificationService? = null

        fun isListenerEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
            val cn = ComponentName(context, MahiNotificationService::class.java).flattenToString()
            return flat.contains(cn) || flat.contains(context.packageName)
        }

        fun sendAuthorizedReply(notificationKey: String, message: String): AutomationOutcome {
            val svc = instance
                ?: return AutomationOutcome(false, "Notification Listener Service not connected.")
            val clean = message.trim()
            if (clean.isBlank()) return AutomationOutcome(false, "Reply message cannot be empty.")
            val active = svc.activeNotifications?.firstOrNull { it.key == notificationKey }
                ?: return AutomationOutcome(false, "Notification no longer active.")
            val actions = active.notification?.actions
                ?: return AutomationOutcome(false, "Notification has no reply actions.")

            for (action in actions) {
                val remotes = action.remoteInputs ?: continue
                if (remotes.isEmpty()) continue
                val bundle = Bundle()
                remotes.forEach { ri -> bundle.putCharSequence(ri.resultKey, clean) }
                val fill = Intent()
                RemoteInput.addResultsToIntent(remotes, fill, bundle)
                return try {
                    action.actionIntent.send(svc, 0, fill)
                    AutomationOutcome(true, "Sent authorized reply to ${active.packageName}")
                } catch (e: Exception) {
                    AutomationOutcome(false, "Reply failed: ${e.localizedMessage}")
                }
            }
            return AutomationOutcome(false, "No inline RemoteInput reply field found on that notification.")
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        _isConnected.value = true
        refreshActive()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        if (instance === this) instance = null
        _isConnected.value = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        refreshActive()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        refreshActive()
    }

    private fun refreshActive() {
        val list = try {
            activeNotifications?.mapNotNull { sbn ->
                if (sbn.packageName == packageName) return@mapNotNull null
                val extras = sbn.notification?.extras ?: return@mapNotNull null
                val title = extras.getCharSequence("android.title")?.toString().orEmpty()
                val rawBody = extras.getCharSequence("android.bigText")?.toString()
                    ?: extras.getCharSequence("android.text")?.toString().orEmpty()
                val safeBody = NotificationUnderstandingManager.redactSensitiveText(rawBody)
                val hasReply = sbn.notification?.actions?.any { !it.remoteInputs.isNullOrEmpty() } == true
                CapturedNotification(
                    key = sbn.key,
                    packageName = sbn.packageName,
                    appLabel = sbn.packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() },
                    sender = title.ifBlank { sbn.packageName.substringAfterLast('.') },
                    bodyRedacted = safeBody,
                    timestampMs = sbn.postTime,
                    hasReplyAction = hasReply
                )
            }.orEmpty().sortedByDescending { it.timestampMs }.take(40)
        } catch (_: Exception) {
            emptyList()
        }
        _notifications.value = list
    }
}
