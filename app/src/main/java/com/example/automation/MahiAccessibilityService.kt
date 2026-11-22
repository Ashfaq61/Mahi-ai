package com.example.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import com.example.preferences.MahiPreferencesStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

enum class SwipeDirection {
    UP, DOWN, LEFT, RIGHT
}

enum class GestureSpeed(val durationMs: Long) {
    SLOW(620L),
    NORMAL(340L),
    FAST(190L)
}

data class VoiceGestureIntent(
    val actionName: String,
    val targetQuery: String? = null,
    val targetIndex: Int? = null,
    val speed: GestureSpeed = GestureSpeed.NORMAL,
    val startX: Float? = null,
    val startY: Float? = null,
    val endX: Float? = null,
    val endY: Float? = null
)

data class GestureHistoryEntry(
    val action: String,
    val detail: String,
    val success: Boolean,
    val timestampMs: Long = System.currentTimeMillis()
)

class MahiAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "MahiA11yService"
        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

        private val _screenSnapshot = MutableStateFlow(ScreenSnapshot())
        val screenSnapshot: StateFlow<ScreenSnapshot> = _screenSnapshot.asStateFlow()

        private val _gestureHistory = MutableStateFlow<List<GestureHistoryEntry>>(emptyList())
        val gestureHistory: StateFlow<List<GestureHistoryEntry>> = _gestureHistory.asStateFlow()

        @Volatile
        private var instance: MahiAccessibilityService? = null

        var onStopAutomationFromOverlay: (() -> Unit)? = null

        fun isAccessibilityServiceEnabledInSettings(context: Context): Boolean {
            if (_isConnected.value && instance != null) return true
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            return enabled.contains(context.packageName, ignoreCase = true)
        }

        fun recordGesture(action: String, detail: String, success: Boolean) {
            val next = (listOf(GestureHistoryEntry(action, detail, success)) + _gestureHistory.value).take(40)
            _gestureHistory.value = next
        }

        fun evaluateForegroundState(context: Context): ScreenActionResult {
            val svc = instance
            if (!_isConnected.value || svc == null) {
                return ScreenActionResult(
                    success = false,
                    actionName = "evaluateForegroundState",
                    message = "Accessibility Service is disabled. Enable Mahi Accessibility Service in Android Settings.",
                    verificationDetail = "Service disconnected",
                    suggestedStrategy = SuggestedRetryStrategy.REQUIRE_USER_CONFIRMATION
                )
            }
            val snap = svc.captureFreshSnapshot()
            _screenSnapshot.value = snap
            val ownPkg = context.packageName
            if (snap.packageName == ownPkg) {
                return ScreenActionResult(
                    success = false,
                    actionName = "evaluateForegroundState",
                    message = "Mahi AI is currently in the foreground. Open the target app first so Mahi can read that app's screen.",
                    verificationDetail = "Foreground is own package ($ownPkg)",
                    suggestedStrategy = SuggestedRetryStrategy.WAIT_FOR_IDLE,
                    packageName = snap.packageName
                )
            }
            return ScreenActionResult(
                success = true,
                actionName = "evaluateForegroundState",
                message = "Active app screen: ${snap.packageName} (${snap.elements.size} elements)",
                packageName = snap.packageName
            )
        }

        suspend fun readScreenWithRetry(
            context: Context,
            maxAttempts: Int = 3
        ): ScreenSnapshot {
            val svc = instance ?: return _screenSnapshot.value
            val prefs = MahiPreferencesStore(context)
            var latest = svc.captureFreshSnapshot()
            repeat(maxAttempts) { attempt ->
                if (latest.packageName.isNotBlank() &&
                    latest.packageName != context.packageName &&
                    latest.elements.isNotEmpty()
                ) {
                    if (MahiPreferencesStore.isValidExternalTargetPackage(latest.packageName, context.packageName)) {
                        prefs.setLastExternalTargetPackage(latest.packageName)
                    }
                    _screenSnapshot.value = latest
                    return latest
                }
                if (attempt < maxAttempts - 1) delay(240L)
                latest = svc.captureFreshSnapshot()
            }
            _screenSnapshot.value = latest
            return latest
        }

        fun executeGestureIntent(intent: VoiceGestureIntent): AutomationOutcome {
            val svc = instance
                ?: return AutomationOutcome(
                    success = false,
                    message = "Accessibility Service not connected. Enable it in Settings.",
                    suggestedStrategy = SuggestedRetryStrategy.REQUIRE_USER_CONFIRMATION
                )
            return svc.performSyncGesture(intent)
        }

        suspend fun executeGestureIntentAwait(intent: VoiceGestureIntent): AutomationOutcome {
            val svc = instance
                ?: return AutomationOutcome(
                    success = false,
                    message = "Accessibility Service not connected. Enable it in Settings.",
                    suggestedStrategy = SuggestedRetryStrategy.REQUIRE_USER_CONFIRMATION
                )
            return svc.performAwaitGesture(intent)
        }

        suspend fun tapElementAwait(
            index: Int? = null,
            query: String? = null,
            viewId: String? = null,
            fallbackX: Float? = null,
            fallbackY: Float? = null
        ): AutomationOutcome {
            val svc = instance
                ?: return AutomationOutcome(false, "Accessibility Service is disabled.")
            return svc.performTapAwait(index, query, viewId, fallbackX, fallbackY)
        }

        suspend fun typeTextAwait(
            text: String,
            index: Int? = null,
            query: String? = null,
            clearFirst: Boolean = false,
            submit: Boolean = true
        ): AutomationOutcome {
            val svc = instance
                ?: return AutomationOutcome(false, "Accessibility Service is disabled.")
            return svc.performTypeAwait(text, index, query, clearFirst, submit)
        }

        suspend fun scrollScreenAwait(forward: Boolean): AutomationOutcome =
            executeGestureIntentAwait(
                VoiceGestureIntent(
                    actionName = if (forward) "SCROLL_DOWN" else "SCROLL_UP",
                    speed = GestureSpeed.NORMAL
                )
            )

        suspend fun swipeScreenAwait(direction: SwipeDirection): AutomationOutcome {
            val action = when (direction) {
                SwipeDirection.UP -> "SWIPE_UP_SCROLL"
                SwipeDirection.DOWN -> "SWIPE_DOWN_SCROLL"
                SwipeDirection.LEFT -> "SWIPE_LEFT"
                SwipeDirection.RIGHT -> "SWIPE_RIGHT"
            }
            return executeGestureIntentAwait(VoiceGestureIntent(actionName = action))
        }

        fun navigateBack(): AutomationOutcome {
            val svc = instance ?: return AutomationOutcome(false, "Accessibility Service not connected.")
            val ok = svc.performGlobalAction(GLOBAL_ACTION_BACK)
            recordGesture("BACK", "Global back action", ok)
            return AutomationOutcome(ok, if (ok) "Navigated back" else "Back action failed")
        }

        fun openQuickSettings(): AutomationOutcome {
            val svc = instance ?: return AutomationOutcome(false, "Accessibility Service not connected.")
            val ok = svc.performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            recordGesture("QUICK_SETTINGS", "System Quick Settings", ok)
            return AutomationOutcome(ok, if (ok) "Opened Quick Settings" else "Unable to open Quick Settings")
        }

        fun openNotificationsShade(): AutomationOutcome {
            val svc = instance ?: return AutomationOutcome(false, "Accessibility Service not connected.")
            val ok = svc.performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            recordGesture("NOTIFICATIONS", "Notification shade", ok)
            return AutomationOutcome(ok, if (ok) "Opened Notification Shade" else "Unable to open Notification Shade")
        }

        fun setAutopilotObserving(active: Boolean, statusText: String = "") {
            instance?.updateFloatingStopOverlay(active, statusText.ifBlank { "Autopilot Active" })
        }

        fun setWatchModeObserving(active: Boolean) {
            instance?.updateFloatingStopOverlay(active, "Watch Mode")
        }

        suspend fun takeScreenshotBitmap(): Bitmap? {
            val svc = instance ?: return null
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
            return withTimeoutOrNull(3000L) {
                suspendCoroutine { cont ->
                    svc.takeScreenshot(
                        android.view.Display.DEFAULT_DISPLAY,
                        svc.mainExecutor,
                        object : TakeScreenshotCallback {
                            override fun onSuccess(screenshot: ScreenshotResult) {
                                val hw = screenshot.hardwareBuffer
                                val cs = screenshot.colorSpace
                                val bmp = Bitmap.wrapHardwareBuffer(hw, cs)
                                hw.close()
                                cont.resume(bmp?.copy(Bitmap.Config.ARGB_8888, true))
                            }

                            override fun onFailure(errorCode: Int) {
                                cont.resume(null)
                            }
                        }
                    )
                }
            }
        }

        suspend fun takeScreenshotJpeg(snapshot: ScreenSnapshot? = null): Result<ByteArray> {
            val bmp = takeScreenshotBitmap()
                ?: return Result.failure(IllegalStateException("Accessibility screenshot requires Android 11+ and active accessibility service."))
            val snap = snapshot ?: _screenSnapshot.value
            val bytes = SetOfMarkGrounding.redactPasswordsToJpeg(bmp, snap)
            return Result.success(bytes)
        }
    }

    private var overlayButton: Button? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isConnected.value = true
        _screenSnapshot.value = captureFreshSnapshot()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val snap = captureFreshSnapshot()
        if (snap.elements.isNotEmpty() || snap.packageName.isNotBlank()) {
            _screenSnapshot.value = snap
            val ownPkg = packageName
            if (MahiPreferencesStore.isValidExternalTargetPackage(snap.packageName, ownPkg)) {
                MahiPreferencesStore(this).setLastExternalTargetPackage(snap.packageName)
            }
        }
    }

    override fun onInterrupt() {
        updateFloatingStopOverlay(false, "")
    }

    override fun onDestroy() {
        super.onDestroy()
        updateFloatingStopOverlay(false, "")
        if (instance === this) instance = null
        _isConnected.value = false
    }

    private fun displaySize(): Pair<Int, Int> {
        val wm = getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm?.currentWindowMetrics?.bounds
            if (bounds != null && bounds.width() > 0) bounds.width() to bounds.height() else 1080 to 2400
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm?.defaultDisplay?.getRealMetrics(metrics)
            (metrics.widthPixels.takeIf { it > 0 } ?: 1080) to (metrics.heightPixels.takeIf { it > 0 } ?: 2400)
        }
    }

    fun captureFreshSnapshot(): ScreenSnapshot {
        val roots = try {
            windows?.mapNotNull { it.root }.orEmpty().ifEmpty { listOfNotNull(rootInActiveWindow) }
        } catch (_: Exception) {
            listOfNotNull(rootInActiveWindow)
        }
        val (w, h) = displaySize()
        val hint = rootInActiveWindow?.packageName?.toString().orEmpty()
        return ScreenReaderV2.collectElementsFromRoots(roots, hint, w, h)
    }

    private fun findAccessibilityNodeForTarget(
        root: AccessibilityNodeInfo?,
        snapshotElement: ScreenElementNode?
    ): AccessibilityNodeInfo? {
        if (root == null || snapshotElement == null) return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 400) {
            val cur = queue.removeFirst()
            visited++
            val rect = Rect()
            cur.getBoundsInScreen(rect)
            val txt = cur.text?.toString().orEmpty()
            val desc = cur.contentDescription?.toString().orEmpty()
            val idMatch = snapshotElement.viewIdResourceName.isNotBlank() &&
                cur.viewIdResourceName == snapshotElement.viewIdResourceName
            val rectClose = kotlin.math.abs(rect.centerX() - snapshotElement.bounds.centerX()) < 48 &&
                kotlin.math.abs(rect.centerY() - snapshotElement.bounds.centerY()) < 48
            val labelMatch = txt.equals(snapshotElement.text, true) || desc.equals(snapshotElement.contentDescription, true)
            if ((idMatch && rectClose) || (rectClose && (cur.isClickable || cur.isEditable || labelMatch))) {
                return cur
            }
            for (i in 0 until cur.childCount) {
                cur.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private suspend fun performTapAwait(
        index: Int?,
        query: String?,
        viewId: String?,
        fallbackX: Float?,
        fallbackY: Float?
    ): AutomationOutcome {
        val snap = captureFreshSnapshot()
        _screenSnapshot.value = snap

        val matched = when {
            index != null -> snap.elements.firstOrNull { it.index == index }
            !viewId.isNullOrBlank() -> snap.elements.firstOrNull { it.viewIdResourceName.contains(viewId, true) }
            !query.isNullOrBlank() -> snap.elements.firstOrNull {
                it.bestLabel.equals(query, true)
            } ?: snap.elements.firstOrNull {
                it.bestLabel.contains(query, true)
            }
            else -> null
        }

        if (matched != null) {
            val node = findAccessibilityNodeForTarget(rootInActiveWindow, matched)
            if (node != null && node.isClickable) {
                val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (clicked) {
                    recordGesture("TAP_NODE", matched.bestLabel, true)
                    return AutomationOutcome(
                        success = true,
                        message = "Tapped '${matched.bestLabel}'",
                        verificationDetail = "Accessibility ACTION_CLICK on #${matched.index}",
                        packageName = snap.packageName
                    )
                }
            }
            val cx = matched.bounds.centerX().toFloat()
            val cy = matched.bounds.centerY().toFloat()
            val dispatched = dispatchCoordinateTapAwait(cx, cy)
            recordGesture("TAP_COORD", "${matched.bestLabel} @($cx,$cy)", dispatched)
            return AutomationOutcome(
                success = dispatched,
                message = if (dispatched) "Tapped '${matched.bestLabel}'" else "Tap failed at ($cx,$cy)",
                verificationDetail = "Coordinate tap @(${cx.toInt()},${cy.toInt()})",
                packageName = snap.packageName
            )
        }

        if (fallbackX != null && fallbackY != null) {
            val ok = dispatchCoordinateTapAwait(fallbackX, fallbackY)
            recordGesture("TAP_POINT", "(${fallbackX.toInt()},${fallbackY.toInt()})", ok)
            return AutomationOutcome(ok, if (ok) "Tapped point (${fallbackX.toInt()},${fallbackY.toInt()})" else "Tap gesture rejected")
        }

        return AutomationOutcome(
            success = false,
            message = "Target element not found on current screen (${query ?: "#$index"}).",
            suggestedStrategy = SuggestedRetryStrategy.FALLBACK_TO_SCROLL,
            packageName = snap.packageName
        )
    }

    private suspend fun performTypeAwait(
        text: String,
        index: Int?,
        query: String?,
        clearFirst: Boolean,
        submit: Boolean
    ): AutomationOutcome {
        val snap = captureFreshSnapshot()
        _screenSnapshot.value = snap
        val editCandidate = when {
            index != null -> snap.elements.firstOrNull { it.index == index }
            !query.isNullOrBlank() -> snap.elements.firstOrNull {
                it.isEditable && it.bestLabel.contains(query, true)
            } ?: snap.elements.firstOrNull { it.isEditable }
            else -> snap.elements.firstOrNull { it.isEditable && it.isFocused }
                ?: snap.elements.firstOrNull { it.isEditable }
        } ?: return AutomationOutcome(
            false,
            "No editable input box visible on screen.",
            suggestedStrategy = SuggestedRetryStrategy.FALLBACK_TO_SCROLL
        )

        if (editCandidate.isPassword) {
            return AutomationOutcome(
                false,
                "Blocked typing into password field for safety.",
                suggestedStrategy = SuggestedRetryStrategy.REQUIRE_USER_CONFIRMATION
            )
        }

        val node = findAccessibilityNodeForTarget(rootInActiveWindow, editCandidate)
            ?: return AutomationOutcome(false, "Editable field lost focus before typing.")

        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        delay(80L)

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, if (clearFirst) text else text)
        }
        val setOk = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (submit && setOk) {
            delay(120L)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            }
        }
        recordGesture("TYPE_TEXT", "Typed ${text.length} chars into ${editCandidate.bestLabel}", setOk)
        return AutomationOutcome(
            success = setOk,
            message = if (setOk) "Typed text into '${editCandidate.bestLabel}'" else "Android rejected text insertion.",
            verificationDetail = if (submit) "Submitted IME action" else "Text entered",
            packageName = snap.packageName
        )
    }

    private fun performSyncGesture(intent: VoiceGestureIntent): AutomationOutcome {
        val path = buildPathForGesture(intent) ?: return AutomationOutcome(false, "Unknown gesture ${intent.actionName}")
        val stroke = GestureDescription.StrokeDescription(path, 0L, intent.speed.durationMs)
        val desc = GestureDescription.Builder().addStroke(stroke).build()
        val ok = dispatchGesture(desc, null, null)
        recordGesture(intent.actionName, intent.targetQuery ?: "dispatched", ok)
        return AutomationOutcome(ok, if (ok) "Executed ${intent.actionName}" else "Gesture blocked by Android")
    }

    private suspend fun performAwaitGesture(intent: VoiceGestureIntent): AutomationOutcome {
        if (intent.actionName.equals("BACK", true)) return navigateBack()
        val path = buildPathForGesture(intent) ?: return AutomationOutcome(false, "Unknown gesture ${intent.actionName}")
        val stroke = GestureDescription.StrokeDescription(path, 0L, intent.speed.durationMs)
        val desc = GestureDescription.Builder().addStroke(stroke).build()
        val ok = dispatchGestureAwait(desc)
        recordGesture(intent.actionName, intent.targetQuery ?: "completed", ok)
        return AutomationOutcome(ok, if (ok) "Completed ${intent.actionName}" else "Gesture cancelled or rejected")
    }

    private fun buildPathForGesture(intent: VoiceGestureIntent): Path? {
        val (w, h) = displaySize()
        val safe = ScreenReaderV2.computeSwipeSafeBounds(w, h, _screenSnapshot.value.imeBounds)
        val cx = safe.centerX().toFloat()
        val cy = safe.centerY().toFloat()
        return Path().apply {
            when (intent.actionName.uppercase()) {
                "SWIPE_UP_SCROLL", "SCROLL_DOWN" -> {
                    moveTo(cx, safe.bottom.toFloat())
                    lineTo(cx, safe.top.toFloat())
                }
                "SWIPE_DOWN_SCROLL", "SCROLL_UP" -> {
                    moveTo(cx, safe.top.toFloat())
                    lineTo(cx, safe.bottom.toFloat())
                }
                "SWIPE_LEFT" -> {
                    moveTo(safe.right.toFloat(), cy)
                    lineTo(safe.left.toFloat(), cy)
                }
                "SWIPE_RIGHT" -> {
                    moveTo(safe.left.toFloat(), cy)
                    lineTo(safe.right.toFloat(), cy)
                }
                "COORDINATE_TAP", "TAP" -> {
                    val x = (intent.startX ?: cx).coerceIn(1f, w.toFloat() - 1f)
                    val y = (intent.startY ?: cy).coerceIn(1f, h.toFloat() - 1f)
                    moveTo(x, y)
                }
                "DRAG", "CUSTOM_SWIPE" -> {
                    val sx = intent.startX ?: cx
                    val sy = intent.startY ?: safe.bottom.toFloat()
                    val ex = intent.endX ?: cx
                    val ey = intent.endY ?: safe.top.toFloat()
                    moveTo(sx, sy)
                    lineTo(ex, ey)
                }
                else -> return null
            }
        }
    }

    private suspend fun dispatchCoordinateTapAwait(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x.coerceAtLeast(1f), y.coerceAtLeast(1f)) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 65L)
        return dispatchGestureAwait(GestureDescription.Builder().addStroke(stroke).build())
    }

    private suspend fun dispatchGestureAwait(gesture: GestureDescription): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        val started = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    deferred.complete(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    deferred.complete(false)
                }
            },
            mainHandler
        )
        if (!started) return false
        return withTimeoutOrNull(3000L) { deferred.await() } ?: false
    }

    @SuppressLint("RtlHardcoded")
    fun updateFloatingStopOverlay(visible: Boolean, label: String) {
        mainHandler.post {
            val wm = getSystemService(WindowManager::class.java) ?: return@post
            if (!visible) {
                overlayButton?.let { btn ->
                    runCatching { wm.removeView(btn) }
                }
                overlayButton = null
                return@post
            }
            if (!Settings.canDrawOverlays(this)) return@post
            if (overlayButton == null) {
                val btn = Button(this).apply {
                    text = "STOP ($label)"
                    setBackgroundColor(Color.parseColor("#DC2626"))
                    setTextColor(Color.WHITE)
                    textSize = 11f
                    setPadding(24, 12, 24, 12)
                    setOnClickListener {
                        onStopAutomationFromOverlay?.invoke()
                        updateFloatingStopOverlay(false, "")
                    }
                }
                val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.END
                    x = 24
                    y = 140
                }
                runCatching {
                    wm.addView(btn, lp)
                    overlayButton = btn
                }
            } else {
                overlayButton?.text = "STOP ($label)"
            }
        }
    }
}
