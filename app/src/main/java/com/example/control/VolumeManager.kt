package com.example.control

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import com.example.automation.AutomationOutcome
import kotlin.math.roundToInt

enum class VolumeStreamType(
    val displayName: String,
    val androidStream: Int
) {
    MEDIA("Media", AudioManager.STREAM_MUSIC),
    RINGTONE("Ringtone", AudioManager.STREAM_RING),
    NOTIFICATION("Notification", AudioManager.STREAM_NOTIFICATION),
    ALARM("Alarm", AudioManager.STREAM_ALARM),
    CALL("Voice Call", AudioManager.STREAM_VOICE_CALL)
}

data class AllVolumeLevels(
    val mediaPercent: Int = 50,
    val ringtonePercent: Int = 50,
    val notificationPercent: Int = 50,
    val alarmPercent: Int = 60,
    val callPercent: Int = 70,
    val ringerMode: String = "Normal",
    val isMediaMuted: Boolean = false,
    val hasDndPolicyAccess: Boolean = false
)

/**
 * Real 5-stream Android AudioManager controller + DND access verification.
 */
class VolumeManager(private val context: Context) {

    private val audioManager: AudioManager?
        get() = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val notificationManager: NotificationManager?
        get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    fun hasDndPolicyAccess(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                notificationManager?.isNotificationPolicyAccessGranted == true
            } else true
        } catch (_: Exception) {
            false
        }
    }

    fun openDndAccessSettings(): Boolean {
        return try {
            context.startActivity(
                Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    fun getStreamPercent(streamType: VolumeStreamType): Int {
        val am = audioManager ?: return 50
        val max = am.getStreamMaxVolume(streamType.androidStream).coerceAtLeast(1)
        val cur = am.getStreamVolume(streamType.androidStream)
        return ((cur.toFloat() / max.toFloat()) * 100f).roundToInt().coerceIn(0, 100)
    }

    fun getAllLevels(): AllVolumeLevels {
        val am = audioManager
        val ringer = when (am?.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> "Silent"
            AudioManager.RINGER_MODE_VIBRATE -> "Vibrate"
            else -> "Normal"
        }
        val isMuted = try {
            am?.isStreamMute(AudioManager.STREAM_MUSIC) == true
        } catch (_: Exception) {
            getStreamPercent(VolumeStreamType.MEDIA) == 0
        }
        return AllVolumeLevels(
            mediaPercent = getStreamPercent(VolumeStreamType.MEDIA),
            ringtonePercent = getStreamPercent(VolumeStreamType.RINGTONE),
            notificationPercent = getStreamPercent(VolumeStreamType.NOTIFICATION),
            alarmPercent = getStreamPercent(VolumeStreamType.ALARM),
            callPercent = getStreamPercent(VolumeStreamType.CALL),
            ringerMode = ringer,
            isMediaMuted = isMuted,
            hasDndPolicyAccess = hasDndPolicyAccess()
        )
    }

    fun setStreamPercent(streamType: VolumeStreamType, percent: Int): AutomationOutcome {
        val am = audioManager ?: return AutomationOutcome(false, "AudioManager unavailable")
        if ((streamType == VolumeStreamType.RINGTONE || streamType == VolumeStreamType.NOTIFICATION) &&
            percent == 0 && !hasDndPolicyAccess()
        ) {
            openDndAccessSettings()
            return AutomationOutcome(
                false,
                "Android requires Do Not Disturb Access to silence ${streamType.displayName} to 0%. Opened DND settings."
            )
        }

        val clamped = percent.coerceIn(0, 100)
        val max = am.getStreamMaxVolume(streamType.androidStream).coerceAtLeast(1)
        val target = ((clamped / 100f) * max).roundToInt().coerceIn(0, max)
        return try {
            am.setStreamVolume(streamType.androidStream, target, AudioManager.FLAG_SHOW_UI)
            val actual = am.getStreamVolume(streamType.androidStream)
            AutomationOutcome(
                success = true,
                message = "${streamType.displayName} volume set to $clamped%",
                verificationDetail = "Verified stream index $actual/$max"
            )
        } catch (se: SecurityException) {
            openDndAccessSettings()
            AutomationOutcome(
                success = false,
                message = "Do Not Disturb Policy Access required to adjust ${streamType.displayName}. Opened permission screen."
            )
        } catch (e: Exception) {
            AutomationOutcome(false, e.localizedMessage ?: "Failed to adjust ${streamType.displayName} volume")
        }
    }

    fun muteMedia(mute: Boolean): AutomationOutcome {
        val am = audioManager ?: return AutomationOutcome(false, "AudioManager unavailable")
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
                    AudioManager.FLAG_SHOW_UI
                )
            } else {
                setStreamPercent(VolumeStreamType.MEDIA, if (mute) 0 else 55)
            }
            AutomationOutcome(
                success = true,
                message = if (mute) "Media volume muted" else "Media volume unmuted",
                verificationDetail = "Stream mute=$mute"
            )
        } catch (e: Exception) {
            AutomationOutcome(false, e.localizedMessage ?: "Failed to toggle mute")
        }
    }
}
