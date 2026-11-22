package com.example.automation

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.example.preferences.AppLanguage
import com.example.vision.NotificationUnderstandingManager
import org.json.JSONObject
import kotlin.math.abs

enum class SuggestedRetryStrategy {
    NONE,
    WAIT_FOR_IDLE,
    RETRY_WITH_REFRESH,
    FALLBACK_TO_SCROLL,
    FALLBACK_TO_COORDINATE_TAP,
    FALLBACK_TO_VISION_GROUNDING,
    REQUIRE_USER_CONFIRMATION
}

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
    val isFocused: Boolean = false,
    val isSelected: Boolean = false,
    val isChecked: Boolean = false,
    val isPassword: Boolean = false,
    val isEnabled: Boolean = true,
    val packageName: String = "",
    val depth: Int = 0,
    val windowLayer: Int = 0
) {
    val bestLabel: String
        get() = when {
            isPassword -> "[PASSWORD_HIDDEN]"
            text.isNotBlank() -> NotificationUnderstandingManager.redactSensitiveText(text)
            contentDescription.isNotBlank() -> NotificationUnderstandingManager.redactSensitiveText(contentDescription)
            viewIdResourceName.isNotBlank() -> viewIdResourceName.substringAfterLast('/')
            else -> className.substringAfterLast('.')
        }

    val region: String
        get() {
            val cy = bounds.centerY()
            val cx = bounds.centerX()
            val v = when {
                cy < 500 -> "top"
                cy > 1650 -> "bottom"
                else -> "middle"
            }
            val h = when {
                cx < 350 -> "left"
                cx > 730 -> "right"
                else -> "center"
            }
            return "$v-$h"
        }

    val fingerprint: String
        get() = "${className.substringAfterLast('.')}|${viewIdResourceName.substringAfterLast('/')}|${bestLabel.lowercase()}|$region"

    fun toIndexedLine(): String {
        val simpleClass = className.substringAfterLast('.').ifBlank { "View" }
        val flags = buildList {
            if (isClickable) add("clickable")
            if (isEditable) add("editable")
            if (isScrollable) add("scrollable")
            if (isFocused) add("focused")
            if (isSelected) add("selected")
            if (isChecked) add("checked")
            if (isPassword) add("password")
        }.joinToString(",")
        val flagsBlock = if (flags.isNotEmpty()) " [$flags]" else ""
        return "$index. [$simpleClass] \"$bestLabel\"$flagsBlock @(${bounds.centerX()},${bounds.centerY()})"
    }
}

data class ScreenSnapshot(
    val packageName: String = "",
    val elements: List<ScreenElementNode> = emptyList(),
    val capturedAtMs: Long = System.currentTimeMillis(),
    val windowTitle: String = "",
    val isImeVisible: Boolean = false,
    val imeBounds: Rect? = null,
    val rootCount: Int = 1,
    val isLoading: Boolean = false
) {
    val readableSummary: String
        get() {
            if (elements.isEmpty()) return "Screen ($packageName): no interactive or readable elements detected."
            return buildString {
                appendLine("App: $packageName (${elements.size} elements)")
                elements.forEach { appendLine(it.toIndexedLine()) }
            }.trim()
        }

    fun signatureKey(): String {
        val topLabels = elements.take(12).joinToString("|") { it.fingerprint }
        return "$packageName|${elements.size}|$topLabels"
    }
}

data class AutomationOutcome(
    val success: Boolean,
    val message: String,
    val verificationDetail: String = "",
    val suggestedStrategy: SuggestedRetryStrategy = SuggestedRetryStrategy.NONE,
    val packageName: String = ""
)

