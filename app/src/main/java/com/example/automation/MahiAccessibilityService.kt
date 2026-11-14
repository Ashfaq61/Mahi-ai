package com.example.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.vision.NotificationUnderstandingManager
import com.example.vision.VisionBitmapUtils
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class ScreenElementNode(
    val id: String,
    val text: String,
    val contentDescription: String,
    val className: String,
    val viewIdResourceName: String,
    val isClickable: Boolean,
    val isLongClickable: Boolean,
    val isScrollable: Boolean,
    val isEditable: Boolean,
    val bounds: Rect,
    val index: Int = 0,
    val isPassword: Boolean = false,
    val isFocused: Boolean = false
) {
    val centerX: Int get() = bounds.centerX()
    val centerY: Int get() = bounds.centerY()

    val bestLabel: String
        get() = text.ifBlank { contentDescription }.ifBlank { viewIdResourceName.substringAfterLast("/") }

    fun toIndexedLine(): String {
        val flags = buildList {
            if (isClickable) add("clickable")
            if (isEditable) add("editable")
            if (isScrollable) add("scrollable")
            if (isFocused) add("focused")
        }
        val widget = className.ifBlank { "View" }
        val safeLabel = if (isPassword) {
            "[PASSWORD_HIDDEN]"
        } else {
            NotificationUnderstandingManager.redactSensitiveText(bestLabel).first.take(80)
        }
        val flagStr = if (flags.isNotEmpty()) " [${flags.joinToString(",")}]" else ""
        val num = if (index > 0) "$index. " else ""
        return "$num[$widget] \"$safeLabel\"$flagStr @($centerX,$centerY)"
    }
}

data class ScreenSnapshot(
    val packageName: String = "",
    val elements: List<ScreenElementNode> = emptyList(),
    val capturedAtMs: Long = 0L
) {
    val readableSummary: String
        get() {
            if (elements.isEmpty()) return "No screen elements captured yet. Enable Mahi Accessibility Service to inspect active apps."
            val lines = elements.take(60).joinToString("\n") { it.toIndexedLine() }
            return "App: $packageName | Visible elements (${elements.size}):\n$lines"
        }
}

enum class SwipeDirection { UP, DOWN, LEFT, RIGHT }

data class GestureActionRecord(
    val id: String = UUID.randomUUID().toString(),
    val actionName: String,
    val detail: String,
    val success: Boolean,
    val timestampMs: Long = System.currentTimeMillis()
)

data class GestureSegment(
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float
)

class MahiAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        private var activeService: MahiAccessibilityService? = null

        private val stopFlag = AtomicBoolean(false)
        private val fallbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        private var continuousJob: Job? = null

        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

        private val _screenSnapshot = MutableStateFlow(ScreenSnapshot())
        val screenSnapshot: StateFlow<ScreenSnapshot> = _screenSnapshot.asStateFlow()

        private val _gestureStatus = MutableStateFlow("Idle — ready for screen gestures")
        val gestureStatus: StateFlow<String> = _gestureStatus.asStateFlow()

        private val _isContinuousScrolling = MutableStateFlow(false)
        val isContinuousScrolling: StateFlow<Boolean> = _isContinuousScrolling.asStateFlow()

        private val _gestureHistory = MutableStateFlow<List<GestureActionRecord>>(emptyList())
        val gestureHistory: StateFlow<List<GestureActionRecord>> = _gestureHistory.asStateFlow()

        private val _isReadingScreen = MutableStateFlow(false)
        val isReadingScreen: StateFlow<Boolean> = _isReadingScreen.asStateFlow()

        fun computeSwipeSegment(bounds: Rect, direction: SwipeDirection): GestureSegment {
            val left = bounds.left.toFloat()
            val right = bounds.right.toFloat()
            val top = bounds.top.toFloat()
            val bottom = bounds.bottom.toFloat()
            val width = (right - left).coerceAtLeast(100f)
            val height = (bottom - top).coerceAtLeast(100f)
            val cx = left + width * 0.5f
            val cy = top + height * 0.5f

            return when (direction) {
                SwipeDirection.UP -> GestureSegment(
                    startX = cx,
                    startY = top + height * 0.78f,
                    endX = cx,
                    endY = top + height * 0.22f
                )
                SwipeDirection.DOWN -> GestureSegment(
                    startX = cx,
                    startY = top + height * 0.22f,
                    endX = cx,
                    endY = top + height * 0.78f
                )
                SwipeDirection.LEFT -> GestureSegment(
                    startX = left + width * 0.84f,
                    startY = cy,
                    endX = left + width * 0.16f,
                    endY = cy
                )
                SwipeDirection.RIGHT -> GestureSegment(
                    startX = left + width * 0.16f,
                    startY = cy,
                    endX = left + width * 0.84f,
                    endY = cy
                )
            }
        }

        private fun recordGesture(actionName: String, detail: String, success: Boolean) {
            val entry = GestureActionRecord(
                actionName = actionName,
                detail = detail,
                success = success
            )
            _gestureHistory.value = (listOf(entry) + _gestureHistory.value).take(40)
            _gestureStatus.value = detail
        }

        fun clearGestureHistory() {
            _gestureHistory.value = emptyList()
        }

        fun isServiceEnabled(context: Context): Boolean {
            if (activeService != null) return true
            return try {
                val expected = ComponentName(context, MahiAccessibilityService::class.java).flattenToString()
                val enabled = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: return false
                val splitter = TextUtils.SimpleStringSplitter(':')
                splitter.setString(enabled)
                while (splitter.hasNext()) {
                    if (splitter.next().equals(expected, ignoreCase = true)) return true
                }
                false
            } catch (_: Exception) {
                false
            }
        }

        fun openAccessibilitySettings(context: Context): Boolean {
            return try {
                context.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                true
            } catch (_: Exception) {
                false
            }
        }

        fun refreshScreenSnapshot(force: Boolean = false): ScreenSnapshot? {
            return activeService?.captureCurrentScreen(force = force)
        }

        suspend fun readScreenWithRetry(context: Context): Result<ScreenSnapshot> {
            val svc = activeService
            if (svc == null) {
                openAccessibilitySettings(context)
                recordGesture("READ_SCREEN", "Accessibility Service off — opened Accessibility Settings", false)
                return Result.failure(
                    IllegalStateException(
                        "Mahi Accessibility Service is off. Opened Accessibility Settings—please turn on Mahi AI so I can read the screen."
                    )
                )
            }
            _isReadingScreen.value = true
            return try {
                var snap = svc.captureCurrentScreen(force = true)
                var attempts = 0
                while ((snap == null || snap.elements.isEmpty()) && attempts < 2) {
                    delay(180L)
                    snap = svc.captureCurrentScreen(force = true)
                    attempts++
                }
                if (snap == null || snap.elements.isEmpty()) {
                    recordGesture("READ_SCREEN", "No readable UI nodes exposed by ${snap?.packageName ?: "active app"}", false)
                    Result.failure(
                        IllegalStateException(
                            "This screen has no readable Accessibility text nodes (e.g. game/canvas/protected view). Use captureScreenImage to inspect visually."
                        )
                    )
                } else {
                    recordGesture("READ_SCREEN", "Read ${snap.elements.size} UI elements in ${snap.packageName}", true)
                    Result.success(snap)
                }
            } finally {
                _isReadingScreen.value = false
            }
        }

        fun tapElement(
            context: Context? = null,
            index: Int? = null,
            query: String? = null,
            viewId: String? = null,
            x: Float? = null,
            y: Float? = null
        ): AutomationOutcome {
            val svc = activeService
            if (svc == null) {
                if (context != null) openAccessibilitySettings(context)
                recordGesture("TAP", "Accessibility Service off — cannot tap element", false)
                return AutomationOutcome(
                    false,
                    "Mahi Accessibility Service is off. Please enable Mahi AI in Accessibility Settings to tap on screen."
                )
            }
            val ok = svc.performUnifiedTap(index = index, query = query, viewId = viewId, x = x, y = y)
            val label = when {
                index != null && index > 0 -> "element #$index"
                !query.isNullOrBlank() -> "'$query'"
                !viewId.isNullOrBlank() -> "id:$viewId"
                x != null && y != null -> "($x,$y)"
                else -> "target"
            }
            recordGesture("TAP", if (ok) "Tapped $label" else "Could not find or tap $label", ok)
            if (ok) svc.captureCurrentScreen(force = true)
            return AutomationOutcome(
                success = ok,
                message = if (ok) "Tapped $label cleanly." else "Element $label was not found on the current screen.",
                verificationDetail = if (ok) "Tap dispatched & screen refreshed" else ""
            )
        }

        fun typeText(
            context: Context,
            index: Int? = null,
            query: String? = null,
            text: String,
            append: Boolean = false,
            clearFirst: Boolean = false,
            submit: Boolean = false
        ): AutomationOutcome {
            if (text.isBlank() && !clearFirst) {
                return AutomationOutcome(false, "Cannot type empty text unless clearFirst=true.")
            }
            val svc = activeService
            if (svc == null) {
                openAccessibilitySettings(context)
                recordGesture("TYPE_TEXT", "Accessibility Service off — opened Settings", false)
                return AutomationOutcome(
                    false,
                    "Mahi Accessibility Service is off. Opened Accessibility Settings—enable Mahi AI to type on screen."
                )
            }
            val outcome = svc.performTypeText(
                context = context,
                index = index,
                query = query,
                text = text,
                append = append,
                clearFirst = clearFirst,
                submit = submit
            )
            recordGesture("TYPE_TEXT", outcome.message, outcome.success)
            if (outcome.success) svc.captureCurrentScreen(force = true)
            return outcome
        }

        fun tapElementByText(query: String): Boolean {
            val svc = activeService
            if (svc == null) {
                recordGesture("TAP", "Accessibility Service off — cannot tap '$query'", false)
                return false
            }
            val ok = svc.performActionOnMatchingNode(query, longClick = false)
            recordGesture("TAP", if (ok) "Tapped '$query'" else "Element '$query' not found", ok)
            return ok
        }

        fun doubleTapElementOrCenter(query: String = ""): Boolean {
            val svc = activeService
            if (svc == null) {
                recordGesture("DOUBLE_TAP", "Accessibility Service off — cannot double tap", false)
                return false
            }
            val ok = svc.performDoubleTap(query)
            recordGesture("DOUBLE_TAP", if (ok) "Double-tapped ${query.ifBlank { "center" }}" else "Double tap failed", ok)
            return ok
        }

        fun longPressElementByText(query: String): Boolean {
            val svc = activeService
            if (svc == null) {
                recordGesture("LONG_PRESS", "Accessibility Service off — cannot long press '$query'", false)
                return false
            }
            val ok = svc.performActionOnMatchingNode(query, longClick = true)
            recordGesture("LONG_PRESS", if (ok) "Long-pressed '$query'" else "Element '$query' not found", ok)
            return ok
        }

        fun dragBetweenElements(fromQuery: String, toQuery: String): Boolean {
            val svc = activeService
            if (svc == null) {
                recordGesture("DRAG_DROP", "Accessibility Service off — cannot drag '$fromQuery'", false)
                return false
            }
            val ok = svc.performDragBetweenNodes(fromQuery, toQuery)
            recordGesture("DRAG_DROP", if (ok) "Dragged '$fromQuery' → '$toQuery'" else "Drag targets unavailable", ok)
            return ok
        }

        fun dragScreen(startX: Float, startY: Float, endX: Float, endY: Float): Boolean {
            val svc = activeService
            if (svc == null) {
                recordGesture("DRAG", "Accessibility Service off", false)
                return false
            }
            val ok = svc.performDragCoordinates(startX, startY, endX, endY, 550L)
            recordGesture("DRAG", if (ok) "Dragged ($startX,$startY) → ($endX,$endY)" else "Drag failed", ok)
            return ok
        }

        fun scrollScreen(forward: Boolean, speed: GestureSpeed = GestureSpeed.NORMAL): Boolean {
            val svc = activeService
            if (svc == null) {
                recordGesture("SCROLL", "Accessibility Service off — cannot scroll", false)
                return false
            }
            val ok = svc.performScrollOnScreen(forward, speed)
            val label = if (forward) "Scroll Down (${speed.displayName})" else "Scroll Up (${speed.displayName})"
            recordGesture("SCROLL", if (ok) label else "Scroll failed", ok)
            return ok
        }

        fun swipeScreen(
            direction: SwipeDirection,
            speed: GestureSpeed = GestureSpeed.NORMAL,
            targetIndex: Int? = null,
            targetQuery: String = "",
            startX: Float? = null,
            startY: Float? = null,
            endX: Float? = null,
            endY: Float? = null
        ): Boolean {
            val svc = activeService
            if (svc == null) {
                recordGesture("SWIPE", "Accessibility Service off — cannot swipe ${direction.name}", false)
                return false
            }
            val ok = if (startX != null && startY != null && endX != null && endY != null) {
                svc.performDragCoordinates(startX, startY, endX, endY, speed.durationMs)
            } else {
                svc.performSwipeGesture(
                    direction = direction,
                    speed = speed,
                    useScrollableBounds = true,
                    targetIndex = targetIndex,
                    targetQuery = targetQuery
                )
            }
            recordGesture("SWIPE_${direction.name}", if (ok) "Swiped ${direction.name} (${speed.displayName})" else "Swipe failed", ok)
            if (ok) svc.captureCurrentScreen(force = true)
            return ok
        }

        fun startContinuousScroll(forward: Boolean, speed: GestureSpeed = GestureSpeed.NORMAL): Boolean {
            val svc = activeService
            if (svc == null) {
                recordGesture("CONTINUOUS_SCROLL", "Accessibility Service off", false)
                return false
            }
            stopFlag.set(false)
            continuousJob?.cancel()
            _isContinuousScrolling.value = true
            _gestureStatus.value = "Continuous scroll ${if (forward) "DOWN" else "UP"} (${speed.displayName})..."
            continuousJob = fallbackScope.launch {
                val intervalMs = when (speed) {
                    GestureSpeed.FAST -> 520L
                    GestureSpeed.NORMAL -> 950L
                    GestureSpeed.SLOW -> 1450L
                }
                var steps = 0
                while (isActive && !stopFlag.get() && steps < 40) {
                    val acted = svc.performScrollOnScreen(forward, speed)
                    if (!acted) break
                    steps++
                    delay(intervalMs)
                }
                _isContinuousScrolling.value = false
                if (!stopFlag.get()) {
                    recordGesture("CONTINUOUS_SCROLL", "Completed $steps continuous scroll steps", steps > 0)
                }
            }
            return true
        }

        fun scrollToEdge(toBottom: Boolean): Boolean {
            val svc = activeService
            if (svc == null) {
                recordGesture("SCROLL_EDGE", "Accessibility Service off", false)
                return false
            }
            stopFlag.set(false)
            continuousJob?.cancel()
            _isContinuousScrolling.value = true
            _gestureStatus.value = if (toBottom) "Scrolling to bottom..." else "Scrolling to top..."
            continuousJob = fallbackScope.launch {
                var steps = 0
                var previousSig = svc.captureCurrentScreen()?.readableSummary.orEmpty()
                while (isActive && !stopFlag.get() && steps < 10) {
                    val ok = svc.performSwipeGesture(
                        if (toBottom) SwipeDirection.UP else SwipeDirection.DOWN,
                        GestureSpeed.FAST,
                        useScrollableBounds = true
                    )
                    if (!ok) break
                    steps++
                    delay(320L)
                    val nextSig = svc.captureCurrentScreen()?.readableSummary.orEmpty()
                    if (steps >= 2 && nextSig == previousSig) break
                    previousSig = nextSig
                }
                _isContinuousScrolling.value = false
                if (!stopFlag.get()) {
                    recordGesture(
                        if (toBottom) "SCROLL_BOTTOM" else "SCROLL_TOP",
                        "Reached ${if (toBottom) "bottom" else "top"} in $steps fast swipes",
                        steps > 0
                    )
                }
            }
            return true
        }

        fun stopAllGestures() {
            stopFlag.set(true)
            continuousJob?.cancel()
            continuousJob = null
            _isContinuousScrolling.value = false
            recordGesture("STOP", "All active gestures stopped immediately", true)
        }

        fun executeGestureIntent(intent: VoiceGestureIntent, context: Context? = null): AutomationOutcome {
            if (activeService == null && !intent.actionName.equals("STOP", ignoreCase = true)) {
                if (context != null) openAccessibilitySettings(context)
                recordGesture(intent.actionName.uppercase(), "Accessibility Service off", false)
                return AutomationOutcome(
                    false,
                    "Mahi Accessibility Service is off. Enable Mahi AI in Accessibility Settings to control the screen."
                )
            }
            return when (intent.actionName.uppercase()) {
                "STOP" -> {
                    stopAllGestures()
                    AutomationOutcome(true, "Stopped all screen gestures immediately", "STOP command executed")
                }
                "SWIPE_UP", "SWIPE_UP_SCROLL" -> {
                    val ok = swipeScreen(
                        SwipeDirection.UP,
                        intent.speed,
                        intent.targetIndex,
                        intent.targetText,
                        intent.startX,
                        intent.startY,
                        intent.endX,
                        intent.endY
                    )
                    AutomationOutcome(ok, if (ok) "Swiped UP (${intent.speed.displayName})" else "Enable Mahi Accessibility Service")
                }
                "SWIPE_DOWN", "SWIPE_DOWN_SCROLL" -> {
                    val ok = swipeScreen(
                        SwipeDirection.DOWN,
                        intent.speed,
                        intent.targetIndex,
                        intent.targetText,
                        intent.startX,
                        intent.startY,
                        intent.endX,
                        intent.endY
                    )
                    AutomationOutcome(ok, if (ok) "Swiped DOWN (${intent.speed.displayName})" else "Enable Mahi Accessibility Service")
                }
                "SWIPE_LEFT" -> {
                    val ok = swipeScreen(
                        SwipeDirection.LEFT,
                        intent.speed,
                        intent.targetIndex,
                        intent.targetText,
                        intent.startX,
                        intent.startY,
                        intent.endX,
                        intent.endY
                    )
                    AutomationOutcome(ok, if (ok) "Swiped LEFT" else "Enable Mahi Accessibility Service")
                }
                "SWIPE_RIGHT" -> {
                    val ok = swipeScreen(
                        SwipeDirection.RIGHT,
                        intent.speed,
                        intent.targetIndex,
                        intent.targetText,
                        intent.startX,
                        intent.startY,
                        intent.endX,
                        intent.endY
                    )
                    AutomationOutcome(ok, if (ok) "Swiped RIGHT" else "Enable Mahi Accessibility Service")
                }
                "FAST_SCROLL_DOWN" -> {
                    val ok = scrollScreen(true, GestureSpeed.FAST)
                    AutomationOutcome(ok, if (ok) "Fast scrolled downward" else "Enable Mahi Accessibility Service")
                }
                "FAST_SCROLL_UP" -> {
                    val ok = scrollScreen(false, GestureSpeed.FAST)
                    AutomationOutcome(ok, if (ok) "Fast scrolled upward" else "Enable Mahi Accessibility Service")
                }
                "CONTINUOUS_SCROLL_DOWN" -> {
                    val ok = startContinuousScroll(true, intent.speed)
                    AutomationOutcome(ok, if (ok) "Continuous scroll started (say STOP to halt)" else "Enable Mahi Accessibility Service")
                }
                "CONTINUOUS_SCROLL_UP" -> {
                    val ok = startContinuousScroll(false, intent.speed)
                    AutomationOutcome(ok, if (ok) "Continuous scroll up started (say STOP to halt)" else "Enable Mahi Accessibility Service")
                }
                "SCROLL_BOTTOM" -> {
                    val ok = scrollToEdge(toBottom = true)
                    AutomationOutcome(ok, if (ok) "Scrolling to bottom of page" else "Enable Mahi Accessibility Service")
                }
                "SCROLL_TOP" -> {
                    val ok = scrollToEdge(toBottom = false)
                    AutomationOutcome(ok, if (ok) "Scrolling to top of page" else "Enable Mahi Accessibility Service")
                }
                "TAP_TEXT", "TAP_INDEX" -> {
                    tapElement(
                        context = context,
                        index = intent.targetIndex,
                        query = intent.targetText.ifBlank { null },
                        x = intent.startX,
                        y = intent.startY
                    )
                }
                "DOUBLE_TAP" -> {
                    val ok = doubleTapElementOrCenter(intent.targetText)
                    AutomationOutcome(ok, if (ok) "Double-tapped target" else "Enable Mahi Accessibility Service")
                }
                "LONG_PRESS" -> {
                    val query = intent.targetText
                    val ok = longPressElementByText(query)
                    AutomationOutcome(ok, if (ok) "Long-pressed '$query'" else "Could not long-press '$query'")
                }
                else -> AutomationOutcome(false, "Unknown gesture '${intent.actionName}'")
            }
        }

        fun pressBack(): Boolean = activeService?.performGlobalAction(GLOBAL_ACTION_BACK) ?: false

        fun pressHome(): Boolean = activeService?.performGlobalAction(GLOBAL_ACTION_HOME) ?: false

        fun pressRecents(): Boolean = activeService?.performGlobalAction(GLOBAL_ACTION_RECENTS) ?: false

        fun openNotificationShade(): Boolean =
            activeService?.performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS) ?: false

        fun openQuickSettings(): Boolean =
            activeService?.performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS) ?: false

        fun takeScreenshotBitmap(onResult: (Result<Bitmap>) -> Unit) {
            val svc = activeService
            if (svc == null) {
                onResult(
                    Result.failure(
                        IllegalStateException(
                            "Enable Mahi Accessibility Service to capture the current screen."
                        )
                    )
                )
                return
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                onResult(
                    Result.failure(
                        UnsupportedOperationException(
                            "Accessibility screenshots require Android 11+ (API 30). Pick a screenshot from Gallery instead."
                        )
                    )
                )
                return
            }
            try {
                svc.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    svc.mainExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            val hw = screenshot.hardwareBuffer
                            val colorSpace = screenshot.colorSpace
                            try {
                                val wrapped = Bitmap.wrapHardwareBuffer(hw, colorSpace)
                                val soft = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                                hw.close()
                                if (soft != null) {
                                    recordGesture("SCREENSHOT", "Captured active screen via Accessibility", true)
                                    onResult(Result.success(soft))
                                } else {
                                    onResult(Result.failure(IllegalStateException("Screenshot buffer returned null bitmap.")))
                                }
                            } catch (e: Exception) {
                                try {
                                    hw.close()
                                } catch (_: Exception) {
                                }
                                onResult(Result.failure(e))
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            val msg = "Android screenshot failed (code=$errorCode). Some banking/DRM apps block capture."
                            recordGesture("SCREENSHOT", msg, false)
                            onResult(Result.failure(IllegalStateException(msg)))
                        }
                    }
                )
            } catch (e: Exception) {
                onResult(Result.failure(e))
            }
        }

        fun captureScreenJpeg(
            maxDim: Int = 1024,
            quality: Int = 72,
            onResult: (Result<ByteArray>) -> Unit
        ) {
            takeScreenshotBitmap { res ->
                res.onSuccess { bmp ->
                    try {
                        val scaled = VisionBitmapUtils.scaleDown(bmp, maxDim)
                        val out = ByteArrayOutputStream()
                        scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
                        onResult(Result.success(out.toByteArray()))
                    } catch (e: Exception) {
                        onResult(Result.failure(e))
                    }
                }.onFailure { err ->
                    onResult(Result.failure(err))
                }
            }
        }
    }

    @Volatile
    private var lastCaptureMs: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeService = this
        _isConnected.value = true
        _gestureStatus.value = "Connected — Accessibility Gesture Engine Ready"
        captureCurrentScreen(force = true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        captureCurrentScreen(force = false)
    }

    override fun onInterrupt() {
        stopAllGestures()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (activeService === this) {
            stopAllGestures()
            activeService = null
        }
        _isConnected.value = false
    }

    fun captureCurrentScreen(force: Boolean = true): ScreenSnapshot? {
        val now = System.currentTimeMillis()
        if (!force && (now - lastCaptureMs) < 180L) {
            return _screenSnapshot.value
        }
        lastCaptureMs = now

        val roots = getCandidateRoots()
        val root = roots.firstOrNull() ?: return null
        val pkg = root.packageName?.toString().orEmpty()
        val collected = mutableListOf<ScreenElementNode>()
        roots.forEach { windowRoot ->
            if (collected.size < 60) {
                traverseNode(windowRoot, collected, depth = 0)
            }
        }
        val snapshot = ScreenSnapshot(
            packageName = pkg,
            elements = collected.take(60),
            capturedAtMs = now
        )
        _screenSnapshot.value = snapshot
        return snapshot
    }

    private fun getCandidateRoots(): List<AccessibilityNodeInfo> {
        val list = mutableListOf<AccessibilityNodeInfo>()
        rootInActiveWindow?.let { list.add(it) }
        try {
            windows?.forEach { window ->
                val wRoot = window.root
                if (wRoot != null && wRoot != rootInActiveWindow) {
                    list.add(wRoot)
                }
            }
        } catch (_: Exception) {
        }
        return list
    }

    private fun traverseNode(
        node: AccessibilityNodeInfo,
        out: MutableList<ScreenElementNode>,
        depth: Int,
        liveNodesOut: MutableList<AccessibilityNodeInfo>? = null
    ) {
        if (depth > 14 || out.size >= 60) return
        if (node.isPassword) return

        val rect = Rect()
        node.getBoundsInScreen(rect)
        val rawText = node.text?.toString()?.trim().orEmpty()
        val rawDesc = node.contentDescription?.toString()?.trim().orEmpty()
        val text = NotificationUnderstandingManager.redactSensitiveText(rawText).first
        val desc = NotificationUnderstandingManager.redactSensitiveText(rawDesc).first
        val resId = node.viewIdResourceName.orEmpty()

        if (node.isVisibleToUser &&
            (text.isNotBlank() || desc.isNotBlank() || node.isClickable || node.isScrollable || node.isEditable)
        ) {
            val nextIdx = out.size + 1
            out.add(
                ScreenElementNode(
                    id = "node_${out.size}",
                    text = text,
                    contentDescription = desc,
                    className = node.className?.toString()?.substringAfterLast(".").orEmpty(),
                    viewIdResourceName = resId,
                    isClickable = node.isClickable,
                    isLongClickable = node.isLongClickable,
                    isScrollable = node.isScrollable,
                    isEditable = node.isEditable,
                    bounds = rect,
                    index = nextIdx,
                    isPassword = node.isPassword,
                    isFocused = node.isFocused
                )
            )
            liveNodesOut?.add(node)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverseNode(child, out, depth + 1, liveNodesOut)
        }
    }

    private fun collectLiveIndexedNodes(): List<AccessibilityNodeInfo> {
        val dummy = mutableListOf<ScreenElementNode>()
        val live = mutableListOf<AccessibilityNodeInfo>()
        getCandidateRoots().forEach { windowRoot ->
            if (dummy.size < 60) {
                traverseNode(windowRoot, dummy, 0, live)
            }
        }
        return live
    }

    private fun findNodeByViewId(viewId: String): AccessibilityNodeInfo? {
        val target = viewId.trim().lowercase()
        if (target.isBlank()) return null
        return collectLiveIndexedNodes().firstOrNull { node ->
            val id = node.viewIdResourceName?.lowercase().orEmpty()
            id == target || id.endsWith("/$target") || id.contains(target)
        }
    }

    private fun performUnifiedTap(
        index: Int?,
        query: String?,
        viewId: String?,
        x: Float?,
        y: Float?
    ): Boolean {
        val liveNodes = collectLiveIndexedNodes()
        val targetNode: AccessibilityNodeInfo? = when {
            index != null && index in 1..liveNodes.size -> liveNodes[index - 1]
            !viewId.isNullOrBlank() -> findNodeByViewId(viewId)
            !query.isNullOrBlank() -> findMatchingNodeAcrossWindows(query)
            else -> null
        }

        if (targetNode != null) {
            var cursor: AccessibilityNodeInfo? = targetNode
            while (cursor != null) {
                if (cursor.isClickable && cursor.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true
                }
                cursor = cursor.parent
            }
            val rect = Rect()
            targetNode.getBoundsInScreen(rect)
            if (!rect.isEmpty) {
                return dispatchSingleTap(rect.exactCenterX(), rect.exactCenterY())
            }
        }

        if (x != null && y != null) {
            return dispatchSingleTap(x, y)
        }
        return false
    }

    private fun dispatchSingleTap(cx: Float, cy: Float): Boolean {
        val path = Path().apply { moveTo(cx, cy) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 60L))
            .build()
        return dispatchGestureWithCallback(gesture, "Tap ($cx,$cy)")
    }

    private fun performTypeText(
        context: Context,
        index: Int?,
        query: String?,
        text: String,
        append: Boolean,
        clearFirst: Boolean,
        submit: Boolean
    ): AutomationOutcome {
        val liveNodes = collectLiveIndexedNodes()
        val indexedCandidate = if (index != null && index in 1..liveNodes.size) liveNodes[index - 1] else null
        val queryCandidate = if (!query.isNullOrBlank()) findMatchingNodeAcrossWindows(query) else null
        val focusedInput = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

        val candidate = indexedCandidate ?: queryCandidate ?: focusedInput ?: liveNodes.firstOrNull { it.isEditable }
        val editable = findEditableTarget(candidate)
            ?: liveNodes.firstOrNull { it.isEditable }
            ?: return AutomationOutcome(false, "No editable text box found on the current screen.")

        if (editable.isPassword) {
            return AutomationOutcome(false, "Refused to read or type into a password field for security.")
        }

        editable.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        editable.performAction(AccessibilityNodeInfo.ACTION_CLICK)

        val finalText = when {
            clearFirst && text.isEmpty() -> ""
            append && !clearFirst -> {
                val existing = editable.text?.toString().orEmpty()
                if (existing.isBlank()) text else "$existing$text"
            }
            else -> text
        }

        val bundle = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, finalText)
        }
        var setOk = editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)

        if (!setOk) {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("Mahi Input", finalText))
                setOk = editable.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            }
        }

        if (!setOk) {
            return AutomationOutcome(false, "Active input box blocked Accessibility text entry.")
        }

        var submitNote = ""
        if (submit) {
            var submitted = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                submitted = editable.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            }
            if (!submitted) {
                val submitLabels = listOf("search", "send", "go", "submit", "next", "done")
                val submitNode = collectLiveIndexedNodes().firstOrNull { node ->
                    val label = (node.text?.toString() ?: node.contentDescription?.toString() ?: "").lowercase()
                    node.isClickable && !node.isEditable && submitLabels.any { label == it || label.contains(it) }
                }
                if (submitNode != null) {
                    submitted = submitNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
            }
            submitNote = if (submitted) " and submitted" else " (submit button not found)"
        }

        return AutomationOutcome(
            success = true,
            message = "Typed \"$finalText\"$submitNote.",
            verificationDetail = "Text inserted into editable field$submitNote"
        )
    }

    private fun findEditableTarget(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findEditableTarget(child)?.let { return it }
        }
        var p = node.parent
        var hops = 0
        while (p != null && hops < 4) {
            if (p.isEditable) return p
            p = p.parent
            hops++
        }
        return null
    }

    private fun findMatchingNodeAcrossWindows(query: String): AccessibilityNodeInfo? {
        val lowerQuery = query.trim().lowercase()
        if (lowerQuery.isBlank()) return null
        for (root in getCandidateRoots()) {
            val match = findNodeRecursive(root, lowerQuery)
            if (match != null) return match
        }
        return null
    }

    private fun dispatchGestureWithCallback(gesture: GestureDescription, label: String): Boolean {
        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                _gestureStatus.value = "$label completed"
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                _gestureStatus.value = "$label cancelled"
            }
        }
        return dispatchGesture(gesture, callback, null)
    }

    private fun performActionOnMatchingNode(query: String, longClick: Boolean): Boolean {
        val target = findMatchingNodeAcrossWindows(query) ?: return false
        val action = if (longClick) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK

        var cursor: AccessibilityNodeInfo? = target
        while (cursor != null) {
            val canAct = if (longClick) cursor.isLongClickable else cursor.isClickable
            if (canAct && cursor.performAction(action)) {
                captureCurrentScreen(force = true)
                return true
            }
            cursor = cursor.parent
        }

        // Coordinate fallback if target node has valid bounds even when not flagged clickable
        val rect = Rect()
        target.getBoundsInScreen(rect)
        if (!rect.isEmpty) {
            val path = Path().apply { moveTo(rect.exactCenterX(), rect.exactCenterY()) }
            val strokeDuration = if (longClick) 650L else 60L
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, strokeDuration))
                .build()
            return dispatchGestureWithCallback(gesture, if (longClick) "Long press '$query'" else "Tap '$query'")
        }
        return false
    }

    private fun performDoubleTap(query: String): Boolean {
        val cx: Float
        val cy: Float
        val target = if (query.isNotBlank()) findMatchingNodeAcrossWindows(query) else null

        if (target != null) {
            val r = Rect()
            target.getBoundsInScreen(r)
            cx = r.exactCenterX()
            cy = r.exactCenterY()
        } else {
            val dm = resources.displayMetrics
            cx = dm.widthPixels * 0.5f
            cy = dm.heightPixels * 0.5f
        }

        val p1 = Path().apply { moveTo(cx, cy) }
        val p2 = Path().apply { moveTo(cx, cy) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p1, 0L, 45L))
            .addStroke(GestureDescription.StrokeDescription(p2, 110L, 45L))
            .build()
        return dispatchGestureWithCallback(gesture, "Double tap")
    }

    private fun performDragBetweenNodes(fromQuery: String, toQuery: String): Boolean {
        val fromNode = findMatchingNodeAcrossWindows(fromQuery) ?: return false
        val toNode = findMatchingNodeAcrossWindows(toQuery) ?: return false
        val r1 = Rect()
        val r2 = Rect()
        fromNode.getBoundsInScreen(r1)
        toNode.getBoundsInScreen(r2)
        if (r1.isEmpty || r2.isEmpty) return false
        return performDragCoordinates(r1.exactCenterX(), r1.exactCenterY(), r2.exactCenterX(), r2.exactCenterY(), 550L)
    }

    private fun performDragCoordinates(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long
    ): Boolean {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
            .build()
        return dispatchGestureWithCallback(gesture, "Drag")
    }

    private fun findNodeRecursive(node: AccessibilityNodeInfo, lowerQuery: String): AccessibilityNodeInfo? {
        if (lowerQuery.isBlank()) return null
        val t = node.text?.toString()?.lowercase().orEmpty()
        val d = node.contentDescription?.toString()?.lowercase().orEmpty()
        val id = node.viewIdResourceName?.lowercase().orEmpty()
        if (node.isVisibleToUser &&
            (t.contains(lowerQuery) || d.contains(lowerQuery) || id.endsWith(lowerQuery)) &&
            (t.isNotBlank() || d.isNotBlank() || id.isNotBlank())
        ) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val match = findNodeRecursive(child, lowerQuery)
            if (match != null) return match
        }
        if (!node.isVisibleToUser &&
            (t.contains(lowerQuery) || d.contains(lowerQuery) || id.endsWith(lowerQuery)) &&
            (t.isNotBlank() || d.isNotBlank() || id.isNotBlank())
        ) {
            return node
        }
        return null
    }

    private fun performScrollOnScreen(forward: Boolean, speed: GestureSpeed): Boolean {
        val scrollable = getCandidateRoots().firstNotNullOfOrNull { findLargestScrollableNode(it) }
        if (speed == GestureSpeed.NORMAL && scrollable != null) {
            val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            if (scrollable.performAction(action)) {
                captureCurrentScreen(force = true)
                return true
            }
        }
        return performSwipeGesture(
            direction = if (forward) SwipeDirection.UP else SwipeDirection.DOWN,
            speed = speed,
            useScrollableBounds = true
        )
    }

    private fun findLargestScrollableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val scrollables = mutableListOf<AccessibilityNodeInfo>()
        collectScrollables(root, scrollables, 0)
        return scrollables.filter { it.isVisibleToUser }.maxByOrNull { node ->
            val r = Rect()
            node.getBoundsInScreen(r)
            r.width().toLong() * r.height().toLong()
        } ?: scrollables.maxByOrNull { node ->
            val r = Rect()
            node.getBoundsInScreen(r)
            r.width().toLong() * r.height().toLong()
        }
    }

    private fun collectScrollables(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>, depth: Int) {
        if (depth > 14) return
        if (node.isScrollable) out.add(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectScrollables(child, out, depth + 1)
        }
    }

    private fun performSwipeGesture(
        direction: SwipeDirection,
        speed: GestureSpeed = GestureSpeed.NORMAL,
        useScrollableBounds: Boolean = true,
        targetIndex: Int? = null,
        targetQuery: String = ""
    ): Boolean {
        val dm = resources.displayMetrics
        val fullBounds = Rect(0, 0, dm.widthPixels, dm.heightPixels)
        val liveNodes = collectLiveIndexedNodes()
        val explicitTarget = when {
            targetIndex != null && targetIndex in 1..liveNodes.size -> liveNodes[targetIndex - 1]
            targetQuery.isNotBlank() -> findMatchingNodeAcrossWindows(targetQuery)
            else -> null
        }

        val targetBounds = when {
            explicitTarget != null -> {
                val r = Rect()
                explicitTarget.getBoundsInScreen(r)
                if (!r.isEmpty && r.width() > 60 && r.height() > 60) r else fullBounds
            }
            useScrollableBounds -> {
                val scrollable = getCandidateRoots().firstNotNullOfOrNull { findLargestScrollableNode(it) }
                if (scrollable != null) {
                    val r = Rect()
                    scrollable.getBoundsInScreen(r)
                    if (!r.isEmpty && r.width() > 80 && r.height() > 80) r else fullBounds
                } else fullBounds
            }
            else -> fullBounds
        }

        val seg = computeSwipeSegment(targetBounds, direction)
        val path = Path().apply {
            moveTo(seg.startX, seg.startY)
            lineTo(seg.endX, seg.endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, speed.durationMs))
            .build()
        return dispatchGestureWithCallback(gesture, "Swipe ${direction.name}")
    }
}
