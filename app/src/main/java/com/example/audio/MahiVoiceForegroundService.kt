package com.example.audio

import android.app.ActivityManager
import android.app.KeyguardManager
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
import android.os.PowerManager
import android.os.Process
import android.util.Log
import com.example.MainActivity
import com.example.WakePopupActivity
import com.example.preferences.MahiPreferencesStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Android 14/15 compliant foreground service with foregroundServiceType="microphone".
 * Serves two non-overlapping modes with strict single-mic arbitration:
 * 1) Active Live Voice session (continuous Gemini Live / STT)
 * 2) Opt-in offline "Wake Up Detection" ("Hey Mahi" via Vosk)
 * Whenever Active Live Voice starts, Wake Detection pauses and releases its AudioRecord first.
 */
class MahiVoiceForegroundService : Service() {

    companion object {
        private const val TAG = "MahiVoiceFGS"
        private const val CHANNEL_ID = "mahi_live_voice_channel"
        private const val WAKE_POPUP_CHANNEL_ID = "mahi_wake_popup_channel"
        private const val NOTIFICATION_ID = 4102
        private const val WAKE_FULLSCREEN_NOTIF_ID = 4103

        const val ACTION_START_ACTIVE_VOICE = "com.example.audio.ACTION_START_ACTIVE_VOICE"
        const val ACTION_STOP_VOICE = "com.example.audio.ACTION_STOP_VOICE"
        const val ACTION_START_WAKE_DETECTION = "com.example.audio.ACTION_START_WAKE_DETECTION"
        const val ACTION_PAUSE_WAKE_FOR_ACTIVE = "com.example.audio.ACTION_PAUSE_WAKE_FOR_ACTIVE"
        const val ACTION_RESUME_WAKE = "com.example.audio.ACTION_RESUME_WAKE"
        const val ACTION_DISABLE_WAKE = "com.example.audio.ACTION_DISABLE_WAKE"

        private val _isForegroundActive = MutableStateFlow(false)
        val isForegroundActive: StateFlow<Boolean> = _isForegroundActive.asStateFlow()

        private val _isWakeServiceActive = MutableStateFlow(false)
        val isWakeServiceActive: StateFlow<Boolean> = _isWakeServiceActive.asStateFlow()

        private val _foregroundRestrictionNotice = MutableStateFlow<String?>(null)
        val foregroundRestrictionNotice: StateFlow<String?> = _foregroundRestrictionNotice.asStateFlow()

        var onStopRequestedFromNotification: (() -> Unit)? = null
        var onWakeDisabledFromNotification: (() -> Unit)? = null
        var onWakeTriggered: ((String) -> Unit)? = null

        fun start(context: Context): Result<Unit> =
            sendAction(context, ACTION_START_ACTIVE_VOICE)

        fun startWakeDetection(context: Context): Result<Unit> =
            sendAction(context, ACTION_START_WAKE_DETECTION)

        fun pauseWakeForActiveSession(context: Context) {
            if (_isWakeServiceActive.value) {
                sendAction(context, ACTION_PAUSE_WAKE_FOR_ACTIVE)
            }
        }

        fun resumeWakeIfEnabled(context: Context) {
            val enabled = MahiPreferencesStore(context).prefsFlow.value.wakeDetectionEnabled
            if (enabled) {
                sendAction(context, ACTION_RESUME_WAKE)
            }
        }

        fun disableWakeDetection(context: Context) {
            MahiPreferencesStore(context).setWakeDetectionEnabled(false)
            try {
                context.startService(
                    Intent(context, MahiVoiceForegroundService::class.java).apply {
                        action = ACTION_DISABLE_WAKE
                    }
                )
            } catch (_: Exception) {
                stop(context)
            }
            _isWakeServiceActive.value = false
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, MahiVoiceForegroundService::class.java))
            } catch (_: Exception) {
            }
            _isForegroundActive.value = false
            _isWakeServiceActive.value = false
        }

        private fun sendAction(context: Context, actionName: String): Result<Unit> {
            return try {
                val intent = Intent(context, MahiVoiceForegroundService::class.java).apply {
                    action = actionName
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                _foregroundRestrictionNotice.value = null
                Result.success(Unit)
            } catch (e: Exception) {
                val reason = "Android restricted starting background microphone service: ${e.localizedMessage}. Open Mahi AI to resume."
                Log.w(TAG, reason, e)
                _foregroundRestrictionNotice.value = reason
                Result.failure(e)
            }
        }
    }

    private var wakeEngine: WakeWordEngine? = null
    private var activeVoiceMode = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannels()

        when (intent?.action) {
            ACTION_STOP_VOICE -> {
                activeVoiceMode = false
                onStopRequestedFromNotification?.invoke()
                val wakeEnabled = MahiPreferencesStore(this).prefsFlow.value.wakeDetectionEnabled
                if (wakeEnabled) {
                    return startWakeMode()
                }
                wakeEngine?.stopAll()
                stopSelf()
                _isForegroundActive.value = false
                _isWakeServiceActive.value = false
                return START_NOT_STICKY
            }

            ACTION_DISABLE_WAKE -> {
                MahiPreferencesStore(this).setWakeDetectionEnabled(false)
                wakeEngine?.stopAll()
                _isWakeServiceActive.value = false
                onWakeDisabledFromNotification?.invoke()
                if (activeVoiceMode) {
                    promoteForeground(buildActiveVoiceNotification())
                    return START_NOT_STICKY
                }
                stopSelf()
                _isForegroundActive.value = false
                return START_NOT_STICKY
            }

            ACTION_PAUSE_WAKE_FOR_ACTIVE -> {
                activeVoiceMode = true
                wakeEngine?.pauseForActiveVoice()
                _isWakeServiceActive.value = false
                promoteForeground(buildActiveVoiceNotification())
                return START_NOT_STICKY
            }

            ACTION_RESUME_WAKE -> {
                activeVoiceMode = false
                return startWakeMode()
            }

            ACTION_START_WAKE_DETECTION -> {
                activeVoiceMode = false
                return startWakeMode()
            }

            else -> {
                // ACTION_START_ACTIVE_VOICE or service default
                activeVoiceMode = true
                wakeEngine?.pauseForActiveVoice()
                _isWakeServiceActive.value = false
                promoteForeground(buildActiveVoiceNotification())
                return START_NOT_STICKY
            }
        }
    }

    private fun startWakeMode(): Int {
        val prefs = MahiPreferencesStore(this).prefsFlow.value
        if (!prefs.wakeDetectionEnabled) {
            stopSelf()
            _isForegroundActive.value = false
            _isWakeServiceActive.value = false
            return START_NOT_STICKY
        }

        if (wakeEngine == null) {
            val enroll = WakeEnrollmentStore(this)
            val modelMgr = WakeModelManager(this)
            wakeEngine = WakeWordEngine(this, enroll, modelMgr).also { engine ->
                engine.onWakePhraseDetected = { transcript ->
                    handleWakeTrigger(transcript)
                }
            }
        }

        if (!promoteForeground(buildWakeDetectionNotification())) {
            return START_NOT_STICKY
        }

        val started = wakeEngine?.startWakeListening() == true
        _isWakeServiceActive.value = started
        if (!started) {
            _foregroundRestrictionNotice.value =
                wakeEngine?.lastError?.value ?: "Download offline model & grant Mic permission to run Wake Up Detection."
        }
        return START_STICKY
    }

    private fun isAppUnlockedInForeground(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val interactive = pm?.isInteractive ?: true
        val locked = km?.isKeyguardLocked ?: false
        if (!interactive || locked) return false

        val myPid = Process.myPid()
        val inForeground = am?.runningAppProcesses?.any { proc ->
            proc.pid == myPid &&
                proc.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        } ?: false
        return inForeground
    }

    private fun handleWakeTrigger(transcript: String) {
        // Release Vosk mic BEFORE opening WakePopupActivity / MainActivity Gemini Live
        wakeEngine?.pauseForActiveVoice()
        _isWakeServiceActive.value = false

        val canHandleDirectlyInApp = onWakeTriggered != null && isAppUnlockedInForeground()
        if (canHandleDirectlyInApp) {
            onWakeTriggered?.invoke(transcript)
            return
        }

        val popupIntent = Intent(this, WakePopupActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(WakePopupActivity.EXTRA_WAKE_TRANSCRIPT, transcript)
        }
        try {
            startActivity(popupIntent)
        } catch (e: Exception) {
            Log.w(TAG, "Direct WakePopupActivity launch blocked by Android; posting high-priority notification", e)
            postWakeFullScreenNotification(popupIntent, transcript)
        }
    }

    private fun postWakeFullScreenNotification(popupIntent: Intent, transcript: String) {
        val pi = PendingIntent.getActivity(
            this,
            4109,
            popupIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, WAKE_POPUP_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val notif = builder
            .setContentTitle("Mahi AI heard \"$transcript\"")
            .setContentText("Tap to speak with Mahi")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setFullScreenIntent(pi, true)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        getSystemService(NotificationManager::class.java)?.notify(WAKE_FULLSCREEN_NOTIF_ID, notif)
    }

    private fun promoteForeground(notification: Notification): Boolean {
        return try {
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
            true
        } catch (e: Exception) {
            val reason = "Microphone foreground service denied by Android (${e.localizedMessage}). Open Mahi AI to use voice."
            Log.e(TAG, reason, e)
            _isForegroundActive.value = false
            _isWakeServiceActive.value = false
            _foregroundRestrictionNotice.value = reason
            stopSelf()
            false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        wakeEngine?.stopAll()
        wakeEngine = null
        _isForegroundActive.value = false
        _isWakeServiceActive.value = false
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Mahi Live Voice & Wake Detection",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Active while Mahi AI microphone session or opt-in Wake Up Detection is running"
                    setShowBadge(false)
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(
                    WAKE_POPUP_CHANNEL_ID,
                    "Mahi Wake Trigger",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Opens Mahi AI when you say Hey Mahi"
                    setShowBadge(true)
                }
            )
        }
    }

    private fun buildActiveVoiceNotification(): Notification {
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
            .addAction(Notification.Action.Builder(null, "Stop Voice", stopIntent).build())
            .build()
    }

    private fun buildWakeDetectionNotification(): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            102,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val turnOffIntent = PendingIntent.getService(
            this,
            103,
            Intent(this, MahiVoiceForegroundService::class.java).apply { action = ACTION_DISABLE_WAKE },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("Wake Up Detection ON")
            .setContentText("Listening offline for \"Hey Mahi\" • Tap Turn Off anytime")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .addAction(Notification.Action.Builder(null, "Turn Off", turnOffIntent).build())
            .build()
    }
}
