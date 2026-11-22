package com.example.automation

import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

enum class AgentActionType {
    OPEN_APP,
    YOUTUBE_SEARCH,
    READ_SCREEN,
    TAP_BY_INDEX,
    TAP_BY_LABEL,
    TYPE_TEXT,
    SCROLL_DOWN,
    SCROLL_UP,
    BACK,
    CALL_CONTACT,
    PURCHASE_ACTION,
    DELETE_ACTION
}

enum class SensitiveCategory {
    NONE,
    MESSAGE,
    CALL,
    DELETE,
    PURCHASE,
    INSTALL
}

data class AgentStepItem(
    val id: String = UUID.randomUUID().toString(),
    val actionType: AgentActionType,
    val title: String,
    val param: String,
    val status: String = "Pending",
    val sensitivity: SensitiveCategory = SensitiveCategory.NONE
)

data class PendingConfirmationRequest(
    val id: String = UUID.randomUUID().toString(),
    val category: SensitiveCategory,
    val title: String,
    val detail: String,
    val onDecision: ((Boolean) -> Unit)? = null
)

data class AgentExecutionHistory(
    val id: String = UUID.randomUUID().toString(),
    val goal: String,
    val summary: String,
    val success: Boolean,
    val stepsExecuted: Int,
    val timestampMs: Long = System.currentTimeMillis()
)

data class ScreenTaskResult(
    val completed: Boolean,
    val verified: Boolean,
    val stepsExecuted: Int,
    val summary: String,
    val actionsTaken: List<String> = emptyList()
)

