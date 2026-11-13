package com.example.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.example.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Android 14+ compliant foreground service with foregroundServiceType="microphone"
 * so continuous voice capture remains permitted when switching to apps like TikTok,
 * Facebook, or YouTube after the user explicitly starts a live session inside Mahi AI.
 */
class MahiVoiceForegroundService : Service() {

    companion object {
        private const val TAG = "MahiVoiceFGS"
        private const val CHANNEL_ID = "mahi_live_voice_channel"
        private const val NOTIFICATION_ID = 4102
        const val ACTION_STOP_VOICE = "com.example.audio.ACTION_STOP_VOICE"

        private val _isForegroundActive = MutableStateFlow(false)
        val isForegroundActive: StateFlow<Boolean> = _isForegroundActive.asStateFlow()

        private val _foregroundRestrictionNotice = MutableStateFlow<String?>(null)
        val foregroundRestrictionNotice: StateFlow<String?> = _foregroundRestrictionNotice.asStateFlow()

        var onStopRequestedFromNotification: (() -> Unit)? = null

        fun start(context: Context): Result<Unit> {
            return try {
                val intent = Intent(context, MahiVoiceForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                _foregroundRestrictionNotice.value = null
                Result.success(Unit)
            } catch (e: Exception) {
                val reason = "Android restricted starting background microphone service: ${e.localizedMessage}. Tap Orb inside Mahi AI to resume."
                Log.w(TAG, reason, e)
                _foregroundRestrictionNotice.value = reason
                Result.failure(e)
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, MahiVoiceForegroundService::class.java))
            } catch (_: Exception) {
            }
            _isForegroundActive.value = false
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_VOICE) {
            onStopRequestedFromNotification?.invoke()
            stopSelf()
            _isForegroundActive.value = false
            return START_NOT_STICKY
        }

        createNotificationChannel()
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            _isForegroundActive.value = true
            _foregroundRestrictionNotice.value = null
        } catch (e: Exception) {
            val reason = "Microphone foreground service denied by Android (${e.localizedMessage}). Open Mahi AI to use voice."
            Log.e(TAG, reason, e)
            _isForegroundActive.value = false
            _foregroundRestrictionNotice.value = reason
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        _isForegroundActive.value = false
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Mahi Live Voice Session",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Active while Mahi AI continuous microphone listening is enabled"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            100,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            101,
            Intent(this, MahiVoiceForegroundService::class.java).apply { action = ACTION_STOP_VOICE },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("Mahi AI Voice Active")
            .setContentText("Listening in continuous mode • Tap Stop anytime")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .addAction(
                Notification.Action.Builder(
                    null,
                    "Stop Voice",
                    stopIntent
                ).build()
            )
            .build()
    }
}
