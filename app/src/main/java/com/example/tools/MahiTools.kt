package com.example.tools

import android.content.Context
import com.example.automation.MahiAccessibilityService
import com.example.automation.VoiceGestureIntent
import com.example.control.QuickSettingsManager
import com.example.control.VolumeManager
import com.example.control.VolumeStreamType

data class ToolAction(
    val name: String,
    val params: Map<String, String> = emptyMap()
)

data class ToolExecutionResult(
    val toolName: String,
    val title: String,
    val message: String,
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
    suspend fun performScreenTask(goal: String): ToolExecutionResult
    suspend fun setWatchMode(enabled: Boolean): ToolExecutionResult
    suspend fun confirmSensitiveAction(title: String, reason: String): Boolean
}

class MahiToolsManager(
    private val context: Context,
    private val volumeManager: VolumeManager = VolumeManager(context),
    private val quickSettingsManager: QuickSettingsManager = QuickSettingsManager(context),
    private val visionDelegate: MahiVisionToolDelegate? = null
) {
    fun execute(action: ToolAction): ToolExecutionResult {
        return when (action.name) {
            "setVolume" -> {
                val pct = action.params["percent"]?.toIntOrNull() ?: 50
                val res = volumeManager.setStreamPercent(VolumeStreamType.MEDIA, pct)
                ToolExecutionResult("setVolume", "Volume", res.message, res.success)
            }
            "openQuickSettings" -> {
                val res = quickSettingsManager.openQuickSettingsShade()
                ToolExecutionResult("openQuickSettings", "Quick Settings", res.message, res.success)
            }
            else -> ToolExecutionResult(action.name, action.name, "Use executeAsync for asynchronous UI/Vision tool '${action.name}'.", false)
        }
    }

    suspend fun executeAsync(action: ToolAction): ToolExecutionResult {
        return when (action.name) {
            "readScreen" -> {
                if (!MahiAccessibilityService.isConnected.value) {
                    ToolExecutionResult("readScreen", "Read Screen", "Accessibility Service is disabled. Enable it in Settings.", false)
                } else {
                    val snap = MahiAccessibilityService.readScreenWithRetry(context)
                    ToolExecutionResult("readScreen", "Screen (${snap.packageName})", snap.readableSummary, snap.elements.isNotEmpty())
                }
            }
            "tapElement" -> {
                if (!MahiAccessibilityService.isConnected.value) {
                    ToolExecutionResult("tapElement", "Tap Element", "Accessibility Service is disabled. Enable it in Settings.", false)
                } else {
                    val outcome = MahiAccessibilityService.tapElementAwait(
                        index = action.params["index"]?.toIntOrNull(),
                        query = action.params["query"],
                        viewId = action.params["viewId"]
                    )
                    ToolExecutionResult("tapElement", "Tap Element", outcome.message, outcome.success)
                }
            }
            "typeText" -> {
                if (!MahiAccessibilityService.isConnected.value) {
                    ToolExecutionResult("typeText", "Type Text", "Accessibility Service is disabled. Enable it in Settings.", false)
                } else {
                    val outcome = MahiAccessibilityService.typeTextAwait(
                        text = action.params["text"].orEmpty(),
                        index = action.params["targetIndex"]?.toIntOrNull(),
                        query = action.params["targetQuery"],
                        clearFirst = action.params["clearFirst"]?.toBooleanStrictOrNull() ?: false,
                        submit = action.params["submit"]?.toBooleanStrictOrNull() ?: true
                    )
                    ToolExecutionResult("typeText", "Type Text", outcome.message, outcome.success)
                }
            }
            "screenGesture" -> {
                if (!MahiAccessibilityService.isConnected.value) {
                    ToolExecutionResult("screenGesture", "Gesture", "Accessibility Service is disabled.", false)
                } else {
                    val intent = VoiceGestureIntent(
                        actionName = action.params["actionName"] ?: "SWIPE_UP_SCROLL",
                        targetQuery = action.params["targetQuery"],
                        targetIndex = action.params["targetIndex"]?.toIntOrNull(),
                        startX = action.params["startX"]?.toFloatOrNull(),
                        startY = action.params["startY"]?.toFloatOrNull(),
                        endX = action.params["endX"]?.toFloatOrNull(),
                        endY = action.params["endY"]?.toFloatOrNull()
                    )
                    val out = MahiAccessibilityService.executeGestureIntentAwait(intent)
                    ToolExecutionResult("screenGesture", "Gesture", out.message, out.success)
                }
            }
            "captureScreenImage" -> visionDelegate?.captureScreenImage(action.params["question"] ?: "Describe this screen")
                ?: ToolExecutionResult("captureScreenImage", "Vision", "Vision delegate unavailable.", false)
            "describeScreen" -> visionDelegate?.describeScreen(action.params["question"] ?: "What is on screen?")
                ?: ToolExecutionResult("describeScreen", "Screen", "Vision delegate unavailable.", false)
            "analyzeScreenshot" -> visionDelegate?.analyzeScreenshot(action.params["question"] ?: "Analyze screenshot")
                ?: ToolExecutionResult("analyzeScreenshot", "Screenshot", "Vision delegate unavailable.", false)
            "readTextFromCamera" -> visionDelegate?.readTextFromCamera(action.params["explain"]?.toBooleanStrictOrNull() ?: false)
                ?: ToolExecutionResult("readTextFromCamera", "Camera OCR", "Vision delegate unavailable.", false)
            "readTextFromScreenshot" -> visionDelegate?.readTextFromScreenshot(action.params["explain"]?.toBooleanStrictOrNull() ?: false)
                ?: ToolExecutionResult("readTextFromScreenshot", "Screenshot OCR", "Vision delegate unavailable.", false)
            "identifyObjects" -> visionDelegate?.identifyObjects()
                ?: ToolExecutionResult("identifyObjects", "Objects", "Vision delegate unavailable.", false)
            "recognizeScene" -> visionDelegate?.recognizeScene()
                ?: ToolExecutionResult("recognizeScene", "Scene", "Vision delegate unavailable.", false)
            "describeCameraView" -> visionDelegate?.describeCameraView(action.params["question"] ?: "What do you see?")
                ?: ToolExecutionResult("describeCameraView", "Camera View", "Vision delegate unavailable.", false)
            "summarizeNotifications" -> visionDelegate?.summarizeNotifications()
                ?: ToolExecutionResult("summarizeNotifications", "Notifications", "Vision delegate unavailable.", false)
            "openGalleryAnalysis" -> visionDelegate?.openGalleryAnalysis()
                ?: ToolExecutionResult("openGalleryAnalysis", "Gallery", "Vision delegate unavailable.", false)
            "compareImages" -> visionDelegate?.compareImages()
                ?: ToolExecutionResult("compareImages", "Compare Images", "Vision delegate unavailable.", false)
            "askAboutLastImage" -> visionDelegate?.askAboutLastImage(action.params["question"] ?: "")
                ?: ToolExecutionResult("askAboutLastImage", "VQA", "Vision delegate unavailable.", false)
            "performScreenTask" -> visionDelegate?.performScreenTask(action.params["goal"].orEmpty())
                ?: ToolExecutionResult("performScreenTask", "Screen Agent", "Vision delegate unavailable.", false)
            "setWatchMode" -> visionDelegate?.setWatchMode(action.params["enabled"]?.toBooleanStrictOrNull() ?: false)
                ?: ToolExecutionResult("setWatchMode", "Watch Mode", "Vision delegate unavailable.", false)
            else -> execute(action)
        }
    }
}