class AutonomousAgentEngine(
    private val context: Context,
    private val deviceControl: DeviceControlHelper = DeviceControlHelper(context),
    private val labelMemoryStore: AppLabelMemoryStore? = null
) {
    interface ScreenAgentDriver {
        fun isAccessibilityEnabled(): Boolean
        suspend fun readScreen(): Result<ScreenSnapshot>
        suspend fun openApp(appName: String): AutomationOutcome
        suspend fun tap(index: Int? = null, query: String? = null, viewId: String? = null, x: Float? = null, y: Float? = null): AutomationOutcome
        suspend fun typeText(index: Int? = null, query: String? = null, text: String, submit: Boolean = true, clearFirst: Boolean = false): AutomationOutcome
        suspend fun scroll(forward: Boolean): AutomationOutcome
        suspend fun swipe(direction: SwipeDirection): AutomationOutcome
        suspend fun pressBack(): AutomationOutcome
        suspend fun captureScreenJpeg(): Result<ByteArray>
        fun displaySize(): Pair<Int, Int>
    }

    companion object {
        const val MAX_LOOP_STEPS = 12

        fun classifySensitivity(action: AgentActionType, labelOrGoal: String, param: String): SensitiveCategory {
            val combined = "$labelOrGoal $param".lowercase()
            return when {
                action == AgentActionType.PURCHASE_ACTION || combined.contains("pay ") || combined.contains("buy") || combined.contains("purchase") -> SensitiveCategory.PURCHASE
                action == AgentActionType.DELETE_ACTION || combined.contains("delete") || combined.contains("remove account") -> SensitiveCategory.DELETE
                action == AgentActionType.CALL_CONTACT || combined.contains("call ") -> SensitiveCategory.CALL
                combined.contains("install apk") -> SensitiveCategory.INSTALL
                combined.contains("send message") || combined.contains("whatsapp") || combined.contains("reply") -> SensitiveCategory.MESSAGE
                else -> SensitiveCategory.NONE
            }
        }
    }

    private val _planSteps = MutableStateFlow<List<AgentStepItem>>(emptyList())
    val planSteps: StateFlow<List<AgentStepItem>> = _planSteps.asStateFlow()

    private val _history = MutableStateFlow<List<AgentExecutionHistory>>(emptyList())
    val history: StateFlow<List<AgentExecutionHistory>> = _history.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _statusMessage = MutableStateFlow("Agent idle")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    @Volatile
    private var stopRequested = false

    fun requestEmergencyStop() {
        stopRequested = true
        _isRunning.value = false
        _statusMessage.value = "Emergency STOP activated."
        MahiAccessibilityService.setAutopilotObserving(false)
    }

    fun clearHistory() {
        _history.value = emptyList()
    }

    fun buildHeuristicPlan(goal: String): List<AgentStepItem> {
        val clean = goal.trim()
        val steps = mutableListOf<AgentStepItem>()
        val subGoals = AppSkills.decomposeGoalIntoSubGoals(clean)
        for (part in subGoals) {
            val lower = part.lowercase()
            val tapMatch = Regex("(?i)(?:tap|click)\\s+(?:element\\s+)?#?(\\d+)").find(part)
            when {
                tapMatch != null -> {
                    val idx = tapMatch.groupValues[1]
                    steps.add(AgentStepItem(actionType = AgentActionType.TAP_BY_INDEX, title = "Tap element #$idx", param = idx))
                }
                (lower.contains("kholo") || lower.contains("open ")) && !lower.contains("search") -> {
                    val app = AppSkills.detectTargetAppInGoal(part) ?: AppLauncherManager.normalizeAppQuery(part)
                    steps.add(AgentStepItem(actionType = AgentActionType.OPEN_APP, title = "Open $app", param = app))
                }
                lower.contains("youtube") && (lower.contains("kholo") || lower.contains("open")) -> {
                    steps.add(AgentStepItem(actionType = AgentActionType.OPEN_APP, title = "Open YouTube", param = "youtube"))
                }
                lower.contains("search karo") || lower.contains("search ") -> {
                    val q = part.replace(Regex("(?i)\\b(search karo|search|youtube|facebook|par|pe|mein|aur)\\b"), " ")
                        .replace(Regex("\\s+"), " ").trim()
                    val isYt = clean.contains("youtube", ignoreCase = true)
                    steps.add(
                        AgentStepItem(
                            actionType = if (isYt) AgentActionType.YOUTUBE_SEARCH else AgentActionType.TYPE_TEXT,
                            title = "Search '$q'",
                            param = q
                        )
                    )
                }
                lower.contains("like") || lower.contains("pasand") -> {
                    steps.add(AgentStepItem(actionType = AgentActionType.TAP_BY_LABEL, title = "Tap Like", param = "Like"))
                }
                lower.contains("subscribe") || lower.contains("follow") -> {
                    steps.add(AgentStepItem(actionType = AgentActionType.TAP_BY_LABEL, title = "Tap Subscribe/Follow", param = "Subscribe"))
                }
                lower.contains("save") || lower.contains("mahfooz") -> {
                    steps.add(AgentStepItem(actionType = AgentActionType.TAP_BY_LABEL, title = "Tap Save", param = "Save"))
                }
                else -> {
                    steps.add(AgentStepItem(actionType = AgentActionType.READ_SCREEN, title = "Inspect screen for: $part", param = part))
                }
            }
        }
        _planSteps.value = steps
        return steps
    }

    fun defaultAccessibilityDriver(): ScreenAgentDriver = object : ScreenAgentDriver {
        override fun isAccessibilityEnabled(): Boolean =
            MahiAccessibilityService.isAccessibilityServiceEnabledInSettings(context)

        override suspend fun readScreen(): Result<ScreenSnapshot> {
            val snap = MahiAccessibilityService.readScreenWithRetry(context)
            return if (snap.elements.isNotEmpty() || snap.packageName.isNotBlank()) {
                Result.success(snap)
            } else {
                Result.failure(IllegalStateException("Unable to read active screen nodes."))
            }
        }

        override suspend fun openApp(appName: String): AutomationOutcome =
            deviceControl.launchAppByName(appName)

        override suspend fun tap(index: Int?, query: String?, viewId: String?, x: Float?, y: Float?): AutomationOutcome =
            MahiAccessibilityService.tapElementAwait(index, query, viewId, x, y)

        override suspend fun typeText(index: Int?, query: String?, text: String, submit: Boolean, clearFirst: Boolean): AutomationOutcome =
            MahiAccessibilityService.typeTextAwait(text, index, query, clearFirst, submit)

        override suspend fun scroll(forward: Boolean): AutomationOutcome =
            MahiAccessibilityService.scrollScreenAwait(forward)

        override suspend fun swipe(direction: SwipeDirection): AutomationOutcome =
            MahiAccessibilityService.swipeScreenAwait(direction)

        override suspend fun pressBack(): AutomationOutcome =
            MahiAccessibilityService.navigateBack()

        override suspend fun captureScreenJpeg(): Result<ByteArray> =
            MahiAccessibilityService.takeScreenshotJpeg()

        override fun displaySize(): Pair<Int, Int> = 1080 to 2400
    }

    suspend fun runScreenTask(
        goal: String,
        driver: ScreenAgentDriver = defaultAccessibilityDriver(),
        groundingProvider: GuiGroundingProvider? = null,
        stepDelayMs: Long = 220L,
        verifyDelayMs: Long = 240L
    ): ScreenTaskResult {
        stopRequested = false
        AppSkills.detectBulkSpamViolation(goal)?.let { violation ->
            return ScreenTaskResult(completed = false, verified = false, stepsExecuted = 0, summary = violation)
        }
        if (!driver.isAccessibilityEnabled()) {
            return ScreenTaskResult(
                completed = false,
                verified = false,
                stepsExecuted = 0,
                summary = "Accessibility Service is disabled. Enable Mahi Accessibility Service in Settings."
            )
        }

        _isRunning.value = true
        _statusMessage.value = "Running: $goal"
        val actionsTaken = mutableListOf<String>()
        var steps = 0

        try {
            val targetApp = AppSkills.detectTargetAppInGoal(goal)
            val snapInitial = driver.readScreen().getOrNull()
            if (targetApp != null && snapInitial != null && !snapInitial.packageName.contains(targetApp, ignoreCase = true)) {
                val appMapping = AppLauncherManager.matchKnownApp(targetApp)
                val alreadyInApp = appMapping?.packageNames?.any { it.equals(snapInitial.packageName, true) } == true
                if (!alreadyInApp) {
                    val launchRes = driver.openApp(targetApp)
                    steps++
                    actionsTaken.add("Open $targetApp: ${launchRes.message}")
                    if (stepDelayMs > 0L) delay(stepDelayMs)
                }
            }

            val lower = goal.lowercase()
            val explicitComment = AppSkills.extractExplicitCommentText(goal)
            var previousSig = ""
            var repeatSigCount = 0

            while (steps < MAX_LOOP_STEPS && !stopRequested) {
                val snap = driver.readScreen().getOrElse {
                    return ScreenTaskResult(false, false, steps, it.localizedMessage ?: "Screen read failed", actionsTaken)
                }
                AppSkills.detectSecurityOrCaptchaWall(snap)?.let { wall ->
                    return ScreenTaskResult(false, false, steps, wall, actionsTaken)
                }
                AppSkills.detectProtectedSystemOrPaywallPrompt(snap)?.let { prompt ->
                    return ScreenTaskResult(false, false, steps, prompt, actionsTaken)
                }

                val sig = snap.signatureKey()
                if (sig == previousSig) {
                    repeatSigCount++
                    if (repeatSigCount >= 3) {
                        return ScreenTaskResult(false, false, steps, "Stopped to avoid repeating unchanged screen state.", actionsTaken)
                    }
                } else {
                    repeatSigCount = 0
                }
                previousSig = sig

                val recipe = AppSkills.forPackage(snap.packageName)

                // Handle harmless popups first
                AppSkills.detectHarmlessInterruption(snap)?.let { dismissNode ->
                    driver.tap(index = dismissNode.index, query = dismissNode.bestLabel)
                    steps++
                    actionsTaken.add("Dismissed '${dismissNode.bestLabel}'")
                    if (stepDelayMs > 0L) delay(stepDelayMs)
                    continue
                }

                // Back navigation
                if (lower.contains("wapas jao") || lower.contains("go back")) {
                    val res = driver.pressBack()
                    steps++
                    actionsTaken.add("Back: ${res.message}")
                    return ScreenTaskResult(res.success, res.success, steps, res.message, actionsTaken)
                }

                // Save without sharing
                if (lower.contains("save") && (lower.contains("share mat") || lower.contains("don't share"))) {
                    val saveEl = snap.elements.firstOrNull { el ->
                        recipe.saveLabels.any { lbl -> el.bestLabel.contains(lbl, ignoreCase = true) } &&
                            !el.bestLabel.contains("share", ignoreCase = true)
                    }
                    if (saveEl != null) {
                        val res = driver.tap(index = saveEl.index, query = saveEl.bestLabel)
                        steps++
                        actionsTaken.add("Tapped Save #${saveEl.index}")
                        if (verifyDelayMs > 0L) delay(verifyDelayMs)
                        val after = driver.readScreen().getOrNull()
                        val verified = after?.elements?.any {
                            it.bestLabel.contains("Saved", true) || it.isSelected
                        } ?: res.success
                        return ScreenTaskResult(true, verified, steps, "Saved without sharing.", actionsTaken)
                    }
                }

                // Dictated comment workflow
                if (!explicitComment.isNullOrBlank()) {
                    val editBox = snap.elements.firstOrNull { el ->
                        el.isEditable && recipe.commentInputHints.any { h -> el.bestLabel.contains(h, true) }
                    } ?: snap.elements.firstOrNull { it.isEditable }

                    if (editBox != null) {
                        val res = driver.typeText(index = editBox.index, query = editBox.bestLabel, text = explicitComment, submit = true)
                        steps++
                        actionsTaken.add("Posted comment '$explicitComment'")
                        if (verifyDelayMs > 0L) delay(verifyDelayMs)
                        return ScreenTaskResult(res.success, res.success, steps, "Comment posted: $explicitComment", actionsTaken)
                    } else {
                        val cBtn = snap.elements.firstOrNull { el ->
                            recipe.commentButtonLabels.any { lbl -> el.bestLabel.contains(lbl, true) }
                        }
                        if (cBtn != null) {
                            driver.tap(index = cBtn.index, query = cBtn.bestLabel)
                            steps++
                            actionsTaken.add("Opened comments")
                            if (stepDelayMs > 0L) delay(stepDelayMs)
                            continue
                        }
                    }
                }

                // Like / Subscribe / Save single target
                val desiredLabels = when {
                    lower.contains("like") || lower.contains("pasand") -> "like" to recipe.likeLabels
                    lower.contains("subscribe") || lower.contains("follow") -> "subscribe" to recipe.subscribeFollowLabels
                    lower.contains("save") -> "save" to recipe.saveLabels
                    lower.contains("search") -> "search" to recipe.searchButtonLabels
                    else -> null
                }

                if (desiredLabels != null) {
                    val (canonical, variants) = desiredLabels
                    val learned = labelMemoryStore?.getPreferredStrategy(snap.packageName, canonical)?.matchedLabel
                    val allQueries = listOfNotNull(learned) + variants
                    val target = snap.elements.firstOrNull { el ->
                        allQueries.any { q -> el.bestLabel.equals(q, true) || el.bestLabel.contains(q, true) }
                    }
                    if (target != null) {
                        val tapRes = driver.tap(index = target.index, query = target.bestLabel, viewId = target.viewIdResourceName)
                        steps++
                        actionsTaken.add("Tapped '${target.bestLabel}'")
                        if (verifyDelayMs > 0L) delay(verifyDelayMs)
                        val verifySnap = driver.readScreen().getOrNull()
                        val changed = verifySnap?.elements?.any {
                            it.isSelected || it.isChecked ||
                                it.bestLabel.contains("Unlike", true) ||
                                it.bestLabel.contains("Liked", true) ||
                                it.bestLabel.contains("Subscribed", true) ||
                                it.bestLabel.contains("Saved", true)
                        } ?: false
                        if (changed) {
                            labelMemoryStore?.recordSuccess(snap.packageName, canonical, target.bestLabel)
                        }
                        return ScreenTaskResult(
                            completed = tapRes.success,
                            verified = changed || tapRes.success,
                            stepsExecuted = steps,
                            summary = if (changed) "Verified '$canonical' completed." else tapRes.message,
                            actionsTaken = actionsTaken
                        )
                    } else if (groundingProvider != null && SetOfMarkGrounding.isThinAccessibilityTree(snap)) {
                        val jpeg = driver.captureScreenJpeg().getOrNull()
                        val (w, h) = driver.displaySize()
                        if (jpeg != null) {
                            val pt = groundingProvider.groundTarget(jpeg, canonical, w, h)
                            if (pt != null) {
                                val res = driver.tap(x = pt.x, y = pt.y, query = canonical)
                                steps++
                                actionsTaken.add("Vision-grounded tap ($canonical)")
                                return ScreenTaskResult(res.success, res.success, steps, res.message, actionsTaken)
                            }
                        }
                    }
                }

                // Scroll once to look for control if not found yet
                driver.scroll(forward = true)
                steps++
                actionsTaken.add("Scrolled forward looking for target")
                if (stepDelayMs > 0L) delay(stepDelayMs)
            }

            return ScreenTaskResult(
                completed = false,
                verified = false,
                stepsExecuted = steps,
                summary = if (stopRequested) "Stopped by user." else "Reached $steps steps without verified completion.",
                actionsTaken = actionsTaken
            )
        } finally {
            _isRunning.value = false
            val finalRecord = AgentExecutionHistory(
                goal = goal,
                summary = _statusMessage.value,
                success = actionsTaken.isNotEmpty(),
                stepsExecuted = steps
            )
            _history.value = (listOf(finalRecord) + _history.value).take(30)
            MahiAccessibilityService.setAutopilotObserving(false)
        }
    }
}
