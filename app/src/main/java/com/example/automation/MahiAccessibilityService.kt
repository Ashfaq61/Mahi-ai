package com.example.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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
    val bounds: Rect
) {
    val bestLabel: String
        get() = text.ifBlank { contentDescription }.ifBlank { viewIdResourceName.substringAfterLast("/") }
}

data class ScreenSnapshot(
    val packageName: String = "",
    val elements: List<ScreenElementNode> = emptyList(),
    val capturedAtMs: Long = 0L
) {
    val readableSummary: String
        get() {
            if (elements.isEmpty()) return "No screen elements captured yet. Enable Mahi Accessibility Service to inspect active apps."
            val labels = elements.mapNotNull { it.bestLabel.takeIf { s -> s.isNotBlank() } }.distinct().take(20)
            return "App: $packageName | Visible elements (${elements.size}): ${labels.joinToString(", ")}"
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

        fun openAccessibilitySettings(context: Context) {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }

        fun refreshScreenSnapshot(): ScreenSnapshot? {
            return activeService?.captureCurrentScreen()
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

        fun swipeScreen(direction: SwipeDirection, speed: GestureSpeed = GestureSpeed.NORMAL): Boolean {
            val svc = activeService
            if (svc == null) {
                recordGesture("SWIPE", "Accessibility Service off — cannot swipe ${direction.name}", false)
                return false
            }
            val ok = svc.performSwipeGesture(direction, speed, useScrollableBounds = true)
            recordGesture("SWIPE_${direction.name}", if (ok) "Swiped ${direction.name} (${speed.displayName})" else "Swipe failed", ok)
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

        fun executeGestureIntent(intent: VoiceGestureIntent): AutomationOutcome {
            return when (intent.actionName.uppercase()) {
                "STOP" -> {
                    stopAllGestures()
                    AutomationOutcome(true, "Stopped all screen gestures immediately", "STOP command executed")
                }
                "SWIPE_UP", "SWIPE_UP_SCROLL" -> {
                    val ok = swipeScreen(SwipeDirection.UP, intent.speed)
                    AutomationOutcome(ok, if (ok) "Swiped UP (${intent.speed.displayName})" else "Enable Mahi Accessibility Service")
                }
                "SWIPE_DOWN", "SWIPE_DOWN_SCROLL" -> {
                    val ok = swipeScreen(SwipeDirection.DOWN, intent.speed)
                    AutomationOutcome(ok, if (ok) "Swiped DOWN (${intent.speed.displayName})" else "Enable Mahi Accessibility Service")
                }
                "SWIPE_LEFT" -> {
                    val ok = swipeScreen(SwipeDirection.LEFT, intent.speed)
                    AutomationOutcome(ok, if (ok) "Swiped LEFT" else "Enable Mahi Accessibility Service")
                }
                "SWIPE_RIGHT" -> {
                    val ok = swipeScreen(SwipeDirection.RIGHT, intent.speed)
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
                "TAP_TEXT" -> {
                    val query = intent.targetText.ifBlank { "OK" }
                    val ok = tapElementByText(query)
                    AutomationOutcome(ok, if (ok) "Tapped '$query'" else "Could not find or tap '$query'")
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
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeService = this
        _isConnected.value = true
        _gestureStatus.value = "Connected — Accessibility Gesture Engine Ready"
        captureCurrentScreen()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        captureCurrentScreen()
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

    fun captureCurrentScreen(): ScreenSnapshot? {
        val root = rootInActiveWindow ?: return null
        val pkg = root.packageName?.toString().orEmpty()
        val collected = mutableListOf<ScreenElementNode>()
        traverseNode(root, collected, depth = 0)
        val snapshot = ScreenSnapshot(
            packageName = pkg,
            elements = collected.take(60),
            capturedAtMs = System.currentTimeMillis()
        )
        _screenSnapshot.value = snapshot
        return snapshot
    }

    private fun traverseNode(
        node: AccessibilityNodeInfo,
        out: MutableList<ScreenElementNode>,
        depth: Int
    ) {
        if (depth > 14 || out.size >= 60) return
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val resId = node.viewIdResourceName.orEmpty()

        if (text.isNotBlank() || desc.isNotBlank() || node.isClickable || node.isScrollable || node.isEditable) {
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
                    bounds = rect
                )
            )
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverseNode(child, out, depth + 1)
        }
    }

    private fun performActionOnMatchingNode(query: String, longClick: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = findNodeRecursive(root, query.trim().lowercase()) ?: return false
        val action = if (longClick) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK

        var cursor: AccessibilityNodeInfo? = target
        while (cursor != null) {
            val canAct = if (longClick) cursor.isLongClickable else cursor.isClickable
            if (canAct && cursor.performAction(action)) {
                captureCurrentScreen()
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
            return dispatchGesture(gesture, null, null)
        }
        return false
    }

    private fun performDoubleTap(query: String): Boolean {
        val cx: Float
        val cy: Float
        val root = rootInActiveWindow
        val target = if (query.isNotBlank() && root != null) {
            findNodeRecursive(root, query.trim().lowercase())
        } else null

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
        return dispatchGesture(gesture, null, null)
    }

    private fun performDragBetweenNodes(fromQuery: String, toQuery: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val fromNode = findNodeRecursive(root, fromQuery.trim().lowercase()) ?: return false
        val toNode = findNodeRecursive(root, toQuery.trim().lowercase()) ?: return false
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
        return dispatchGesture(gesture, null, null)
    }

    private fun findNodeRecursive(node: AccessibilityNodeInfo, lowerQuery: String): AccessibilityNodeInfo? {
        if (lowerQuery.isBlank()) return null
        val t = node.text?.toString()?.lowercase().orEmpty()
        val d = node.contentDescription?.toString()?.lowercase().orEmpty()
        val id = node.viewIdResourceName?.lowercase().orEmpty()
        if ((t.contains(lowerQuery) || d.contains(lowerQuery) || id.endsWith(lowerQuery)) &&
            (t.isNotBlank() || d.isNotBlank() || id.isNotBlank())
        ) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val match = findNodeRecursive(child, lowerQuery)
            if (match != null) return match
        }
        return null
    }

    private fun performScrollOnScreen(forward: Boolean, speed: GestureSpeed): Boolean {
        val root = rootInActiveWindow
        val scrollable = if (root != null) findLargestScrollableNode(root) else null
        if (speed == GestureSpeed.NORMAL && scrollable != null) {
            val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            if (scrollable.performAction(action)) {
                captureCurrentScreen()
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
        return scrollables.maxByOrNull { node ->
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
        useScrollableBounds: Boolean = true
    ): Boolean {
        val dm = resources.displayMetrics
        val fullBounds = Rect(0, 0, dm.widthPixels, dm.heightPixels)
        val targetBounds = if (useScrollableBounds) {
            val scrollable = rootInActiveWindow?.let { findLargestScrollableNode(it) }
            if (scrollable != null) {
                val r = Rect()
                scrollable.getBoundsInScreen(r)
                if (!r.isEmpty && r.width() > 80 && r.height() > 80) r else fullBounds
            } else fullBounds
        } else fullBounds

        val seg = computeSwipeSegment(targetBounds, direction)
        val path = Path().apply {
            moveTo(seg.startX, seg.startY)
            lineTo(seg.endX, seg.endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, speed.durationMs))
            .build()
        return dispatchGesture(gesture, null, null)
    }
}
