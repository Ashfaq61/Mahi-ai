package com.example.automation

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.example.preferences.AppLanguage
import com.example.vision.NotificationUnderstandingManager
import org.json.JSONObject
import java.util.UUID
import kotlin.math.abs

data class ScreenActionResult(
    val success: Boolean,
    val changed: Boolean = false,
    val stale: Boolean = false,
    val packageName: String = "",
    val newSummary: String = "",
    val failureReason: String = "",
    val hint: String = "",
    val endOfList: Boolean = false,
    val readBackText: String? = null
) {
    fun toAutomationOutcome(fallbackSuccessMsg: String = "Action completed."): AutomationOutcome {
        val msg = when {
            success -> buildString {
                if (!readBackText.isNullOrBlank()) {
                    append("Typed \"$readBackText\"")
                } else {
                    append(fallbackSuccessMsg)
                }
                if (endOfList) append(" (Reached end of list)")
                if (newSummary.isNotBlank()) append(" • $newSummary")
            }
            else -> buildString {
                append(failureReason.ifBlank { "Screen action could not complete." })
                if (hint.isNotBlank()) append(" Hint: $hint")
            }
        }
        return AutomationOutcome(
            success = success,
            message = msg,
            verificationDetail = newSummary.ifBlank { failureReason }
        )
    }

    fun localizedMessage(language: AppLanguage): String {
        if (success) {
            return when (language) {
                AppLanguage.ROMAN_URDU -> if (endOfList) "Ho gaya — list ke end tak pahunch gaye." else "Ho gaya! ${newSummary.take(120)}"
                AppLanguage.URDU -> if (endOfList) "مکمل ہو گیا — لسٹ کے آخر تک پہنچ گئے۔" else "ہو گیا! ${newSummary.take(120)}"
                AppLanguage.ENGLISH -> if (endOfList) "Done — reached the end of the list." else newSummary.ifBlank { "Action completed cleanly." }
            }
        }
        val reason = failureReason.ifBlank { "Screen action failed." }
        val tip = hint.takeIf { it.isNotBlank() }
        return when (language) {
            AppLanguage.ROMAN_URDU -> if (tip != null) "$reason ($tip)" else reason
            AppLanguage.URDU -> if (tip != null) "$reason ($tip)" else reason
            AppLanguage.ENGLISH -> if (tip != null) "$reason Hint: $tip" else reason
        }
    }

    fun toStructuredJson(): String {
        return JSONObject().apply {
            put("success", success)
            put("changed", changed)
            put("stale", stale)
            put("packageName", packageName)
            put("newSummary", newSummary)
            put("failureReason", failureReason)
            put("hint", hint)
            if (endOfList) put("endOfList", true)
            if (!readBackText.isNullOrBlank()) put("readBackText", readBackText)
        }.toString()
    }
}

object ScreenReaderV2 {
    const val MAX_DEPTH = 40
    const val MAX_RAW_NODES = 600
    const val TIME_BUDGET_MS = 300L
    const val MAX_OUTPUT_ELEMENTS = 80

    private data class CandidateItem(
        val element: ScreenElementNode,
        val liveNode: AccessibilityNodeInfo?,
        val score: Int
    )

