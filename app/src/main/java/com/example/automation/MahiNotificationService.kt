package com.example.automation

import android.app.Notification
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CapturedNotification(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val senderTitle: String,
    val messageText: String,
    val timestampMs: Long,
    val isWhatsApp: Boolean,
    val canReply: Boolean
)

class MahiNotificationService : NotificationListenerService() {

    companion object {
        private val WHATSAPP_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")

        @Volatile
        private var instance: MahiNotificationService? = null

        private val _notifications = MutableStateFlow<List<CapturedNotification>>(emptyList())
        val notifications: StateFlow<List<CapturedNotification>> = _notifications.asStateFlow()

        private val replyActions = mutableMapOf<String, Notification.Action>()

        fun isListenerEnabled(context: Context): Boolean {
            if (instance != null) return true
            return try {
                val expected = ComponentName(context, MahiNotificationService::class.java).flattenToString()
                val flat = Settings.Secure.getString(
                    context.contentResolver,
                    "enabled_notification_listeners"
                ) ?: return false
                val splitter = TextUtils.SimpleStringSplitter(':')
                splitter.setString(flat)
                while (splitter.hasNext()) {
                    if (splitter.next().equals(expected, ignoreCase = true)) return true
                }
                false
            } catch (_: Exception) {
                false
            }
        }

        fun openNotificationListenerSettings(context: Context) {
            context.startActivity(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }

        /**
         * Sends an authorized reply using Android Notification RemoteInput.
         * Callers MUST obtain explicit user confirmation before invoking this method.
         */
        fun sendAuthorizedReply(
            context: Context,
            notificationKey: String,
            replyText: String
        ): Result<String> {
            val action = replyActions[notificationKey]
                ?: return Result.failure(IllegalStateException("No active reply handle for this notification."))
            val remoteInputs = action.remoteInputs
            if (remoteInputs.isNullOrEmpty()) {
                return Result.failure(IllegalStateException("Notification does not expose RemoteInput."))
            }

            return try {
                val intent = Intent()
                val resultsBundle = Bundle()
                remoteInputs.forEach { ri ->
                    resultsBundle.putCharSequence(ri.resultKey, replyText)
                }
                RemoteInput.addResultsToIntent(remoteInputs, intent, resultsBundle)
                action.actionIntent.send(context, 0, intent)
                Result.success("Reply delivered cleanly.")
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        refreshActiveNotifications()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        refreshActiveNotifications()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        refreshActiveNotifications()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        if (instance === this) instance = null
    }

    private fun refreshActiveNotifications() {
        try {
            val active = activeNotifications ?: return
            replyActions.clear()
            val mapped = active.mapNotNull { sbn ->
                val notification = sbn.notification ?: return@mapNotNull null
                val extras = notification.extras ?: Bundle.EMPTY
                val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
                val text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
                    ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()

                if (title.isBlank() && text.isBlank()) return@mapNotNull null

                val replyAction = notification.actions?.firstOrNull { act ->
                    !act.remoteInputs.isNullOrEmpty()
                }
                if (replyAction != null) {
                    replyActions[sbn.key] = replyAction
                }

                val isWa = WHATSAPP_PACKAGES.contains(sbn.packageName)
                CapturedNotification(
                    key = sbn.key,
                    packageName = sbn.packageName,
                    appLabel = if (isWa) "WhatsApp" else sbn.packageName.substringAfterLast("."),
                    senderTitle = title.ifBlank { sbn.packageName },
                    messageText = text,
                    timestampMs = sbn.postTime,
                    isWhatsApp = isWa,
                    canReply = replyAction != null
                )
            }.sortedByDescending { it.timestampMs }.take(25)

            _notifications.value = mapped
        } catch (_: Exception) {
        }
    }
}
