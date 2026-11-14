package com.example.tools

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.provider.AlarmClock
import android.util.Log
import com.example.automation.AppLauncherManager
import com.example.automation.AutonomousAgentEngine
import com.example.automation.DeviceControlHelper
import com.example.automation.MahiAccessibilityService
import com.example.automation.MahiNotificationService
import com.example.automation.VoiceGestureIntent
import com.example.camera.CameraLensFacing
import com.example.camera.MahiCameraController
import com.example.control.BatteryInformationManager
import com.example.control.DeviceInformationManager
import com.example.control.NetworkInformationManager
import com.example.control.QuickSettingsManager
import com.example.control.StorageInformationManager
import com.example.control.VolumeManager
import com.example.control.VolumeStreamType
import com.example.vision.NotificationUnderstandingManager

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

interface MahiVisionToolDelegate {
    suspend fun captureScreenImage(question: String): ToolExecutionResult
    suspend fun describeScreen(question: String): ToolExecutionResult
    suspend fun analyzeScreenshot(question: String): ToolExecutionResult
    suspend fun readTextFromCamera(explainWithAi: Boolean): ToolExecutionResult
    suspend fun readTextFromScreenshot(explainWithAi: Boolean): ToolExecutionResult
    suspend fun identifyObjects(): ToolExecutionResult
    suspend fun recognizeScene(): ToolExecutionResult
    suspend fun describeCameraView(question: String): ToolExecutionResult
    suspend fun summarizeNotifications(): ToolExecutionResult
    suspend fun openGalleryAnalysis(): ToolExecutionResult
    suspend fun compareImages(): ToolExecutionResult
    suspend fun askAboutLastImage(question: String): ToolExecutionResult
    suspend fun confirmSensitiveAction(title: String, reason: String): Boolean
}

