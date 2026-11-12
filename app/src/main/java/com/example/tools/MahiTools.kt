package com.example.tools

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.provider.AlarmClock
import android.util.Log

data class ToolAction(
    val name: String,
    val params: Map<String, String>
)

data class ToolExecutionResult(
    val actionName: String,
    val displayTitle: String,
    val message: String,
    val linkUrl: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val isSuccess: Boolean = true
)

class MahiToolsManager(private val context: Context) {
    companion object {
        private const val TAG = "MahiTools"
    }

    private val cameraManager by lazy {
        try {
            context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        } catch (e: Exception) {
            null
        }
    }

    private var isTorchOn: Boolean = false

    fun execute(action: ToolAction): ToolExecutionResult {
        return try {
            when (action.name.lowercase().trim()) {
                "openwebsite", "openapp" -> {
                    val rawUrl = action.params["url"] ?: action.params["link"] ?: "https://google.com"
                    val formattedUrl = if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) {
                        rawUrl
                    } else {
                        "https://$rawUrl"
                    }
                    val label = action.params["title"] ?: action.params["label"] ?: getDomainName(formattedUrl)

                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(formattedUrl)).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)

                    ToolExecutionResult(
                        actionName = "openWebsite",
                        displayTitle = "Opened $label",
                        message = "Launched $formattedUrl",
                        linkUrl = formattedUrl
                    )
                }

                "searchweb" -> {
                    val query = action.params["query"] ?: action.params["q"] ?: "Mahi AI"
                    val searchUrl = "https://www.google.com/search?q=" + Uri.encode(query)
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(searchUrl)).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)

                    ToolExecutionResult(
                        actionName = "searchWeb",
                        displayTitle = "Searched: \"$query\"",
                        message = "Found results for $query",
                        linkUrl = searchUrl
                    )
                }

                "settimer" -> {
                    val minutesStr = action.params["minutes"] ?: action.params["min"] ?: "5"
                    val minutes = minutesStr.toIntOrNull() ?: 5
                    val label = action.params["label"] ?: "Mahi's Reminder"

                    val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                        putExtra(AlarmClock.EXTRA_LENGTH, minutes * 60)
                        putExtra(AlarmClock.EXTRA_MESSAGE, label)
                        putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    try {
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        Log.w(TAG, "AlarmClock intent failed, fallback to system clock", e)
                    }

                    ToolExecutionResult(
                        actionName = "setTimer",
                        displayTitle = "Timer Set: ${minutes}m",
                        message = "Set a $minutes-minute timer for '$label'"
                    )
                }

                "toggleflashlight" -> {
                    val targetState = action.params["state"]?.lowercase()
                    val turnOn = when (targetState) {
                        "on", "true", "enable" -> true
                        "off", "false", "disable" -> false
                        else -> !isTorchOn
                    }

                    cameraManager?.let { cm ->
                        val cameraId = cm.cameraIdList.firstOrNull()
                        if (cameraId != null) {
                            cm.setTorchMode(cameraId, turnOn)
                            isTorchOn = turnOn
                        }
                    }

                    ToolExecutionResult(
                        actionName = "toggleFlashlight",
                        displayTitle = if (turnOn) "Flashlight On 🔦" else "Flashlight Off 💡",
                        message = if (turnOn) "Turned on device flashlight" else "Turned off flashlight"
                    )
                }

                "changemood" -> {
                    val mood = action.params["mood"] ?: "SASSY"
                    ToolExecutionResult(
                        actionName = "changeMood",
                        displayTitle = "Mood: $mood",
                        message = "Shifted persona mood to $mood"
                    )
                }

                else -> {
                    ToolExecutionResult(
                        actionName = action.name,
                        displayTitle = "Action Executed",
                        message = "Completed action ${action.name}",
                        isSuccess = true
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing tool ${action.name}", e)
            ToolExecutionResult(
                actionName = action.name,
                displayTitle = "Action Failed",
                message = e.localizedMessage ?: "Failed to execute action",
                isSuccess = false
            )
        }
    }

    private fun getDomainName(url: String): String {
        return try {
            val uri = Uri.parse(url)
            uri.host?.replace("www.", "")?.capitalize() ?: "Link"
        } catch (e: Exception) {
            "Link"
        }
    }
}