    fun capture(
        roots: List<Pair<AccessibilityNodeInfo, Boolean>>, // root to isDialogWindow
        imeBounds: Rect?,
        screenWidth: Int,
        screenHeight: Int,
        nowMs: Long = System.currentTimeMillis()
    ): Pair<ScreenSnapshot, List<AccessibilityNodeInfo?>> {
        val startNs = System.nanoTime()
        val budgetNs = TIME_BUDGET_MS * 1_000_000L
        val safeW = screenWidth.coerceAtLeast(100)
        val safeH = screenHeight.coerceAtLeast(100)

        val primaryRoot = roots.firstOrNull()?.first
        val primaryPkg = primaryRoot?.packageName?.toString().orEmpty()
        val rawVisited = intArrayOf(0)
        val candidates = mutableListOf<CandidateItem>()
        val seenDedupKeys = HashSet<String>()

        for ((windowRoot, isDialogWin) in roots) {
            if (rawVisited[0] >= MAX_RAW_NODES) break
            if ((System.nanoTime() - startNs) >= budgetNs) break
            val pkg = windowRoot.packageName?.toString().orEmpty()
            // Exclude systemui notification/status bar chrome unless active foreground IS systemui (quick settings/shade)
            if (pkg == "com.android.systemui" && primaryPkg.isNotBlank() && primaryPkg != "com.android.systemui") {
                continue
            }
            traverseRecursive(
                node = windowRoot,
                depth = 0,
                isDialogWindow = isDialogWin,
                screenW = safeW,
                screenH = safeH,
                startNs = startNs,
                budgetNs = budgetNs,
                rawVisited = rawVisited,
                seenKeys = seenDedupKeys,
                out = candidates
            )
        }

        val ranked = candidates
            .sortedWith(compareByDescending<CandidateItem> { it.score }
                .thenBy { it.element.bounds.top }
                .thenBy { it.element.bounds.left })
            .take(MAX_OUTPUT_ELEMENTS)

        // Restore top-to-bottom visual reading order for the selected top 80 elements
        val ordered = ranked.sortedWith(
            compareBy<CandidateItem>(
                {
                    when (it.element.region) {
                        "dialog" -> 0
                        "top_bar" -> 1
                        "content" -> 2
                        "bottom_bar" -> 3
                        else -> 4
                    }
                },
                { it.element.bounds.top },
                { it.element.bounds.left }
            )
        )

        val finalElements = ArrayList<ScreenElementNode>(ordered.size)
        val finalLiveNodes = ArrayList<AccessibilityNodeInfo?>(ordered.size)
        ordered.forEachIndexed { idx, item ->
            val numbered = item.element.copy(index = idx + 1)
            finalElements.add(numbered)
            finalLiveNodes.add(item.liveNode)
        }

        val isImeOpen = imeBounds != null && !imeBounds.isEmpty
        val summaryLine = buildOneLineSummary(primaryPkg, finalElements, isImeOpen)
        val snap = ScreenSnapshot(
            packageName = primaryPkg,
            elements = finalElements,
            capturedAtMs = nowMs,
            snapshotId = UUID.randomUUID().toString(),
            isKeyboardOpen = isImeOpen,
            summaryLine = summaryLine,
            rawNodeCount = rawVisited[0],
            imeBounds = imeBounds ?: Rect()
        )
        return snap to finalLiveNodes
    }

    fun classifyRegion(bounds: Rect, isDialogWindow: Boolean, screenH: Int): String {
        if (isDialogWindow) return "dialog"
        if (bounds.bottom <= (screenH * 0.14f).toInt()) return "top_bar"
        if (bounds.top >= (screenH * 0.84f).toInt()) return "bottom_bar"
        return "content"
    }

    fun computeFingerprint(
        viewId: String,
        className: String,
        label: String,
        cx: Int,
        cy: Int,
        region: String
    ): String {
        val shortId = viewId.substringAfterLast("/")
        val cleanLbl = label.take(36).lowercase().replace(Regex("\\s+"), " ")
        return "$shortId|$className|$cleanLbl|${cx / 24}|${cy / 24}|$region"
    }

    fun collectCandidateRootsWithDialogFlags(
        activeRoot: AccessibilityNodeInfo?,
        windows: List<AccessibilityWindowInfo>?
    ): List<Pair<AccessibilityNodeInfo, Boolean>> {
        val result = mutableListOf<Pair<AccessibilityNodeInfo, Boolean>>()
        val activePkg = activeRoot?.packageName?.toString().orEmpty()
        if (activeRoot != null) {
            result.add(activeRoot to false)
        }
        try {
            windows?.forEach { win ->
                if (win.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY ||
                    win.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
                ) {
                    return@forEach
                }
                val wRoot = win.root ?: return@forEach
                if (wRoot == activeRoot) return@forEach
                val pkg = wRoot.packageName?.toString().orEmpty()
                if (pkg == "com.android.systemui" && activePkg.isNotBlank() && activePkg != "com.android.systemui") {
                    return@forEach
                }
                val isDialog = win.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                    (win.isFocused || win.isActive)
                result.add(wRoot to isDialog)
            }
        } catch (_: Exception) {
        }
        return result
    }