class MahiToolsManager(
    private val context: Context,
    private val cameraController: MahiCameraController? = null,
    private val onNavigateToCamera: (() -> Unit)? = null,
    private val onRunAiSearchSummary: ((String) -> Unit)? = null,
    private val hasAudioPermission: () -> Boolean = { false },
    private val visionDelegate: MahiVisionToolDelegate? = null
) {
    companion object {
        private const val TAG = "MahiTools"
    }

    private val cameraManager by lazy {
        try {
            context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        } catch (_: Exception) {
            null
        }
    }

    private var isTorchOn: Boolean = false
    private val appLauncher by lazy { AppLauncherManager(context) }

    suspend fun executeAsync(action: ToolAction): ToolExecutionResult {
        return try {
            when (action.name.lowercase().trim()) {
                "readscreen" -> {
                    val res = MahiAccessibilityService.readScreenWithRetry(context)
                    res.fold(
                        onSuccess = { snap ->
                            ToolExecutionResult(
                                actionName = "readScreen",
                                displayTitle = "Screen Read (${snap.elements.size})",
                                message = snap.readableSummary,
                                isSuccess = true
                            )
                        },
                        onFailure = { err ->
                            ToolExecutionResult(
                                actionName = "readScreen",
                                displayTitle = "Read Screen",
                                message = err.localizedMessage ?: "Enable Mahi Accessibility Service to read screen.",
                                isSuccess = false
                            )
                        }
                    )
                }

                "tapelement" -> {
                    val index = action.params["index"]?.toIntOrNull()
                    val query = action.params["query"] ?: action.params["targetText"]
                    val viewId = action.params["viewId"]
                    val x = action.params["x"]?.toFloatOrNull()
                    val y = action.params["y"]?.toFloatOrNull()
                    val out = MahiAccessibilityService.tapElement(
                        context = context,
                        index = index,
                        query = query?.takeIf { it.isNotBlank() },
                        viewId = viewId?.takeIf { it.isNotBlank() },
                        x = x,
                        y = y
                    )
                    ToolExecutionResult(
                        actionName = "tapElement",
                        displayTitle = "Tap Element",
                        message = out.message,
                        isSuccess = out.success
                    )
                }

                "typetext" -> {
                    val index = action.params["index"]?.toIntOrNull()
                    val query = action.params["query"] ?: action.params["targetText"]
                    val text = action.params["text"] ?: ""
                    val append = action.params["append"]?.equals("true", ignoreCase = true) == true
                    val clearFirst = action.params["clearFirst"]?.equals("true", ignoreCase = true) == true
                    val submit = action.params["submit"]?.equals("true", ignoreCase = true) == true

                    val snap = MahiAccessibilityService.screenSnapshot.value
                    if (submit && isSensitiveSubmitContext(query, text, snap) && visionDelegate != null) {
                        val approved = visionDelegate.confirmSensitiveAction(
                            title = "Confirm Send / Submit",
                            reason = "Allow Mahi to type \"$text\" and submit on ${snap.packageName.ifBlank { "current app" }}?"
                        )
                        if (!approved) {
                            return ToolExecutionResult(
                                actionName = "typeText",
                                displayTitle = "Action Cancelled",
                                message = "Cancelled sending/submitting \"$text\" because confirmation was declined.",
                                isSuccess = false
                            )
                        }
                    }

                    val out = MahiAccessibilityService.typeText(
                        context = context,
                        index = index,
                        query = query?.takeIf { it.isNotBlank() },
                        text = text,
                        append = append,
                        clearFirst = clearFirst,
                        submit = submit
                    )
                    ToolExecutionResult(
                        actionName = "typeText",
                        displayTitle = if (submit) "Typed & Submitted" else "Typed Text",
                        message = out.message,
                        isSuccess = out.success
                    )
                }

                "capturescreenimage" -> {
                    val q = action.params["question"].orEmpty()
                    visionDelegate?.captureScreenImage(q) ?: run {
                        ToolExecutionResult(
                            actionName = "captureScreenImage",
                            displayTitle = "Screen Image",
                            message = "Screen image capture delegate unavailable.",
                            isSuccess = false
                        )
                    }
                }

                "describescreen" -> visionDelegate?.describeScreen(action.params["question"].orEmpty())
                    ?: ToolExecutionResult("describeScreen", "Screen Vision", "Vision delegate unavailable.", isSuccess = false)

                "analyzescreenshot" -> visionDelegate?.analyzeScreenshot(action.params["question"].orEmpty())
                    ?: ToolExecutionResult("analyzeScreenshot", "Screenshot Vision", "Vision delegate unavailable.", isSuccess = false)

                "readtextfromcamera" -> visionDelegate?.readTextFromCamera(
                    action.params["explain"]?.equals("true", ignoreCase = true) == true
                ) ?: ToolExecutionResult("readTextFromCamera", "Camera OCR", "Vision delegate unavailable.", isSuccess = false)

                "readtextfromscreenshot" -> visionDelegate?.readTextFromScreenshot(
                    action.params["explain"]?.equals("true", ignoreCase = true) == true
                ) ?: ToolExecutionResult("readTextFromScreenshot", "Screenshot OCR", "Vision delegate unavailable.", isSuccess = false)

                "identifyobjects" -> visionDelegate?.identifyObjects()
                    ?: ToolExecutionResult("identifyObjects", "Object Recognition", "Vision delegate unavailable.", isSuccess = false)

                "recognizescene" -> visionDelegate?.recognizeScene()
                    ?: ToolExecutionResult("recognizeScene", "Scene Recognition", "Vision delegate unavailable.", isSuccess = false)

                "describecameraview" -> visionDelegate?.describeCameraView(action.params["question"].orEmpty())
                    ?: ToolExecutionResult("describeCameraView", "Camera Vision", "Vision delegate unavailable.", isSuccess = false)

                "summarizenotifications" -> visionDelegate?.summarizeNotifications()
                    ?: execute(action.copy(name = "readNotifications"))

                "opengalleryanalysis" -> visionDelegate?.openGalleryAnalysis()
                    ?: ToolExecutionResult("openGalleryAnalysis", "Gallery Analysis", "Vision delegate unavailable.", isSuccess = false)

                "compareimages" -> visionDelegate?.compareImages()
                    ?: ToolExecutionResult("compareImages", "Compare Images", "Vision delegate unavailable.", isSuccess = false)

                "askaboutlastimage" -> visionDelegate?.askAboutLastImage(action.params["question"].orEmpty())
                    ?: ToolExecutionResult("askAboutLastImage", "Visual Q&A", "Vision delegate unavailable.", isSuccess = false)

                else -> execute(action)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in executeAsync ${action.name}", e)
            ToolExecutionResult(
                actionName = action.name,
                displayTitle = "Action Failed",
                message = e.localizedMessage ?: "Failed to execute action",
                isSuccess = false
            )
        }
    }

    private fun isSensitiveSubmitContext(query: String?, text: String, snap: com.example.automation.ScreenSnapshot): Boolean {
        val pkg = snap.packageName.lowercase()
        val q = query.orEmpty().lowercase()
        val t = text.lowercase()
        val isSearchBox = q.contains("search") || q.contains("find") || q.contains("url") || q.contains("address")
        if (isSearchBox && !pkg.contains("bank") && !pkg.contains("pay")) return false

        val sensitivePackages = listOf(
            "whatsapp", "mms", "messaging", "telegram", "orca", "instagram",
            "gmail", "email", "bank", "pay", "easypaisa", "jazzcash", "sadapay", "nayapay"
        )
        if (sensitivePackages.any { pkg.contains(it) }) return true

        val sensitiveKeywords = listOf("send", "reply", "pay", "transfer", "checkout", "delete", "confirm order")
        return sensitiveKeywords.any { q.contains(it) || t.contains(it) }
    }

    fun execute(action: ToolAction): ToolExecutionResult {
        return try {
            when (action.name.lowercase().trim()) {
                "openapp" -> {
                    val target = action.params["appName"] ?: action.params["title"] ?: action.params["url"] ?: ""
                    if (target.equals("google", ignoreCase = true)) {
                        val res = appLauncher.openGoogleApp()
                        return ToolExecutionResult(
                            actionName = "openGoogle",
                            displayTitle = res.appName,
                            message = res.message,
                            linkUrl = res.fallbackUrl,
                            isSuccess = res.success
                        )
                    }
                    if (target.equals("camera", ignoreCase = true) && cameraController != null) {
                        val msg = cameraController.prepareOpenCamera(CameraLensFacing.BACK)
                        onNavigateToCamera?.invoke()
                        return ToolExecutionResult("openCamera", "Camera Studio", msg, isSuccess = true)
                    }
                    val launchRes = appLauncher.launchApp(target, openStoreOrWebIfMissing = true)
                    ToolExecutionResult(
                        actionName = "openApp",
                        displayTitle = launchRes.appName,
                        message = launchRes.message,
                        linkUrl = launchRes.fallbackUrl,
                        isSuccess = launchRes.success
                    )
                }

                "opencamera" -> {
                    val lensArg = (action.params["frontOrBack"] ?: action.params["lens"] ?: "back").lowercase()
                    val lens = if (lensArg.contains("front") || lensArg.contains("selfie")) {
                        CameraLensFacing.FRONT
                    } else {
                        CameraLensFacing.BACK
                    }
                    if (cameraController != null) {
                        val msg = cameraController.prepareOpenCamera(lens)
                        onNavigateToCamera?.invoke()
                        ToolExecutionResult("openCamera", "Camera (${lens.name})", msg, isSuccess = true)
                    } else {
                        val ok = appLauncher.launchApp("Camera", openStoreOrWebIfMissing = false)
                        ToolExecutionResult("openCamera", "Camera", ok.message, isSuccess = ok.success)
                    }
                }

                "switchcamera" -> {
                    val lensArg = (action.params["frontOrBack"] ?: action.params["lens"] ?: "front").lowercase()
                    val lens = if (lensArg.contains("back") || lensArg.contains("rear")) {
                        CameraLensFacing.BACK
                    } else {
                        CameraLensFacing.FRONT
                    }
                    val msg = cameraController?.requestSwitchCamera(lens) ?: "Camera controller unavailable"
                    onNavigateToCamera?.invoke()
                    ToolExecutionResult("switchCamera", "Switch Camera (${lens.name})", msg, isSuccess = cameraController != null)
                }

                "capturephoto" -> {
                    val isSelfie = action.params["isSelfie"]?.lowercase() == "true"
                    val msg = cameraController?.requestPhotoOrSelfie(isSelfie = isSelfie, countdownSeconds = if (isSelfie) 3 else 0)
                        ?: "Camera controller unavailable"
                    onNavigateToCamera?.invoke()
                    ToolExecutionResult(
                        actionName = "capturePhoto",
                        displayTitle = if (isSelfie) "Selfie Countdown" else "Capture Photo",
                        message = msg,
                        isSuccess = cameraController != null
                    )
                }

                "startvideorecording" -> {
                    val msg = cameraController?.requestStartVideoRecording(hasAudioPermission())
                        ?: "Camera controller unavailable"
                    onNavigateToCamera?.invoke()
                    ToolExecutionResult("startVideoRecording", "Video Recording", msg, isSuccess = cameraController != null)
                }

                "stopvideorecording" -> {
                    val msg = cameraController?.stopVideoRecording() ?: "No active recording"
                    ToolExecutionResult("stopVideoRecording", "Video Saved", msg, isSuccess = true)
                }

                "setflash" -> {
                    val enabled = action.params["enabled"]?.lowercase() in setOf("true", "on", "1", "yes")
                    val msg = cameraController?.setFlashEnabled(enabled) ?: run {
                        setTorchSafely(enabled)
                        if (enabled) "Flashlight turned ON" else "Flashlight turned OFF"
                    }
                    ToolExecutionResult("setFlash", if (enabled) "Flash ON" else "Flash OFF", msg, isSuccess = true)
                }

                "setzoom" -> {
                    val raw = action.params["level"]?.lowercase()?.trim() ?: "1.0"
                    val msg = when {
                        cameraController == null -> "Camera controller unavailable"
                        raw == "in" || raw == "up" -> cameraController.adjustZoomBy(0.5f)
                        raw == "out" || raw == "down" -> cameraController.adjustZoomBy(-0.5f)
                        else -> cameraController.setZoomRatio(raw.toFloatOrNull() ?: 1.0f)
                    }
                    ToolExecutionResult("setZoom", "Camera Zoom", msg, isSuccess = cameraController != null)
                }

                "opengoogle" -> {
                    val res = appLauncher.openGoogleApp()
                    ToolExecutionResult(
                        actionName = "openGoogle",
                        displayTitle = res.appName,
                        message = res.message,
                        linkUrl = res.fallbackUrl,
                        isSuccess = res.success
                    )
                }

                "googlesearch" -> {
                    val query = action.params["query"] ?: action.params["q"] ?: ""
                    if (query.isBlank()) {
                        return ToolExecutionResult("googleSearch", "Google Search", "Search query cannot be empty", isSuccess = false)
                    }
                    val res = appLauncher.openGoogleSearch(query)
                    onRunAiSearchSummary?.invoke(query)
                    ToolExecutionResult(
                        actionName = "googleSearch",
                        displayTitle = "Google: \"$query\"",
                        message = res.message,
                        linkUrl = res.fallbackUrl,
                        isSuccess = res.success
                    )
                }

                "youtubesearch" -> {
                    val query = action.params["query"] ?: action.params["q"] ?: ""
                    if (query.isBlank()) {
                        return ToolExecutionResult("youtubeSearch", "YouTube Search", "Search query cannot be empty", isSuccess = false)
                    }
                    val res = appLauncher.openYouTubeSearch(query)
                    ToolExecutionResult(
                        actionName = "youtubeSearch",
                        displayTitle = "YouTube: \"$query\"",
                        message = res.message,
                        linkUrl = res.fallbackUrl,
                        isSuccess = res.success
                    )
                }

                "verifyapplaunch" -> {
                    val pkg = action.params["packageName"] ?: action.params["appName"] ?: ""
                    val res = appLauncher.verifyAppLaunch(pkg)
                    ToolExecutionResult(
                        actionName = "verifyAppLaunch",
                        displayTitle = "Verify: ${res.appName}",
                        message = "${res.message} ${res.verificationDetail}".trim(),
                        isSuccess = res.success
                    )
                }

                "stopautomation" -> {
                    MahiAccessibilityService.stopAllGestures()
                    cameraController?.stopVideoRecording()
                    ToolExecutionResult(
                        actionName = "stopAutomation",
                        displayTitle = "Stopped",
                        message = "Stopped active gestures and automation.",
                        isSuccess = true
                    )
                }

                "openwebsite" -> {
                    val titleOrUrl = action.params["url"] ?: action.params["title"] ?: ""
                    val known = if (!titleOrUrl.contains(".")) AppLauncherManager.matchKnownApp(titleOrUrl) else null
                    if (known != null) {
                        val launchRes = appLauncher.launchApp(known.canonicalName, openStoreOrWebIfMissing = true)
                        return ToolExecutionResult(
                            actionName = "openApp",
                            displayTitle = launchRes.appName,
                            message = launchRes.message,
                            linkUrl = launchRes.fallbackUrl,
                            isSuccess = launchRes.success
                        )
                    }
                    val res = appLauncher.openWebsite(titleOrUrl, action.params["title"])
                    ToolExecutionResult(
                        actionName = "openWebsite",
                        displayTitle = res.appName,
                        message = res.message,
                        linkUrl = res.fallbackUrl,
                        isSuccess = res.success
                    )
                }

                "screengesture" -> {
                    val gestureAction = action.params["action"] ?: "SWIPE_DOWN"
                    val targetText = action.params["targetText"].orEmpty()
                    val outcome = MahiAccessibilityService.executeGestureIntent(
                        VoiceGestureIntent(
                            actionName = gestureAction,
                            targetText = targetText,
                            targetIndex = action.params["targetIndex"]?.toIntOrNull(),
                            startX = action.params["startX"]?.toFloatOrNull(),
                            startY = action.params["startY"]?.toFloatOrNull(),
                            endX = action.params["endX"]?.toFloatOrNull(),
                            endY = action.params["endY"]?.toFloatOrNull()
                        ),
                        context = context
                    )
                    ToolExecutionResult(
                        actionName = "screenGesture",
                        displayTitle = gestureAction.replace('_', ' '),
                        message = outcome.message,
                        isSuccess = outcome.success
                    )
                }

                "tapelement" -> {
                    val out = MahiAccessibilityService.tapElement(
                        context = context,
                        index = action.params["index"]?.toIntOrNull(),
                        query = (action.params["query"] ?: action.params["targetText"])?.takeIf { it.isNotBlank() },
                        viewId = action.params["viewId"]?.takeIf { it.isNotBlank() },
                        x = action.params["x"]?.toFloatOrNull(),
                        y = action.params["y"]?.toFloatOrNull()
                    )
                    ToolExecutionResult("tapElement", "Tap Element", out.message, isSuccess = out.success)
                }

                "searchweb" -> {
                    val query = action.params["query"] ?: action.params["q"] ?: "Mahi AI"
                    val target = action.params["target"].orEmpty()
                    if (target.equals("youtube", ignoreCase = true)) {
                        val ytRes = appLauncher.openYouTubeSearch(query)
                        return ToolExecutionResult(
                            actionName = "youtubeSearch",
                            displayTitle = "YouTube: \"$query\"",
                            message = ytRes.message,
                            linkUrl = ytRes.fallbackUrl,
                            isSuccess = ytRes.success
                        )
                    }
                    val gRes = appLauncher.openGoogleSearch(query)
                    onRunAiSearchSummary?.invoke(query)
                    ToolExecutionResult(
                        actionName = "googleSearch",
                        displayTitle = "Google: \"$query\"",
                        message = gRes.message,
                        linkUrl = gRes.fallbackUrl,
                        isSuccess = gRes.success
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
                    setTorchSafely(turnOn)
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

                "readnotifications" -> {
                    val filter = action.params["filter"]?.lowercase().orEmpty()
                    val list = MahiNotificationService.notifications.value.let { all ->
                        if (filter.contains("whatsapp")) all.filter { it.isWhatsApp } else all
                    }
                    val enabled = MahiNotificationService.isListenerEnabled(context)
                    val safeList = NotificationUnderstandingManager().getSafeSummaries(list)
                    val summary = when {
                        safeList.isNotEmpty() -> safeList.take(5).joinToString(" | ") { "[${it.priority.name}] ${it.appLabel} - ${it.senderTitle}: ${it.redactedMessage}" }
                        !enabled -> "Notification Access is disabled. Enable Mahi Notification Listener in settings to read alerts."
                        else -> "No active notifications right now."
                    }
                    ToolExecutionResult(
                        actionName = "readNotifications",
                        displayTitle = "Notifications (${list.size})",
                        message = summary,
                        isSuccess = enabled || list.isNotEmpty()
                    )
                }

                "getbatteryinfo" -> {
                    val info = BatteryInformationManager(context).getBatteryInfo()
                    ToolExecutionResult("getBatteryInfo", "Battery ${info.levelPercent}%", info.statusSummary, isSuccess = true)
                }

                "getstorageinfo" -> {
                    val info = StorageInformationManager().getStorageInfo()
                    ToolExecutionResult("getStorageInfo", "Internal Storage", info.summary, isSuccess = true)
                }

                "getnetworkinfo" -> {
                    val info = NetworkInformationManager(context).getNetworkInfo()
                    ToolExecutionResult("getNetworkInfo", "Network: ${info.transportLabel}", info.summary, isSuccess = true)
                }

                "getdeviceinfo" -> {
                    val info = DeviceInformationManager(context).getDeviceInfo()
                    ToolExecutionResult("getDeviceInfo", info.manufacturerModel, info.summary, isSuccess = true)
                }

                "setvolume" -> {
                    val vm = VolumeManager(context)
                    val streamName = (action.params["stream"] ?: "media").lowercase()
                    val stream = when {
                        streamName.contains("ring") -> VolumeStreamType.RINGTONE
                        streamName.contains("notif") -> VolumeStreamType.NOTIFICATION
                        streamName.contains("alarm") -> VolumeStreamType.ALARM
                        streamName.contains("call") -> VolumeStreamType.CALL
                        else -> VolumeStreamType.MEDIA
                    }
                    val muteArg = action.params["mute"]?.lowercase()
                    val outcome = if (muteArg == "true" || muteArg == "false") {
                        vm.setStreamMute(stream, muteArg == "true")
                    } else {
                        val pct = action.params["percent"]?.toIntOrNull() ?: 50
                        vm.setStreamPercent(stream, pct)
                    }
                    ToolExecutionResult("setVolume", "${stream.displayName} Volume", outcome.message, isSuccess = outcome.success)
                }

                "openquicksettings" -> {
                    val target = (action.params["panel"] ?: "quick_settings").lowercase()
                    val qsm = QuickSettingsManager(context)
                    val out = when {
                        target.contains("notif") -> qsm.openNotificationShade()
                        target.contains("wifi") -> qsm.openInternetOrWirelessPanel()
                        target.contains("bluetooth") -> qsm.openBluetoothSettings()
                        target.contains("data") -> qsm.openMobileDataSettings()
                        target.contains("airplane") -> qsm.openAirplaneModeSettings()
                        target.contains("nfc") -> qsm.openNfcSettings()
                        else -> qsm.openQuickSettingsShade()
                    }
                    ToolExecutionResult("openQuickSettings", "Quick Settings", out.message, isSuccess = out.success)
                }

                "opensettings" -> {
                    val cat = action.params["category"] ?: "settings"
                    val out = DeviceControlHelper(context).openSettingsScreen(cat)
                    ToolExecutionResult(
                        actionName = "openSettings",
                        displayTitle = "Settings: ${cat.ifBlank { "System" }}",
                        message = out.message,
                        isSuccess = out.success
                    )
                }

                else -> {
                    ToolExecutionResult(
                        actionName = action.name,
                        displayTitle = "Unknown Command",
                        message = "Unsupported tool '${action.name}' ignored safely.",
                        isSuccess = false
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

    private fun setTorchSafely(turnOn: Boolean) {
        cameraManager?.let { cm ->
            val cameraId = cm.cameraIdList.firstOrNull()
            if (cameraId != null) {
                cm.setTorchMode(cameraId, turnOn)
                isTorchOn = turnOn
            }
        }
    }
}