data class ScreenActionResult(
    val success: Boolean,
    val actionName: String,
    val message: String,
    val verificationDetail: String = "",
    val suggestedStrategy: SuggestedRetryStrategy = SuggestedRetryStrategy.NONE,
    val packageName: String = "",
    val targetLabel: String = "",
    val tapMethod: String = "accessibility"
) {
    fun toAutomationOutcome(): AutomationOutcome = AutomationOutcome(
        success = success,
        message = message,
        verificationDetail = verificationDetail,
        suggestedStrategy = suggestedStrategy,
        packageName = packageName
    )

    fun localizedMessage(language: AppLanguage): String = when (language) {
        AppLanguage.ENGLISH -> message
        AppLanguage.ROMAN_URDU -> if (success) "Ho gaya: $message" else "Masla aaya: $message"
        AppLanguage.URDU -> if (success) "مکمل ہو گیا: $message" else "مسئلہ پیش آیا: $message"
        AppLanguage.HINDI -> if (success) "हो गया: $message" else "समस्या आई: $message"
    }

    fun toStructuredJson(): JSONObject = JSONObject().apply {
        put("success", success)
        put("action", actionName)
        put("message", message)
        put("verificationDetail", verificationDetail)
        put("suggestedStrategy", suggestedStrategy.name)
        put("packageName", packageName)
        put("targetLabel", targetLabel)
        put("tapMethod", tapMethod)
    }
}

object ScreenReaderV2 {
    const val MAX_DEPTH = 30
    const val MAX_ELEMENTS = 80
    private val NON_TEXT_CHARS = Regex("\\s+")

    fun isMeaningfulNode(
        text: String,
        desc: String,
        clickable: Boolean,
        editable: Boolean,
        scrollable: Boolean,
        visibleToUser: Boolean
    ): Boolean {
        if (!visibleToUser) return false
        return clickable || editable || scrollable || text.isNotBlank() || desc.isNotBlank()
    }

