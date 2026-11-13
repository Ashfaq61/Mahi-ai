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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

class MahiAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        private var activeService: MahiAccessibilityService? = null

        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

        private val _screenSnapshot = MutableStateFlow(ScreenSnapshot())
        val screenSnapshot: StateFlow<ScreenSnapshot> = _screenSnapshot.asStateFlow()

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
            return activeService?.performActionOnMatchingNode(query, longClick = false) ?: false
        }

        fun longPressElementByText(query: String): Boolean {
            return activeService?.performActionOnMatchingNode(query, longClick = true) ?: false
        }

        fun scrollScreen(forward: Boolean): Boolean {
            return activeService?.performScrollOnScreen(forward) ?: false
        }

        fun swipeScreen(direction: SwipeDirection): Boolean {
            return activeService?.performSwipeGesture(direction) ?: false
        }

        fun pressBack(): Boolean = activeService?.performGlobalAction(GLOBAL_ACTION_BACK) ?: false

        fun pressHome(): Boolean = activeService?.performGlobalAction(GLOBAL_ACTION_HOME) ?: false
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeService = this
        _isConnected.value = true
        captureCurrentScreen()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        captureCurrentScreen()
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        if (activeService === this) {
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

        // Try target first, then walk up to clickable parent if label is inside a button container
        var cursor: AccessibilityNodeInfo? = target
        while (cursor != null) {
            val canAct = if (longClick) cursor.isLongClickable else cursor.isClickable
            if (canAct && cursor.performAction(action)) {
                captureCurrentScreen()
                return true
            }
            cursor = cursor.parent
        }
        return false
    }

    private fun findNodeRecursive(node: AccessibilityNodeInfo, lowerQuery: String): AccessibilityNodeInfo? {
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

    private fun performScrollOnScreen(forward: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val scrollable = findScrollableNode(root) ?: return performSwipeGesture(
            if (forward) SwipeDirection.UP else SwipeDirection.DOWN
        )
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        val acted = scrollable.performAction(action)
        if (acted) captureCurrentScreen()
        return acted
    }

    private fun findScrollableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findScrollableNode(child)
            if (found != null) return found
        }
        return null
    }

    private fun performSwipeGesture(direction: SwipeDirection): Boolean {
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        val path = Path()

        when (direction) {
            SwipeDirection.UP -> {
                path.moveTo(w * 0.5f, h * 0.72f)
                path.lineTo(w * 0.5f, h * 0.28f)
            }
            SwipeDirection.DOWN -> {
                path.moveTo(w * 0.5f, h * 0.28f)
                path.lineTo(w * 0.5f, h * 0.72f)
            }
            SwipeDirection.LEFT -> {
                path.moveTo(w * 0.82f, h * 0.5f)
                path.lineTo(w * 0.18f, h * 0.5f)
            }
            SwipeDirection.RIGHT -> {
                path.moveTo(w * 0.18f, h * 0.5f)
                path.lineTo(w * 0.82f, h * 0.5f)
            }
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 280L))
            .build()
        return dispatchGesture(gesture, null, null)
    }
}
