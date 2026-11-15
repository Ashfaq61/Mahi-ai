package com.example.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.LinearLayout
import android.widget.TextView
import com.example.vision.NotificationUnderstandingManager
import com.example.vision.VisionBitmapUtils
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.math.abs

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
    val isFocused: Boolean = false,
    val isSelected: Boolean = false,
    val isChecked: Boolean = false,
    val hintText: String = "",
    val stateDescription: String = "",
    val tooltipText: String = "",
    val region: String = "content",
    val fingerprint: String = ""
) {
    val centerX: Int get() = bounds.centerX()
    val centerY: Int get() = bounds.centerY()

    val bestLabel: String
        get() = text
            .ifBlank { contentDescription }
            .ifBlank { hintText }
            .ifBlank { tooltipText }
            .ifBlank { viewIdResourceName.substringAfterLast("/") }

    fun toIndexedLine(): String {
        val flags = buildList {
            if (isClickable) add("clickable")
            if (isEditable) add("editable")
            if (isScrollable) add("scrollable")
            if (isFocused) add("focused")
            if (isSelected) add("selected")
            if (isChecked) add("checked")
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
    val capturedAtMs: Long = 0L,
    val snapshotId: String = "",
    val isKeyboardOpen: Boolean = false,
    val summaryLine: String = "",
    val rawNodeCount: Int = 0,
    val imeBounds: Rect = Rect()
) {
    val readableSummary: String
        get() {
            if (elements.isEmpty()) return "No screen elements captured yet. Enable Mahi Accessibility Service to inspect active apps."
            val lines = elements.take(80).joinToString("\n") { it.toIndexedLine() }
            val header = if (summaryLine.isNotBlank()) "Summary: $summaryLine\n" else ""
            return "${header}App: $packageName | Visible elements (${elements.size}):\n$lines"
        }

    fun signatureKey(): String = elements.take(40).joinToString("|") {
        "${it.index}:${it.bestLabel.take(24)}:${it.bounds.top}:${it.isChecked}:${it.isSelected}"
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

        @Volatile
        var isTestPlaygroundActive: Boolean = false

        @Volatile
        private var lastTargetPackage: String = ""

        private val stopFlag = AtomicBoolean(false)
        private val fallbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        private var continuousJob: Job? = null
        private val temporaryObserveCount = AtomicInteger(0)
        private val lastWindowEventMs = AtomicLong(0L)
        private val lastGestureEndMs = AtomicLong(0L)
        private val gestureSpacingMutex = Mutex()
        private val mainHandler = Handler(Looper.getMainLooper())

        private const val MAX_SNAPSHOT_CACHE = 5
        private val snapshotCacheLock = Any()
        private val snapshotHistory = LinkedHashMap<String, Pair<ScreenSnapshot, List<AccessibilityNodeInfo?>>>()

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

        private val _isAutopilotObserving = MutableStateFlow(false)
        val isAutopilotObserving: StateFlow<Boolean> = _isAutopilotObserving.asStateFlow()

        private val _isWatchModeObserving = MutableStateFlow(false)
        val isWatchModeObserving: StateFlow<Boolean> = _isWatchModeObserving.asStateFlow()

        private val _isAutopilotOverlayVisible = MutableStateFlow(false)
        val isAutopilotOverlayVisible: StateFlow<Boolean> = _isAutopilotOverlayVisible.asStateFlow()

        private val _screenChangeEvents = MutableSharedFlow<ScreenSnapshot>(extraBufferCapacity = 8)
        val screenChangeEvents: SharedFlow<ScreenSnapshot> = _screenChangeEvents.asSharedFlow()

        var onAutopilotOverlayStopRequested: (() -> Unit)? = null

        fun getLastTargetPackage(): String = lastTargetPackage

        fun setAutopilotObserving(active: Boolean) {
            _isAutopilotObserving.value = active
            if (!active && !_isWatchModeObserving.value) {
                activeService?.debounceWatchJob?.cancel()
                activeService?.debounceWatchJob = null
                hideAutopilotOverlay()
            } else if (active) {
                activeService?.captureCurrentScreen(force = true)
            }
        }

        fun setWatchModeObserving(active: Boolean) {
            _isWatchModeObserving.value = active
            if (!active && !_isAutopilotObserving.value) {
                activeService?.debounceWatchJob?.cancel()
                activeService?.debounceWatchJob = null
                hideAutopilotOverlay()
            } else if (active) {
                activeService?.captureCurrentScreen(force = true)
            }
        }

        fun showAutopilotOverlay(stepText: String, titleText: String = "Mahi is controlling screen") {
            val svc = activeService ?: return
            fallbackScope.launch(Dispatchers.Main) {
                svc.showOrUpdateAutopilotOverlayOnMain(stepText, titleText)
            }
        }

        fun hideAutopilotOverlay() {
            _isAutopilotOverlayVisible.value = false
            val svc = activeService ?: return
            fallbackScope.launch(Dispatchers.Main) {
                svc.removeAutopilotOverlayOnMain()
            }
        }

        fun isDeviceInteractiveAndUnlocked(context: Context): Boolean {
            return try {
                val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                val interactive = pm?.isInteractive ?: true
                val locked = km?.isKeyguardLocked ?: false
                interactive && !locked
            } catch (_: Exception) {
                true
            }
        }

        fun hideSoftKeyboardIfOpen(): Boolean {
            val svc = activeService ?: return false
            return try {
                val hasIme = svc.windows?.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } == true
                if (hasIme) {
                    val closed = svc.performGlobalAction(GLOBAL_ACTION_BACK)
                    if (closed) recordGesture("HIDE_KEYBOARD", "Dismissed keyboard covering target", true)
                    closed
                } else {
                    false
                }
            } catch (_: Exception) {
                false
            }
        }

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

        fun computeSwipeSegment(
            bounds: Rect,
            direction: SwipeDirection,
            screenWidth: Int,
            screenHeight: Int,
            imeBounds: Rect? = null
        ): GestureSegment {
            val safeZone = ScreenReaderV2.computeSwipeSafeBounds(screenWidth, screenHeight, imeBounds)
            val clipped = Rect(
                maxOf(bounds.left, safeZone.left),
                maxOf(bounds.top, safeZone.top),
                minOf(bounds.right, safeZone.right),
                minOf(bounds.bottom, safeZone.bottom)
            )
            val effective = if (clipped.width() >= 90 && clipped.height() >= 90) clipped else safeZone
            return computeSwipeSegment(effective, direction)
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

        suspend fun awaitSettled(maxWaitMs: Long = 3000L, quietWindowMs: Long = 400L): ScreenSnapshot? {
            val svc = activeService ?: return null
            temporaryObserveCount.incrementAndGet()
            return try {
                val start = System.currentTimeMillis()
                lastWindowEventMs.set(start)
                delay(140L)
                while (System.currentTimeMillis() - start < maxWaitMs) {
                    val sinceLastEvent = System.currentTimeMillis() - lastWindowEventMs.get()
                    if (sinceLastEvent >= quietWindowMs) break
                    delay(65L)
                }
                svc.captureCurrentScreen(force = true)
            } finally {
                temporaryObserveCount.decrementAndGet()
            }
        }

        private fun cacheSnapshotWithNodes(
            snapshot: ScreenSnapshot,
            liveNodes: List<AccessibilityNodeInfo?>
        ) {
            if (snapshot.snapshotId.isBlank()) return
            synchronized(snapshotCacheLock) {
                snapshotHistory[snapshot.snapshotId] = snapshot to liveNodes
                while (snapshotHistory.size > MAX_SNAPSHOT_CACHE) {
                    val oldestKey = snapshotHistory.keys.firstOrNull() ?: break
                    snapshotHistory.remove(oldestKey)
                }
            }
        }

        private fun lookupCachedElement(
            snapshotId: String?,
            index: Int?,
            elementId: String?
        ): ScreenElementNode? {
            synchronized(snapshotCacheLock) {
                val pair = if (!snapshotId.isNullOrBlank()) {
                    snapshotHistory[snapshotId]
                } else {
                    snapshotHistory.values.lastOrNull()
                } ?: return null
                val snap = pair.first
                return when {
                    !elementId.isNullOrBlank() -> snap.elements.firstOrNull { it.id == elementId }
                    index != null && index > 0 -> snap.elements.firstOrNull { it.index == index }
                    else -> null
                }
            }
        }

        private suspend fun ensureTargetAppForeground(context: Context): ScreenActionResult? {
            val svc = activeService ?: return ScreenActionResult(
                success = false,
                failureReason = "Mahi Accessibility Service is off.",
                hint = "Enable Mahi AI in Accessibility Settings."
            )
            if (isTestPlaygroundActive) return null
            val currentPkg = svc.rootInActiveWindow?.packageName?.toString().orEmpty()
            if (currentPkg.isNotBlank() && currentPkg != context.packageName) {
                recordExternalPackageIfValid(context, currentPkg)
                return null
            }
            if (currentPkg == context.packageName && lastTargetPackage.isNotBlank()) {
                try {
                    val launch = context.packageManager.getLaunchIntentForPackage(lastTargetPackage)?.apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    }
                    if (launch != null) {
                        context.startActivity(launch)
                        val deadline = System.currentTimeMillis() + 1200L
                        while (System.currentTimeMillis() < deadline) {
                            delay(100L)
                            val nowPkg = svc.rootInActiveWindow?.packageName?.toString().orEmpty()
                            if (nowPkg.isNotBlank() && nowPkg != context.packageName) {
                                return null
                            }
                        }
                    }
                } catch (_: Exception) {
                }
                return ScreenActionResult(
                    success = false,
                    stale = true,
                    packageName = currentPkg,
                    failureReason = "Mahi is in the foreground instead of target app '$lastTargetPackage'.",
                    hint = "Switch back to $lastTargetPackage or launch it first."
                )
            }
            return null
        }

        private fun recordExternalPackageIfValid(context: Context, pkg: String) {
            if (pkg.isBlank()) return
            if (pkg == context.packageName || pkg == "com.android.systemui") return
            if (pkg.contains("launcher", ignoreCase = true) || pkg.contains("inputmethod", ignoreCase = true)) return
            lastTargetPackage = pkg
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
                ensureTargetAppForeground(context)
                var snap = svc.captureCurrentScreen(force = true)
                var attempts = 0
                while ((snap == null || snap.elements.isEmpty()) && attempts < 3) {
                    delay(200L)
                    snap = svc.captureCurrentScreen(force = true)
                    attempts++
                }
                if (snap == null || snap.elements.isEmpty()) {
                    recordGesture("READ_SCREEN", "No readable UI nodes exposed by ${snap?.packageName ?: "active app"}", false)
                    Result.failure(
                        IllegalStateException(
                            "This screen has no readable Accessibility text nodes (e.g. game/canvas/protected view). Use Set-of-Mark or captureScreenImage to inspect visually."
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

        suspend fun tapElementAwait(
            context: Context,
            index: Int? = null,
            query: String? = null,
            viewId: String? = null,
            elementId: String? = null,
            snapshotId: String? = null,
            x: Float? = null,
            y: Float? = null
        ): ScreenActionResult {
            val svc = activeService
            if (svc == null) {
                openAccessibilitySettings(context)
                recordGesture("TAP", "Accessibility Service off — cannot tap element", false)
                return ScreenActionResult(
                    success = false,
                    failureReason = "Mahi Accessibility Service is off.",
                    hint = "Enable Mahi AI in Accessibility Settings."
                )
            }
            ensureTargetAppForeground(context)?.let { guardFail ->
                recordGesture("TAP", guardFail.failureReason, false)
                return guardFail
            }
            val result = svc.performUnifiedTapV2(
                index = index,
                query = query,
                viewId = viewId,
                elementId = elementId,
                snapshotId = snapshotId,
                x = x,
                y = y
            )
            recordGesture(
                "TAP",
                if (result.success) "Tapped ${query ?: viewId ?: (index?.let { "#$it" } ?: "target")}" else result.failureReason,
                result.success
            )
            return result
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

        suspend fun typeTextAwait(
            context: Context,
            index: Int? = null,
            query: String? = null,
            elementId: String? = null,
            snapshotId: String? = null,
            text: String,
            append: Boolean = false,
            clearFirst: Boolean = false,
            submit: Boolean = false
        ): ScreenActionResult {
            if (text.isBlank() && !clearFirst) {
                return ScreenActionResult(false, failureReason = "Cannot type empty text unless clearFirst=true.")
            }
            val svc = activeService
            if (svc == null) {
                openAccessibilitySettings(context)
                recordGesture("TYPE_TEXT", "Accessibility Service off — opened Settings", false)
                return ScreenActionResult(
                    success = false,
                    failureReason = "Mahi Accessibility Service is off.",
                    hint = "Enable Mahi AI in Accessibility Settings."
                )
            }
            ensureTargetAppForeground(context)?.let { guardFail ->
                recordGesture("TYPE_TEXT", guardFail.failureReason, false)
                return guardFail
            }
            val result = svc.performTypeTextV2(
                context = context,
                index = index,
                query = query,
                elementId = elementId,
                snapshotId = snapshotId,
                text = text,
                append = append,
                clearFirst = clearFirst,
                submit = submit
            )
            recordGesture(
                "TYPE_TEXT",
                if (result.success) "Typed \"${result.readBackText ?: text}\"" else result.failureReason,
                result.success
            )
            return result
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

        suspend fun scrollScreenAwait(
            forward: Boolean,
            speed: GestureSpeed = GestureSpeed.NORMAL
        ): ScreenActionResult {
            val svc = activeService
            if (svc == null) {
                recordGesture("SCROLL", "Accessibility Service off — cannot scroll", false)
                return ScreenActionResult(
                    success = false,
                    failureReason = "Mahi Accessibility Service is off."
                )
            }
            val res = svc.performScrollOnScreenV2(forward, speed)
            val label = if (forward) "Scroll Down (${speed.displayName})" else "Scroll Up (${speed.displayName})"
            recordGesture("SCROLL", if (res.success) label else res.failureReason, res.success)
            return res
        }

        suspend fun swipeScreenAwait(
            direction: SwipeDirection,
            speed: GestureSpeed = GestureSpeed.NORMAL,
            targetIndex: Int? = null,
            targetQuery: String = "",
            startX: Float? = null,
            startY: Float? = null,
            endX: Float? = null,
            endY: Float? = null
        ): ScreenActionResult {
            val svc = activeService
            if (svc == null) {
                recordGesture("SWIPE", "Accessibility Service off — cannot swipe ${direction.name}", false)
                return ScreenActionResult(
                    success = false,
                    failureReason = "Mahi Accessibility Service is off."
                )
            }
            val res = svc.performSwipeGestureV2(
                direction = direction,
                speed = speed,
                targetIndex = targetIndex,
                targetQuery = targetQuery,
                startX = startX,
                startY = startY,
                endX = endX,
                endY = endY
            )
            recordGesture("SWIPE_${direction.name}", if (res.success) "Swiped ${direction.name}" else res.failureReason, res.success)
            return res
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
                var identicalCount = 0
                var prevSig = svc.captureCurrentScreen(force = true)?.signatureKey().orEmpty()
                while (isActive && !stopFlag.get() && steps < 40) {
                    val res = svc.performScrollOnScreenV2(forward, speed)
                    if (!res.success) break
                    steps++
                    val nextSig = svc.captureCurrentScreen(force = false)?.signatureKey().orEmpty()
                    if (nextSig == prevSig) {
                        identicalCount++
                        if (identicalCount >= 2) break
                    } else {
                        identicalCount = 0
                    }
                    prevSig = nextSig
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
                var identicalCount = 0
                var previousSig = svc.captureCurrentScreen(force = true)?.signatureKey().orEmpty()
                while (isActive && !stopFlag.get() && steps < 15) {
                    val res = svc.performSwipeGestureV2(
                        direction = if (toBottom) SwipeDirection.UP else SwipeDirection.DOWN,
                        speed = GestureSpeed.FAST
                    )
                    if (!res.success) break
                    steps++
                    val nextSig = svc.captureCurrentScreen(force = true)?.signatureKey().orEmpty()
                    if (nextSig == previousSig) {
                        identicalCount++
                        if (identicalCount >= 2) break
                    } else {
                        identicalCount = 0
                    }
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

        suspend fun executeGestureIntentAwait(intent: VoiceGestureIntent, context: Context? = null): ScreenActionResult {
            if (activeService == null && !intent.actionName.equals("STOP", ignoreCase = true)) {
                if (context != null) openAccessibilitySettings(context)
                recordGesture(intent.actionName.uppercase(), "Accessibility Service off", false)
                return ScreenActionResult(
                    success = false,
                    failureReason = "Mahi Accessibility Service is off.",
                    hint = "Enable Mahi AI in Accessibility Settings."
                )
            }
            return when (intent.actionName.uppercase()) {
                "STOP" -> {
                    stopAllGestures()
                    ScreenActionResult(true, changed = false, newSummary = "Stopped all screen gestures immediately.")
                }
                "SWIPE_UP", "SWIPE_UP_SCROLL" -> swipeScreenAwait(
                    SwipeDirection.UP, intent.speed, intent.targetIndex, intent.targetText,
                    intent.startX, intent.startY, intent.endX, intent.endY
                )
                "SWIPE_DOWN", "SWIPE_DOWN_SCROLL" -> swipeScreenAwait(
                    SwipeDirection.DOWN, intent.speed, intent.targetIndex, intent.targetText,
                    intent.startX, intent.startY, intent.endX, intent.endY
                )
                "SWIPE_LEFT" -> swipeScreenAwait(
                    SwipeDirection.LEFT, intent.speed, intent.targetIndex, intent.targetText,
                    intent.startX, intent.startY, intent.endX, intent.endY
                )
                "SWIPE_RIGHT" -> swipeScreenAwait(
                    SwipeDirection.RIGHT, intent.speed, intent.targetIndex, intent.targetText,
                    intent.startX, intent.startY, intent.endX, intent.endY
                )
                "FAST_SCROLL_DOWN" -> scrollScreenAwait(true, GestureSpeed.FAST)
                "FAST_SCROLL_UP" -> scrollScreenAwait(false, GestureSpeed.FAST)
                "CONTINUOUS_SCROLL_DOWN" -> {
                    val ok = startContinuousScroll(true, intent.speed)
                    ScreenActionResult(ok, changed = ok, newSummary = "Continuous scroll started (say STOP to halt)")
                }
                "CONTINUOUS_SCROLL_UP" -> {
                    val ok = startContinuousScroll(false, intent.speed)
                    ScreenActionResult(ok, changed = ok, newSummary = "Continuous scroll up started (say STOP to halt)")
                }
                "SCROLL_BOTTOM" -> {
                    val ok = scrollToEdge(toBottom = true)
                    ScreenActionResult(ok, changed = ok, newSummary = "Scrolling to bottom of page")
                }
                "SCROLL_TOP" -> {
                    val ok = scrollToEdge(toBottom = false)
                    ScreenActionResult(ok, changed = ok, newSummary = "Scrolling to top of page")
                }
                "TAP_TEXT", "TAP_INDEX" -> tapElementAwait(
                    context = context ?: activeService ?: return ScreenActionResult(false, failureReason = "Context unavailable"),
                    index = intent.targetIndex,
                    query = intent.targetText.ifBlank { null },
                    x = intent.startX,
                    y = intent.startY
                )
                "DOUBLE_TAP" -> {
                    val ok = activeService?.performDoubleTapAwait(intent.targetText) ?: false
                    recordGesture("DOUBLE_TAP", if (ok) "Double-tapped target" else "Double tap failed", ok)
                    val snap = awaitSettled(1200L, 250L)
                    ScreenActionResult(ok, changed = ok, packageName = snap?.packageName.orEmpty(), newSummary = snap?.summaryLine.orEmpty())
                }
                "LONG_PRESS" -> {
                    val ok = activeService?.performActionOnMatchingNodeAwait(intent.targetText, longClick = true) ?: false
                    recordGesture("LONG_PRESS", if (ok) "Long-pressed '${intent.targetText}'" else "Could not long-press '${intent.targetText}'", ok)
                    val snap = awaitSettled(1200L, 250L)
                    ScreenActionResult(ok, changed = ok, packageName = snap?.packageName.orEmpty(), newSummary = snap?.summaryLine.orEmpty())
                }
                else -> ScreenActionResult(false, failureReason = "Unknown gesture '${intent.actionName}'")
            }
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

        fun navigateBack(context: Context): AutomationOutcome {
            val svc = activeService
            if (svc == null) {
                openAccessibilitySettings(context)
                recordGesture("BACK", "Accessibility Service off — cannot go back", false)
                return AutomationOutcome(false, "Enable Mahi Accessibility Service to navigate back.")
            }
            val ok = svc.performGlobalAction(GLOBAL_ACTION_BACK)
            recordGesture("BACK", if (ok) "Navigated Back" else "Back action failed", ok)
            if (ok) svc.captureCurrentScreen(force = true)
            return AutomationOutcome(ok, if (ok) "Pressed Back." else "Back action failed.")
        }

        fun getDisplaySize(context: Context): Pair<Int, Int> {
            val dm = (activeService ?: context).resources.displayMetrics
            return dm.widthPixels.coerceAtLeast(1080) to dm.heightPixels.coerceAtLeast(1920)
        }

        fun pressHome(): Boolean = activeService?.performGlobalAction(GLOBAL_ACTION_HOME) ?: false

        fun pressRecents(): Boolean = activeService?.performGlobalAction(GLOBAL_ACTION_RECENTS) ?: false

        fun openNotificationShade(): Boolean =
            activeService?.performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS) ?: false

        fun openQuickSettings(): Boolean =
            activeService?.performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS) ?: false

        suspend fun captureScreenBitmapSuspend(): Result<Bitmap> = suspendCancellableCoroutine { cont ->
            takeScreenshotBitmap { res ->
                if (cont.isActive) cont.resume(res)
            }
        }

        suspend fun captureScreenJpegSuspend(maxDim: Int = 768, quality: Int = 72): Result<ByteArray> =
            suspendCancellableCoroutine { cont ->
                captureScreenJpeg(maxDim = maxDim, quality = quality) { res ->
                    if (cont.isActive) cont.resume(res)
                }
            }

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

    private var debounceWatchJob: Job? = null
    private var overlayRootView: LinearLayout? = null
    private var overlayTitleView: TextView? = null
    private var overlayStepView: TextView? = null
    private var overlayLayoutParams: WindowManager.LayoutParams? = null

    @Volatile
    private var latestLiveNodes: List<AccessibilityNodeInfo?> = emptyList()

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeService = this
        _isConnected.value = true
        _gestureStatus.value = "Connected — Screen Engine V2 Ready"
        if (_isAutopilotObserving.value || _isWatchModeObserving.value) {
            captureCurrentScreen(force = true)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val isTemp = temporaryObserveCount.get() > 0
        val isObserving = _isAutopilotObserving.value || _isWatchModeObserving.value
        // Privacy & battery rule: NEVER watch the screen outside Autopilot, Watch Mode, or an active command settle check.
        if (!isObserving && !isTemp) return
        val type = event?.eventType ?: return
        val relevant = type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            type == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
            type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ||
            type == AccessibilityEvent.TYPE_VIEW_FOCUSED
        if (!relevant) return

        lastWindowEventMs.set(System.currentTimeMillis())

        if (!isObserving) return
        debounceWatchJob?.cancel()
        debounceWatchJob = fallbackScope.launch {
            delay(500L)
            if (_isAutopilotObserving.value || _isWatchModeObserving.value) {
                captureCurrentScreen(force = true)?.let { snap ->
                    _screenChangeEvents.tryEmit(snap)
                }
            }
        }
    }

    override fun onInterrupt() {
        removeAutopilotOverlayOnMain()
        setAutopilotObserving(false)
        setWatchModeObserving(false)
        stopAllGestures()
    }

    override fun onDestroy() {
        removeAutopilotOverlayOnMain()
        super.onDestroy()
        if (activeService === this) {
            setAutopilotObserving(false)
            setWatchModeObserving(false)
            stopAllGestures()
            activeService = null
        }
        _isConnected.value = false
    }

    private fun dpToPx(dp: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics).toInt()

    private fun showOrUpdateAutopilotOverlayOnMain(
        stepText: String,
        titleText: String = "Mahi is controlling screen"
    ) {
        val cleanStep = stepText.ifBlank { "Executing verified screen action..." }.take(64)
        val cleanTitle = titleText.ifBlank { "Mahi is controlling screen" }.take(36)
        val existingStepView = overlayStepView
        if (overlayRootView != null && existingStepView != null) {
            overlayTitleView?.text = cleanTitle
            existingStepView.text = cleanStep
            _isAutopilotOverlayVisible.value = true
            return
        }
        try {
            val wm = getSystemService(WINDOW_SERVICE) as? WindowManager ?: return
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dpToPx(14), dpToPx(8), dpToPx(8), dpToPx(8))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dpToPx(28).toFloat()
                    setColor(Color.parseColor("#EB0A1020"))
                    setStroke(dpToPx(2), Color.parseColor("#FF00F0FF"))
                }
                elevation = dpToPx(10).toFloat()
            }

            val dotView = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dpToPx(10), dpToPx(10)).apply {
                    marginEnd = dpToPx(8)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#FF00F0FF"))
                }
            }

            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    dpToPx(176),
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = dpToPx(10)
                }
            }

            val titleTv = TextView(this).apply {
                text = cleanTitle
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTypeface(typeface, Typeface.BOLD)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }

            val stepTv = TextView(this).apply {
                text = cleanStep
                setTextColor(Color.parseColor("#FF7FF8FF"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }

            textCol.addView(titleTv)
            textCol.addView(stepTv)

            val stopBtn = TextView(this).apply {
                text = "STOP"
                contentDescription = "Stop Mahi Autopilot"
                gravity = Gravity.CENTER
                minWidth = dpToPx(60)
                minHeight = dpToPx(48)
                setPadding(dpToPx(14), dpToPx(8), dpToPx(14), dpToPx(8))
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTypeface(typeface, Typeface.BOLD)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dpToPx(20).toFloat()
                    setColor(Color.parseColor("#FFFF2A85"))
                }
                setOnClickListener {
                    hideAutopilotOverlay()
                    setAutopilotObserving(false)
                    setWatchModeObserving(false)
                    stopAllGestures()
                    onAutopilotOverlayStopRequested?.invoke()
                }
            }

            container.addView(dotView)
            container.addView(textCol)
            container.addView(stopBtn)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = dpToPx(40)
            }

            wm.addView(container, params)
            overlayRootView = container
            overlayTitleView = titleTv
            overlayStepView = stepTv
            overlayLayoutParams = params
            _isAutopilotOverlayVisible.value = true
        } catch (_: Exception) {
            overlayRootView = null
            overlayTitleView = null
            overlayStepView = null
            overlayLayoutParams = null
            _isAutopilotOverlayVisible.value = false
        }
    }

    private fun removeAutopilotOverlayOnMain() {
        _isAutopilotOverlayVisible.value = false
        val view = overlayRootView ?: return
        overlayRootView = null
        overlayTitleView = null
        overlayStepView = null
        overlayLayoutParams = null
        try {
            val wm = getSystemService(WINDOW_SERVICE) as? WindowManager
            wm?.removeViewImmediate(view)
        } catch (_: Exception) {
        }
    }

    fun captureCurrentScreen(force: Boolean = true): ScreenSnapshot? {
        val now = System.currentTimeMillis()
        if (!force && (now - lastCaptureMs) < 160L && _screenSnapshot.value.elements.isNotEmpty()) {
            return _screenSnapshot.value
        }
        lastCaptureMs = now

        val dm = resources.displayMetrics
        val w = dm.widthPixels.coerceAtLeast(1080)
        val h = dm.heightPixels.coerceAtLeast(1920)
        val winList = try {
            windows
        } catch (_: Exception) {
            null
        }
        val roots = ScreenReaderV2.collectCandidateRootsWithDialogFlags(rootInActiveWindow, winList)
        if (roots.isEmpty()) return null

        val imeBounds = ScreenReaderV2.extractImeBounds(winList)
        val (snapshot, liveNodes) = ScreenReaderV2.capture(
            roots = roots,
            imeBounds = imeBounds,
            screenWidth = w,
            screenHeight = h,
            nowMs = now
        )
        latestLiveNodes = liveNodes
        cacheSnapshotWithNodes(snapshot, liveNodes)
        recordExternalPackageIfValid(this, snapshot.packageName)
        _screenSnapshot.value = snapshot
        return snapshot
    }

    private fun findNodeByViewId(viewId: String): AccessibilityNodeInfo? {
        val target = viewId.trim().lowercase()
        if (target.isBlank()) return null
        val snap = _screenSnapshot.value
        val idx = snap.elements.indexOfFirst { el ->
            val id = el.viewIdResourceName.lowercase()
            id == target || id.endsWith("/$target") || id.contains(target)
        }
        if (idx >= 0) return latestLiveNodes.getOrNull(idx)
        return findNodeRecursive(rootInActiveWindow ?: return null, target)
    }

    private suspend fun dispatchGestureAwait(
        gesture: GestureDescription,
        label: String,
        timeoutMs: Long = 1500L
    ): Boolean {
        return gestureSpacingMutex.withLock {
            val elapsedSincePrev = System.currentTimeMillis() - lastGestureEndMs.get()
            if (elapsedSincePrev < 120L) {
                delay(120L - elapsedSincePrev)
            }
            val resultDeferred = CompletableDeferred<Boolean>()
            val callback = object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    _gestureStatus.value = "$label completed"
                    lastGestureEndMs.set(System.currentTimeMillis())
                    resultDeferred.complete(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    _gestureStatus.value = "$label cancelled"
                    lastGestureEndMs.set(System.currentTimeMillis())
                    resultDeferred.complete(false)
                }
            }
            val dispatched = dispatchGesture(gesture, callback, mainHandler)
            if (!dispatched) {
                lastGestureEndMs.set(System.currentTimeMillis())
                return@withLock false
            }
            withTimeoutOrNull(timeoutMs) {
                resultDeferred.await()
            } ?: run {
                lastGestureEndMs.set(System.currentTimeMillis())
                false
            }
        }
    }

    private suspend fun dispatchSingleTapAwait(cx: Float, cy: Float): Boolean {
        val path = Path().apply { moveTo(cx, cy) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 65L))
            .build()
        return dispatchGestureAwait(gesture, "Tap (${cx.toInt()},${cy.toInt()})")
    }

    private fun performBoundedActionClick(target: AccessibilityNodeInfo): Boolean {
        val targetRect = Rect()
        target.getBoundsInScreen(targetRect)
        val targetArea = targetRect.width().toLong().coerceAtLeast(1L) * targetRect.height().toLong().coerceAtLeast(1L)
        var cursor: AccessibilityNodeInfo? = target
        var hops = 0
        while (cursor != null && hops < 4) {
            if (cursor.isClickable) {
                val r = Rect()
                cursor.getBoundsInScreen(r)
                val area = r.width().toLong().coerceAtLeast(1L) * r.height().toLong().coerceAtLeast(1L)
                // Only click ancestor if it wraps roughly this control (not a full list container)
                if (cursor === target || area <= targetArea * 4L) {
                    if (cursor.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                } else {
                    break
                }
            }
            cursor = cursor.parent
            hops++
        }
        return false
    }

    private suspend fun performUnifiedTapV2(
        index: Int?,
        query: String?,
        viewId: String?,
        elementId: String?,
        snapshotId: String?,
        x: Float?,
        y: Float?
    ): ScreenActionResult {
        var beforeSnap = captureCurrentScreen(force = true) ?: return ScreenActionResult(
            success = false,
            failureReason = "Unable to inspect active screen.",
            hint = "Re-open the target app and try again."
        )
        var liveNodes = latestLiveNodes

        // Check snapshot identity & stale detection
        var targetPair: Pair<ScreenElementNode?, AccessibilityNodeInfo?>? = null
        val cachedNode = lookupCachedElement(snapshotId, index, elementId)
        if (cachedNode != null && (snapshotId != null || !elementId.isNullOrBlank())) {
            val matched = ScreenReaderV2.findBestLiveMatchForSnapshotNode(cachedNode, beforeSnap.elements, liveNodes)
            if (matched == null && query.isNullOrBlank() && viewId.isNullOrBlank() && (x == null || y == null)) {
                return ScreenActionResult(
                    success = false,
                    stale = true,
                    packageName = beforeSnap.packageName,
                    failureReason = "Target '${cachedNode.bestLabel}' disappeared or shifted off screen.",
                    hint = "Re-read the screen before retrying."
                )
            }
            if (matched != null) {
                targetPair = matched.first to matched.second
            }
        }

        if (targetPair == null) {
            targetPair = resolveLiveTarget(beforeSnap, liveNodes, index, query, viewId, elementId)
        }

        if (targetPair == null && (x == null || y == null)) {
            return ScreenActionResult(
                success = false,
                stale = true,
                packageName = beforeSnap.packageName,
                failureReason = "Element '${query ?: viewId ?: (index?.let { "#$it" } ?: "target")}' was not found on screen.",
                hint = "Scroll to bring it into view or re-read screen elements."
            )
        }

        var targetEl = targetPair?.first
        var targetNode = targetPair?.second

        if (targetEl?.isPassword == true || targetNode?.isPassword == true) {
            return ScreenActionResult(
                success = false,
                packageName = beforeSnap.packageName,
                failureReason = "Refused to interact with a password field."
            )
        }

        // If keyboard obscures the target center, dismiss IME and re-resolve once
        if (targetEl != null && beforeSnap.isKeyboardOpen && !beforeSnap.imeBounds.isEmpty &&
            targetEl.centerY >= beforeSnap.imeBounds.top
        ) {
            if (hideSoftKeyboardIfOpen()) {
                beforeSnap = awaitSettled(1200L, 250L) ?: beforeSnap
                liveNodes = latestLiveNodes
                val reResolved = resolveLiveTarget(beforeSnap, liveNodes, index, query, viewId, elementId)
                if (reResolved != null) {
                    targetEl = reResolved.first
                    targetNode = reResolved.second
                }
            }
        }

        val beforeSig = beforeSnap.signatureKey()
        val beforePkg = beforeSnap.packageName

        var dispatched = false
        if (targetNode != null) {
            dispatched = performBoundedActionClick(targetNode)
        }

        val tapX = x ?: targetEl?.centerX?.toFloat() ?: run {
            val r = Rect()
            targetNode?.getBoundsInScreen(r)
            if (!r.isEmpty) r.exactCenterX() else null
        }
        val tapY = y ?: targetEl?.centerY?.toFloat() ?: run {
            val r = Rect()
            targetNode?.getBoundsInScreen(r)
            if (!r.isEmpty) r.exactCenterY() else null
        }

        if (!dispatched && tapX != null && tapY != null) {
            dispatched = dispatchSingleTapAwait(tapX, tapY)
        }

        var afterSnap = if (dispatched) awaitSettled(1800L, 320L) else captureCurrentScreen(force = true)
        var changed = afterSnap != null && (afterSnap.signatureKey() != beforeSig || afterSnap.packageName != beforePkg)

        // If ACTION_CLICK reported true but UI did not visibly settle/change, do one coordinate tap fallback
        if (dispatched && !changed && targetNode != null && tapX != null && tapY != null) {
            val coordOk = dispatchSingleTapAwait(tapX, tapY)
            if (coordOk) {
                afterSnap = awaitSettled(1800L, 320L) ?: afterSnap
                changed = afterSnap != null && (afterSnap.signatureKey() != beforeSig || afterSnap.packageName != beforePkg)
            }
        }

        val finalSnap = afterSnap ?: beforeSnap
        return ScreenActionResult(
            success = dispatched,
            changed = changed,
            stale = false,
            packageName = finalSnap.packageName,
            newSummary = finalSnap.summaryLine,
            failureReason = if (dispatched) "" else "Android gesture dispatcher cancelled the tap.",
            hint = if (!dispatched) "Check if overlay/dialog blocked touch or re-read screen." else ""
        )
    }

    private fun resolveLiveTarget(
        snap: ScreenSnapshot,
        liveNodes: List<AccessibilityNodeInfo?>,
        index: Int?,
        query: String?,
        viewId: String?,
        elementId: String?
    ): Pair<ScreenElementNode?, AccessibilityNodeInfo?>? {
        if (!elementId.isNullOrBlank()) {
            val idx = snap.elements.indexOfFirst { it.id == elementId }
            if (idx >= 0) return snap.elements[idx] to liveNodes.getOrNull(idx)
        }
        if (index != null && index in 1..snap.elements.size) {
            val el = snap.elements[index - 1]
            return el to liveNodes.getOrNull(index - 1)
        }
        if (!viewId.isNullOrBlank()) {
            val targetId = viewId.trim().lowercase()
            val idx = snap.elements.indexOfFirst {
                val id = it.viewIdResourceName.lowercase()
                id == targetId || id.endsWith("/$targetId") || id.contains(targetId)
            }
            if (idx >= 0) return snap.elements[idx] to liveNodes.getOrNull(idx)
            findNodeByViewId(viewId)?.let { node ->
                return null to node
            }
        }
        if (!query.isNullOrBlank()) {
            val q = query.trim().lowercase()
            val idx = snap.elements.indexOfFirst { el ->
                !el.isPassword && (
                    el.bestLabel.equals(q, ignoreCase = true) ||
                        el.bestLabel.lowercase().contains(q) ||
                        el.hintText.lowercase().contains(q)
                    )
            }
            if (idx >= 0) return snap.elements[idx] to liveNodes.getOrNull(idx)
            findMatchingNodeAcrossWindows(query)?.let { node ->
                return null to node
            }
        }
        return null
    }

    private fun performUnifiedTap(
        index: Int?,
        query: String?,
        viewId: String?,
        x: Float?,
        y: Float?
    ): Boolean {
        val snap = captureCurrentScreen(force = true) ?: return false
        val pair = resolveLiveTarget(snap, latestLiveNodes, index, query, viewId, null)
        val targetNode = pair?.second
        if (targetNode != null) {
            if (targetNode.isPassword) return false
            if (performBoundedActionClick(targetNode)) return true
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
        return dispatchGestureWithCallback(gesture, "Tap (${cx.toInt()},${cy.toInt()})")
    }

    private suspend fun performTypeTextV2(
        context: Context,
        index: Int?,
        query: String?,
        elementId: String?,
        snapshotId: String?,
        text: String,
        append: Boolean,
        clearFirst: Boolean,
        submit: Boolean
    ): ScreenActionResult {
        var snap = captureCurrentScreen(force = true) ?: return ScreenActionResult(
            success = false,
            failureReason = "Unable to inspect active screen."
        )
        val beforeSig = snap.signatureKey()
        val cached = lookupCachedElement(snapshotId, index, elementId)
        val resolvedFromCache = if (cached != null) {
            ScreenReaderV2.findBestLiveMatchForSnapshotNode(cached, snap.elements, latestLiveNodes)
        } else null

        val resolved = resolvedFromCache ?: resolveLiveTarget(snap, latestLiveNodes, index, query, null, elementId)
        var candidateNode = resolved?.second
            ?: rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: latestLiveNodes.filterNotNull().firstOrNull { it.isEditable }

        // If candidate exists but is not yet editable/focused, tap it and poll up to 1500ms for a focused editable across windows
        if (candidateNode != null && !candidateNode.isEditable) {
            val r = Rect()
            candidateNode.getBoundsInScreen(r)
            if (!performBoundedActionClick(candidateNode) && !r.isEmpty) {
                dispatchSingleTapAwait(r.exactCenterX(), r.exactCenterY())
            }
            val deadline = System.currentTimeMillis() + 1500L
            while (System.currentTimeMillis() < deadline) {
                delay(120L)
                val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    ?: windows?.firstNotNullOfOrNull { it.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }
                if (focused != null && focused.isEditable) {
                    candidateNode = focused
                    break
                }
            }
        }

        val editable = findEditableTarget(candidateNode)
            ?: latestLiveNodes.filterNotNull().firstOrNull { it.isEditable }
            ?: return ScreenActionResult(
                success = false,
                stale = true,
                packageName = snap.packageName,
                failureReason = "No editable text box found on the current screen.",
                hint = "Tap inside the search/text field first or re-read screen."
            )

        if (editable.isPassword) {
            return ScreenActionResult(
                success = false,
                packageName = snap.packageName,
                failureReason = "Refused to read or type into a password field for security."
            )
        }

        editable.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        editable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        delay(80L)

        val currentVal = editable.text?.toString().orEmpty()
        val targetText = when {
            clearFirst && text.isEmpty() -> ""
            append && !clearFirst -> if (currentVal.isBlank()) text else "$currentVal$text"
            else -> text
        }

        val bundle = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, targetText)
        }
        var setAttempted = editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
        delay(150L)
        try {
            editable.refresh()
        } catch (_: Exception) {
        }
        var actualText = editable.text?.toString().orEmpty()
        val matchesExpected = { observed: String, expected: String ->
            if (expected.isEmpty()) observed.isEmpty() else observed.contains(expected)
        }

        // Clipboard paste fallback if ACTION_SET_TEXT failed or text didn't stick (e.g. certain WebViews/custom fields)
        if (!setAttempted || !matchesExpected(actualText, targetText)) {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("Mahi Input", targetText))
                editable.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                if (!append || clearFirst) {
                    val selBundle = Bundle().apply {
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, actualText.length)
                    }
                    editable.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selBundle)
                }
                setAttempted = editable.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                delay(150L)
                try {
                    editable.refresh()
                } catch (_: Exception) {
                }
                actualText = editable.text?.toString().orEmpty()
            }
        }

        if (!setAttempted) {
            return ScreenActionResult(
                success = false,
                packageName = snap.packageName,
                failureReason = "Active input box blocked Accessibility text entry.",
                hint = "Tap the field manually or check if it is inside a protected web canvas."
            )
        }

        var submitted = false
        if (submit) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                submitted = editable.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            }
            if (!submitted) {
                val freshSnap = captureCurrentScreen(force = true)
                val submitLabels = listOf("search", "send", "post", "go", "submit", "next", "done")
                val submitIdx = freshSnap?.elements?.indexOfFirst { el ->
                    val lbl = el.bestLabel.lowercase()
                    el.isClickable && !el.isEditable && submitLabels.any { lbl == it || lbl.contains(it) }
                } ?: -1
                if (submitIdx >= 0) {
                    val sNode = latestLiveNodes.getOrNull(submitIdx)
                    if (sNode != null) {
                        submitted = performBoundedActionClick(sNode)
                    }
                }
            }
        }

        snap = (if (submit) awaitSettled(1600L, 320L) else awaitSettled(600L, 200L)) ?: snap
        val changed = snap.signatureKey() != beforeSig || actualText.isNotBlank()
        return ScreenActionResult(
            success = true,
            changed = changed,
            packageName = snap.packageName,
            newSummary = snap.summaryLine,
            readBackText = actualText.ifBlank { targetText },
            hint = if (submit && !submitted) "Text entered, but submit button was not detected." else ""
        )
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
        val snap = captureCurrentScreen(force = true)
        val liveNodes = latestLiveNodes.filterNotNull()
        val indexedCandidate = if (snap != null && index != null && index in 1..snap.elements.size) {
            latestLiveNodes.getOrNull(index - 1)
        } else null
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
                val submitLabels = listOf("search", "send", "post", "go", "submit", "next", "done")
                val submitNode = latestLiveNodes.filterNotNull().firstOrNull { node ->
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
        val winList = try {
            windows
        } catch (_: Exception) {
            null
        }
        val roots = ScreenReaderV2.collectCandidateRootsWithDialogFlags(rootInActiveWindow, winList).map { it.first }
        for (root in roots) {
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
        return dispatchGesture(gesture, callback, mainHandler)
    }

    private suspend fun performActionOnMatchingNodeAwait(query: String, longClick: Boolean): Boolean {
        val target = findMatchingNodeAcrossWindows(query) ?: return false
        if (target.isPassword) return false
        val action = if (longClick) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
        var cursor: AccessibilityNodeInfo? = target
        var hops = 0
        while (cursor != null && hops < 4) {
            val canAct = if (longClick) cursor.isLongClickable else cursor.isClickable
            if (canAct && cursor.performAction(action)) return true
            cursor = cursor.parent
            hops++
        }
        val rect = Rect()
        target.getBoundsInScreen(rect)
        if (!rect.isEmpty) {
            val path = Path().apply { moveTo(rect.exactCenterX(), rect.exactCenterY()) }
            val strokeDuration = if (longClick) 650L else 65L
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, strokeDuration))
                .build()
            return dispatchGestureAwait(gesture, if (longClick) "Long press '$query'" else "Tap '$query'")
        }
        return false
    }

    private fun performActionOnMatchingNode(query: String, longClick: Boolean): Boolean {
        val target = findMatchingNodeAcrossWindows(query) ?: return false
        if (target.isPassword) return false
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

    private suspend fun performDoubleTapAwait(query: String): Boolean {
        val (cx, cy) = resolvePointForQueryOrCenter(query)
        val p1 = Path().apply { moveTo(cx, cy) }
        val p2 = Path().apply { moveTo(cx, cy) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p1, 0L, 45L))
            .addStroke(GestureDescription.StrokeDescription(p2, 110L, 45L))
            .build()
        return dispatchGestureAwait(gesture, "Double tap")
    }

    private fun performDoubleTap(query: String): Boolean {
        val (cx, cy) = resolvePointForQueryOrCenter(query)
        val p1 = Path().apply { moveTo(cx, cy) }
        val p2 = Path().apply { moveTo(cx, cy) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p1, 0L, 45L))
            .addStroke(GestureDescription.StrokeDescription(p2, 110L, 45L))
            .build()
        return dispatchGestureWithCallback(gesture, "Double tap")
    }

    private fun resolvePointForQueryOrCenter(query: String): Pair<Float, Float> {
        val target = if (query.isNotBlank()) findMatchingNodeAcrossWindows(query) else null
        if (target != null) {
            val r = Rect()
            target.getBoundsInScreen(r)
            if (!r.isEmpty) return r.exactCenterX() to r.exactCenterY()
        }
        val dm = resources.displayMetrics
        return (dm.widthPixels * 0.5f) to (dm.heightPixels * 0.5f)
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

    private suspend fun performDragCoordinatesAwait(
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
        return dispatchGestureAwait(gesture, "Swipe/Drag")
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

    private fun findNodeRecursive(node: AccessibilityNodeInfo, lowerQuery: String, depth: Int = 0): AccessibilityNodeInfo? {
        if (lowerQuery.isBlank() || depth > 35) return null
        val t = node.text?.toString()?.lowercase().orEmpty()
        val d = node.contentDescription?.toString()?.lowercase().orEmpty()
        val h = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) node.hintText?.toString()?.lowercase().orEmpty() else ""
        val id = node.viewIdResourceName?.lowercase().orEmpty()
        if (node.isVisibleToUser &&
            (t.contains(lowerQuery) || d.contains(lowerQuery) || h.contains(lowerQuery) || id.endsWith(lowerQuery)) &&
            (t.isNotBlank() || d.isNotBlank() || h.isNotBlank() || id.isNotBlank())
        ) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val match = findNodeRecursive(child, lowerQuery, depth + 1)
            if (match != null) return match
        }
        return null
    }

    private suspend fun performScrollOnScreenV2(forward: Boolean, speed: GestureSpeed): ScreenActionResult {
        val beforeSnap = captureCurrentScreen(force = true) ?: return ScreenActionResult(
            success = false,
            failureReason = "Unable to capture screen before scroll."
        )
        val beforeSig = beforeSnap.signatureKey()
        val direction = if (forward) SwipeDirection.UP else SwipeDirection.DOWN
        val scrollable = findDirectionMatchedScrollable(horizontal = false)

        if (speed == GestureSpeed.NORMAL && scrollable != null) {
            val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            if (scrollable.performAction(action)) {
                val afterSnap = awaitSettled(1200L, 280L) ?: beforeSnap
                val changed = afterSnap.signatureKey() != beforeSig
                if (changed) {
                    return ScreenActionResult(
                        success = true,
                        changed = true,
                        packageName = afterSnap.packageName,
                        newSummary = afterSnap.summaryLine
                    )
                }
            }
        }

        // Swipe gesture fallback inside safe-zone / scrollable bounds
        val swipeRes = performSwipeGestureV2(direction = direction, speed = speed)
        val reachedEnd = !swipeRes.changed
        return swipeRes.copy(endOfList = reachedEnd)
    }

    private suspend fun performSwipeGestureV2(
        direction: SwipeDirection,
        speed: GestureSpeed = GestureSpeed.NORMAL,
        targetIndex: Int? = null,
        targetQuery: String = "",
        startX: Float? = null,
        startY: Float? = null,
        endX: Float? = null,
        endY: Float? = null
    ): ScreenActionResult {
        val beforeSnap = captureCurrentScreen(force = true) ?: return ScreenActionResult(
            success = false,
            failureReason = "Unable to inspect screen for swipe."
        )
        val beforeSig = beforeSnap.signatureKey()
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels.coerceAtLeast(1080)
        val screenH = dm.heightPixels.coerceAtLeast(1920)
        val horizontal = direction == SwipeDirection.LEFT || direction == SwipeDirection.RIGHT

        val dispatched = if (startX != null && startY != null && endX != null && endY != null) {
            performDragCoordinatesAwait(startX, startY, endX, endY, speed.durationMs)
        } else {
            val explicitTarget = when {
                targetIndex != null && targetIndex in 1..latestLiveNodes.size -> latestLiveNodes[targetIndex - 1]
                targetQuery.isNotBlank() -> findMatchingNodeAcrossWindows(targetQuery)
                else -> null
            }
            val fullBounds = Rect(0, 0, screenW, screenH)
            val targetBounds = when {
                explicitTarget != null -> {
                    val r = Rect()
                    explicitTarget.getBoundsInScreen(r)
                    if (!r.isEmpty && r.width() > 80 && r.height() > 80) r else fullBounds
                }
                else -> {
                    val scrollable = findDirectionMatchedScrollable(horizontal = horizontal)
                    if (scrollable != null) {
                        val r = Rect()
                        scrollable.getBoundsInScreen(r)
                        if (!r.isEmpty && r.width() > 80 && r.height() > 80) r else fullBounds
                    } else fullBounds
                }
            }
            val durMs = when (speed) {
                GestureSpeed.FAST -> 140L
                GestureSpeed.NORMAL -> 320L
                GestureSpeed.SLOW -> 680L
            }
            val seg = computeSwipeSegment(
                bounds = targetBounds,
                direction = direction,
                screenWidth = screenW,
                screenHeight = screenH,
                imeBounds = beforeSnap.imeBounds.takeIf { !it.isEmpty }
            )
            performDragCoordinatesAwait(seg.startX, seg.startY, seg.endX, seg.endY, durMs)
        }

        val afterSnap = if (dispatched) awaitSettled(1400L, 280L) else beforeSnap
        val finalSnap = afterSnap ?: beforeSnap
        val changed = finalSnap.signatureKey() != beforeSig || finalSnap.packageName != beforeSnap.packageName
        return ScreenActionResult(
            success = dispatched,
            changed = changed,
            packageName = finalSnap.packageName,
            newSummary = finalSnap.summaryLine,
            endOfList = dispatched && !changed,
            failureReason = if (dispatched) "" else "Swipe gesture cancelled by Android system."
        )
    }

    private fun performScrollOnScreen(forward: Boolean, speed: GestureSpeed): Boolean {
        val scrollable = findDirectionMatchedScrollable(horizontal = false)
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

    private fun findDirectionMatchedScrollable(horizontal: Boolean): AccessibilityNodeInfo? {
        val winList = try {
            windows
        } catch (_: Exception) {
            null
        }
        val roots = ScreenReaderV2.collectCandidateRootsWithDialogFlags(rootInActiveWindow, winList).map { it.first }
        val scrollables = mutableListOf<AccessibilityNodeInfo>()
        roots.forEach { collectScrollables(it, scrollables, 0) }
        val visible = scrollables.filter { it.isVisibleToUser }.ifEmpty { scrollables }
        if (visible.isEmpty()) return null

        val dirFiltered = if (horizontal) {
            visible.filter { node ->
                val cls = node.className?.toString().orEmpty()
                cls.contains("ViewPager", ignoreCase = true) ||
                    cls.contains("HorizontalScrollView", ignoreCase = true)
            }.ifEmpty { visible }
        } else {
            visible.filter { node ->
                val cls = node.className?.toString().orEmpty()
                !cls.contains("ViewPager", ignoreCase = true) &&
                    !cls.contains("HorizontalScrollView", ignoreCase = true)
            }.ifEmpty { visible }
        }

        return dirFiltered.maxByOrNull { node ->
            val r = Rect()
            node.getBoundsInScreen(r)
            r.width().toLong() * r.height().toLong()
        }
    }

    private fun collectScrollables(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>, depth: Int) {
        if (depth > 35 || out.size >= 30) return
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
        val screenW = dm.widthPixels.coerceAtLeast(1080)
        val screenH = dm.heightPixels.coerceAtLeast(1920)
        val fullBounds = Rect(0, 0, screenW, screenH)
        val horizontal = direction == SwipeDirection.LEFT || direction == SwipeDirection.RIGHT
        val explicitTarget = when {
            targetIndex != null && targetIndex in 1..latestLiveNodes.size -> latestLiveNodes[targetIndex - 1]
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
                val scrollable = findDirectionMatchedScrollable(horizontal = horizontal)
                if (scrollable != null) {
                    val r = Rect()
                    scrollable.getBoundsInScreen(r)
                    if (!r.isEmpty && r.width() > 80 && r.height() > 80) r else fullBounds
                } else fullBounds
            }
            else -> fullBounds
        }

        val winList = try {
            windows
        } catch (_: Exception) {
            null
        }
        val imeBounds = ScreenReaderV2.extractImeBounds(winList)
        val seg = computeSwipeSegment(targetBounds, direction, screenW, screenH, imeBounds)
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