    fun extractImeBounds(windows: List<AccessibilityWindowInfo>?): Rect? {
        return try {
            val imeWin = windows?.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } ?: return null
            val r = Rect()
            imeWin.getBoundsInScreen(r)
            if (!r.isEmpty && r.height() > 60) r else null
        } catch (_: Exception) {
            null
        }
    }

    fun buildOneLineSummary(
        packageName: String,
        elements: List<ScreenElementNode>,
        isKeyboardOpen: Boolean
    ): String {
        val appLabel = AppLauncherManager.KNOWN_APPS.firstOrNull { app ->
            app.packageCandidates.any { it.equals(packageName, ignoreCase = true) }
        }?.canonicalName ?: packageName.substringAfterLast(".").replaceFirstChar { it.uppercase() }.ifBlank { "Screen" }

        val bottomTabs = elements
            .filter { it.region == "bottom_bar" && it.isClickable && it.bestLabel.isNotBlank() && !it.isPassword }
            .map { it.bestLabel.take(20) }
            .distinct()
            .take(5)

        val headlines = elements
            .filter { !it.isPassword && it.region == "content" && it.bestLabel.length in 3..42 }
            .map { it.bestLabel.replace('\n', ' ').trim() }
            .distinct()
            .take(3)

        val dialogPresent = elements.any { it.region == "dialog" }

        return buildString {
            append(appLabel)
            append(": ")
            if (dialogPresent) append("[Dialog] ")
            if (isKeyboardOpen) append("[Keyboard open] ")
            if (headlines.isNotEmpty()) {
                append(headlines.joinToString(" | "))
            } else {
                append("${elements.size} controls")
            }
            if (bottomTabs.isNotEmpty()) {
                append(" | tabs: ${bottomTabs.joinToString(", ")}")
            }
        }.take(200)
    }

    fun computeSwipeSafeBounds(screenW: Int, screenH: Int, imeBounds: Rect?): Rect {
        val left = (screenW * 0.10f).toInt().coerceAtLeast(16)
        val right = (screenW * 0.90f).toInt().coerceAtLeast(left + 80)
        val top = (screenH * 0.18f).toInt().coerceAtLeast(48)
        val rawBottom = (screenH * 0.78f).toInt()
        val imeLimit = if (imeBounds != null && !imeBounds.isEmpty) imeBounds.top - 24 else rawBottom
        val bottom = minOf(rawBottom, imeLimit).coerceAtLeast(top + 100)
        return Rect(left, top, right, bottom)
    }

    fun findBestLiveMatchForSnapshotNode(
        target: ScreenElementNode,
        liveElements: List<ScreenElementNode>,
        liveNodes: List<AccessibilityNodeInfo?>
    ): Pair<ScreenElementNode, AccessibilityNodeInfo?>? {
        if (target.fingerprint.isNotBlank()) {
            val exactIdx = liveElements.indexOfFirst { it.fingerprint == target.fingerprint }
            if (exactIdx >= 0) return liveElements[exactIdx] to liveNodes.getOrNull(exactIdx)
        }
        // Fallback: same viewId or exact label within 140px drift (smooth scroll jitter)
        val driftMatches = liveElements.withIndex().filter { (_, cur) ->
            val labelMatches = target.bestLabel.isNotBlank() && cur.bestLabel.equals(target.bestLabel, ignoreCase = true)
            val idMatches = target.viewIdResourceName.isNotBlank() && cur.viewIdResourceName == target.viewIdResourceName
            (labelMatches || idMatches) &&
                abs(cur.centerX - target.centerX) <= 140 &&
                abs(cur.centerY - target.centerY) <= 180
        }
        val best = driftMatches.minByOrNull { (_, cur) ->
            abs(cur.centerX - target.centerX) + abs(cur.centerY - target.centerY)
        } ?: return null
        return best.value to liveNodes.getOrNull(best.index)
    }

    private fun traverseRecursive(
        node: AccessibilityNodeInfo,
        depth: Int,
        isDialogWindow: Boolean,
        screenW: Int,
        screenH: Int,
        startNs: Long,
        budgetNs: Long,
        rawVisited: IntArray,
        seenKeys: MutableSet<String>,
        out: MutableList<CandidateItem>
    ) {
        if (depth > MAX_DEPTH || rawVisited[0] >= MAX_RAW_NODES) return
        if ((System.nanoTime() - startNs) >= budgetNs) return
        rawVisited[0]++

        val isPwd = node.isPassword
        val rawRect = Rect()
        node.getBoundsInScreen(rawRect)
        val clipped = Rect(
            rawRect.left.coerceIn(0, screenW),
            rawRect.top.coerceIn(0, screenH),
            rawRect.right.coerceIn(0, screenW),
            rawRect.bottom.coerceIn(0, screenH)
        )

        val visibleAndSized = node.isVisibleToUser && clipped.width() > 4 && clipped.height() > 4
        if (visibleAndSized) {
            val rawText = if (isPwd) "" else node.text?.toString()?.trim().orEmpty()
            val rawDesc = if (isPwd) "" else node.contentDescription?.toString()?.trim().orEmpty()
            val rawHint = if (!isPwd && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                node.hintText?.toString()?.trim().orEmpty()
            } else ""
            val rawState = if (!isPwd && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                node.stateDescription?.toString()?.trim().orEmpty()
            } else ""
            val rawTooltip = if (!isPwd && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                node.tooltipText?.toString()?.trim().orEmpty()
            } else ""

            val mergedDesc = if (!isPwd && rawText.isBlank() && rawDesc.isBlank() && (node.isClickable || node.isCheckable)) {
                collectDescendantSummary(node, maxDepth = 3).take(120)
            } else {
                rawDesc
            }

            val safeText = NotificationUnderstandingManager.redactSensitiveText(rawText).first
            val safeDesc = NotificationUnderstandingManager.redactSensitiveText(mergedDesc).first
            val safeHint = NotificationUnderstandingManager.redactSensitiveText(rawHint).first
            val safeState = NotificationUnderstandingManager.redactSensitiveText(rawState).first
            val safeTooltip = NotificationUnderstandingManager.redactSensitiveText(rawTooltip).first

            val resId = node.viewIdResourceName.orEmpty()
            val shortCls = node.className?.toString()?.substringAfterLast(".").orEmpty()
            val effectiveLabel = safeText.ifBlank { safeDesc }.ifBlank { safeHint }.ifBlank { resId.substringAfterLast("/") }

            val meaningful = isPwd ||
                effectiveLabel.isNotBlank() ||
                node.isClickable ||
                node.isEditable ||
                node.isScrollable ||
                node.isCheckable

            // Drop purely decorative spacers/containers without text or interactivity
            val purelyDecorative = !meaningful || (effectiveLabel.isBlank() && !node.isClickable && !node.isEditable && !node.isScrollable)
            if (!purelyDecorative) {
                val region = classifyRegion(clipped, isDialogWindow || shortCls.contains("Dialog", true), screenH)
                val dedupKey = "${clipped.left / 6}:${clipped.top / 6}:${clipped.right / 6}:${clipped.bottom / 6}|${effectiveLabel.take(32)}"
                if (seenKeys.add(dedupKey)) {
                    val fp = computeFingerprint(resId, shortCls, effectiveLabel, clipped.centerX(), clipped.centerY(), region)
                    val elId = "el_${fp.hashCode().toUInt().toString(16)}"
                    val element = ScreenElementNode(
                        id = elId,
                        text = safeText,
                        contentDescription = safeDesc,
                        className = shortCls,
                        viewIdResourceName = resId,
                        isClickable = node.isClickable,
                        isLongClickable = node.isLongClickable,
                        isScrollable = node.isScrollable,
                        isEditable = node.isEditable,
                        bounds = clipped,
                        index = 0,
                        isPassword = isPwd,
                        isFocused = node.isFocused,
                        isSelected = node.isSelected,
                        isChecked = readIsChecked(node),
                        hintText = safeHint,
                        stateDescription = safeState,
                        tooltipText = safeTooltip,
                        region = region,
                        fingerprint = fp
                    )
                    val score = scoreElement(element)
                    out.add(CandidateItem(element, node, score))
                }
            }
        }

        for (i in 0 until node.childCount) {
            if (rawVisited[0] >= MAX_RAW_NODES) break
            if ((System.nanoTime() - startNs) >= budgetNs) break
            val child = node.getChild(i) ?: continue
            traverseRecursive(
                node = child,
                depth = depth + 1,
                isDialogWindow = isDialogWindow,
                screenW = screenW,
                screenH = screenH,
                startNs = startNs,
                budgetNs = budgetNs,
                rawVisited = rawVisited,
                seenKeys = seenKeys,
                out = out
            )
        }
    }

    private fun collectDescendantSummary(node: AccessibilityNodeInfo, maxDepth: Int): String {
        val parts = LinkedHashSet<String>()
        fun walk(cur: AccessibilityNodeInfo, d: Int) {
            if (d > maxDepth || parts.size >= 4) return
            if (cur.isPassword) return
            val t = cur.text?.toString()?.trim().orEmpty()
            val c = cur.contentDescription?.toString()?.trim().orEmpty()
            val piece = t.ifBlank { c }
            if (piece.isNotBlank() && cur !== node) {
                parts.add(piece.take(40))
            }
            for (i in 0 until cur.childCount) {
                val ch = cur.getChild(i) ?: continue
                walk(ch, d + 1)
            }
        }
        walk(node, 0)
        return parts.joinToString(" • ")
    }

    private fun readIsChecked(node: AccessibilityNodeInfo): Boolean {
        return if (Build.VERSION.SDK_INT >= 36) {
            node.checked == AccessibilityNodeInfo.CHECKED_STATE_TRUE
        } else {
            @Suppress("DEPRECATION")
            node.isChecked
        }
    }

    private fun scoreElement(el: ScreenElementNode): Int {
        var score = 0
        if (el.region == "dialog") score += 400
        if (el.isEditable) score += 300
        if (el.isPassword) score += 150
        if (el.isClickable || el.isLongClickable) score += 220
        if (el.region == "bottom_bar" && el.isClickable) score += 80
        if (el.region == "top_bar" && el.isClickable) score += 60
        if (el.isScrollable) score += 90
        if (el.bestLabel.isNotBlank()) score += 50
        if (el.hintText.isNotBlank()) score += 30
        if (el.isFocused || el.isSelected) score += 40
        return score
    }
}