    fun collectElementsFromRoots(
        roots: List<AccessibilityNodeInfo>,
        primaryPackageHint: String = "",
        screenWidth: Int = 1080,
        screenHeight: Int = 2400
    ): ScreenSnapshot {
        if (roots.isEmpty()) return ScreenSnapshot()
        val packageCounts = mutableMapOf<String, Int>()
        roots.forEach { r ->
            val pkg = r.packageName?.toString().orEmpty()
            if (pkg.isNotBlank()) packageCounts[pkg] = (packageCounts[pkg] ?: 0) + 1
        }
        val inferredPrimaryPkg = primaryPackageHint.ifBlank {
            roots.firstOrNull {
                val p = it.packageName?.toString().orEmpty()
                p.isNotBlank() && p != "com.android.systemui" && !p.contains("inputmethod")
            }?.packageName?.toString()
                ?: roots.firstOrNull()?.packageName?.toString().orEmpty()
        }

        val hasExternalPrimary = inferredPrimaryPkg.isNotBlank() && inferredPrimaryPkg != "com.android.systemui"
        val rawNodes = mutableListOf<ScreenElementNode>()
        var imeVisible = false
        var imeRect: Rect? = null

        fun traverse(node: AccessibilityNodeInfo?, depth: Int, layer: Int, pkgFallback: String) {
            if (node == null || depth > MAX_DEPTH || rawNodes.size >= 240) return
            val pkg = node.packageName?.toString().orEmpty().ifBlank { pkgFallback }
            if (pkg.contains("inputmethod", ignoreCase = true)) {
                imeVisible = true
                val b = Rect()
                node.getBoundsInScreen(b)
                if (!b.isEmpty) imeRect = b
                return
            }
            if (hasExternalPrimary && pkg == "com.android.systemui") return

            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            val isVisible = node.isVisibleToUser && bounds.width() > 4 && bounds.height() > 4 &&
                bounds.right > 0 && bounds.bottom > 0 && bounds.left < screenWidth && bounds.top < screenHeight

            var text = node.text?.toString().orEmpty().trim()
            val desc = node.contentDescription?.toString().orEmpty().trim()

            // Merge immediate non-clickable child labels onto clickable container if container lacks label
            if (isVisible && node.isClickable && text.isBlank() && desc.isBlank() && !node.isPassword) {
                val childTexts = mutableListOf<String>()
                for (i in 0 until node.childCount) {
                    val ch = node.getChild(i) ?: continue
                    val ct = ch.text?.toString().orEmpty().trim().ifBlank { ch.contentDescription?.toString().orEmpty().trim() }
                    if (ct.isNotBlank() && !ch.isPassword) childTexts.add(ct)
                }
                if (childTexts.isNotEmpty()) {
                    text = childTexts.distinct().take(2).joinToString(" • ")
                }
            }

            if (isMeaningfulNode(text, desc, node.isClickable, node.isEditable, node.isScrollable, isVisible)) {
                rawNodes.add(
                    ScreenElementNode(
                        id = "${pkg}_${depth}_${rawNodes.size}",
                        text = if (node.isPassword) "[PASSWORD_HIDDEN]" else text.replace(NON_TEXT_CHARS, " "),
                        contentDescription = if (node.isPassword) "" else desc.replace(NON_TEXT_CHARS, " "),
                        className = node.className?.toString().orEmpty(),
                        viewIdResourceName = node.viewIdResourceName?.toString().orEmpty(),
                        isClickable = node.isClickable,
                        isLongClickable = node.isLongClickable,
                        isScrollable = node.isScrollable,
                        isEditable = node.isEditable,
                        bounds = bounds,
                        isFocused = node.isFocused,
                        isSelected = node.isSelected,
                        isChecked = node.isChecked,
                        isPassword = node.isPassword,
                        isEnabled = node.isEnabled,
                        packageName = pkg,
                        depth = depth,
                        windowLayer = layer
                    )
                )
            }

            for (i in 0 until node.childCount) {
                traverse(node.getChild(i), depth + 1, layer, pkg)
            }
        }

        roots.forEachIndexed { idx, root ->
            traverse(root, 0, idx, inferredPrimaryPkg)
        }

        // Prioritize dialog/topmost layers, then interactive controls, deduplicated by bounds+label
        val sorted = rawNodes
            .sortedWith(
                compareByDescending<ScreenElementNode> { it.windowLayer }
                    .thenByDescending { it.isEditable || it.isClickable }
                    .thenBy { it.bounds.top }
                    .thenBy { it.bounds.left }
            )
            .distinctBy { "${it.bestLabel}|${it.bounds.centerX() / 12}|${it.bounds.centerY() / 12}" }
            .take(MAX_ELEMENTS)
            .mapIndexed { i, node -> node.copy(index = i + 1) }

        return ScreenSnapshot(
            packageName = inferredPrimaryPkg,
            elements = sorted,
            capturedAtMs = System.currentTimeMillis(),
            isImeVisible = imeVisible,
            imeBounds = imeRect,
            rootCount = roots.size,
            isLoading = sorted.isEmpty() || sorted.all { it.bestLabel.contains("loading", true) || it.bestLabel.contains("ProgressBar", true) }
        )
    }

    fun computeSwipeSafeBounds(
        screenWidth: Int,
        screenHeight: Int,
        imeBounds: Rect? = null
    ): Rect {
        val left = (screenWidth * 0.12f).toInt()
        val right = (screenWidth * 0.88f).toInt()
        val top = (screenHeight * 0.20f).toInt()
        val bottomLimit = imeBounds?.top?.minus(48) ?: (screenHeight * 0.82f).toInt()
        val bottom = bottomLimit.coerceAtLeast(top + 200).coerceAtMost(screenHeight - 80)
        return Rect(left, top, right, bottom)
    }

    fun findBestLiveMatchForSnapshotNode(
        target: ScreenElementNode,
        candidates: List<ScreenElementNode>
    ): ScreenElementNode? {
        if (candidates.isEmpty()) return null
        if (target.viewIdResourceName.isNotBlank()) {
            candidates.firstOrNull {
                it.viewIdResourceName == target.viewIdResourceName &&
                    abs(it.bounds.centerY() - target.bounds.centerY()) < 260
            }?.let { return it }
        }
        return candidates
            .filter { it.bestLabel.equals(target.bestLabel, ignoreCase = true) || it.fingerprint == target.fingerprint }
            .minByOrNull {
                abs(it.bounds.centerX() - target.bounds.centerX()) + abs(it.bounds.centerY() - target.bounds.centerY())
            }
    }
}
