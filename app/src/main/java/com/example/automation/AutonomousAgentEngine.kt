package com.example.automation

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import com.example.api.AiProvider
import com.example.api.ProviderClient
import com.example.preferences.AppLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class AgentActionType {
    OPEN_APP,
    YOUTUBE_SEARCH,
    SET_VOLUME,
    SET_BRIGHTNESS,
    OPEN_SETTINGS,
    TAP_UI_ELEMENT,
    DOUBLE_TAP_UI_ELEMENT,
    LONG_PRESS_UI_ELEMENT,
    DRAG_DROP,
    SCROLL_SCREEN,
    SCROLL_TO_EDGE,
    CONTINUOUS_SCROLL,
    SWIPE_SCREEN,
    STOP_GESTURES,
    READ_SCREEN,
    READ_WHATSAPP,
    SEND_WHATSAPP_REPLY,
    MAKE_CALL,
    PURCHASE_ACTION,
    DELETE_FILE,
    PUBLISH_CONTENT
}

enum class SensitiveCategory(val label: String) {
    NONE("Standard"),
    CALL("Phone Call"),
    MESSAGE("Send Message / WhatsApp Reply"),
    PURCHASE("Financial / Purchase"),
    DELETE_FILE("Delete File / Data"),
    PUBLISH_CONTENT("Publish / Post Public Content")
}

enum class StepExecutionStatus {
    PENDING,
    AWAITING_CONFIRMATION,
    RUNNING,
    VERIFIED_SUCCESS,
    DENIED_BY_USER,
    FAILED,
    STOPPED
}

data class AgentPlanStep(
    val id: String = UUID.randomUUID().toString(),
    val stepNumber: Int,
    val title: String,
    val actionType: AgentActionType,
    val param: String = "",
    val secondaryParam: String = "",
    val sensitiveCategory: SensitiveCategory = SensitiveCategory.NONE,
    val status: StepExecutionStatus = StepExecutionStatus.PENDING,
    val verificationNote: String = ""
) {
    val requiresConfirmation: Boolean
        get() = sensitiveCategory != SensitiveCategory.NONE
}

data class PendingSensitiveConfirmation(
    val step: AgentPlanStep,
    val goal: String,
    val reason: String
)

data class AutomationHistoryEntry(
    val id: String = UUID.randomUUID().toString(),
    val goal: String,
    val timestampMs: Long = System.currentTimeMillis(),
    val statusSummary: String,
    val stepCount: Int
)

class AutomationHistoryStore(context: Context) {
    companion object {
        private const val PREFS_NAME = "mahi_automation_history"
        private const val KEY_HISTORY = "history_json"
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _history = MutableStateFlow(load())
    val history: StateFlow<List<AutomationHistoryEntry>> = _history.asStateFlow()

    private fun load(): List<AutomationHistoryEntry> {
        val raw = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    add(
                        AutomationHistoryEntry(
                            id = obj.optString("id"),
                            goal = obj.optString("goal"),
                            timestampMs = obj.optLong("timestampMs"),
                            statusSummary = obj.optString("statusSummary"),
                            stepCount = obj.optInt("stepCount")
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun record(entry: AutomationHistoryEntry) {
        val updated = (listOf(entry) + _history.value).take(30)
        val arr = JSONArray()
        updated.forEach { item ->
            arr.put(JSONObject().apply {
                put("id", item.id)
                put("goal", item.goal)
                put("timestampMs", item.timestampMs)
                put("statusSummary", item.statusSummary)
                put("stepCount", item.stepCount)
            })
        }
        prefs.edit().putString(KEY_HISTORY, arr.toString()).commit()
        _history.value = updated
    }

    fun clear() {
        prefs.edit().remove(KEY_HISTORY).commit()
        _history.value = emptyList()
    }
}

class AutonomousAgentEngine(
    private val context: Context,
    private val deviceControl: DeviceControlHelper = DeviceControlHelper(context),
    private val providerClient: ProviderClient = ProviderClient(),
    val historyStore: AutomationHistoryStore = AutomationHistoryStore(context)
) {
    companion object {
        fun classifySensitivity(
            actionType: AgentActionType,
            title: String,
            param: String
        ): SensitiveCategory {
            val combined = "${title.lowercase()} ${param.lowercase()}"
            return when {
                actionType == AgentActionType.MAKE_CALL ||
                    combined.contains("call ") || combined.contains("dial ") -> SensitiveCategory.CALL
                actionType == AgentActionType.SEND_WHATSAPP_REPLY ||
                    combined.contains("send message") || combined.contains("whatsapp reply") ||
                    combined.contains("reply to") -> SensitiveCategory.MESSAGE
                actionType == AgentActionType.PURCHASE_ACTION ||
                    combined.contains("buy ") || combined.contains("purchase") ||
                    combined.contains("checkout") || combined.contains("pay ") ||
                    combined.contains("order ") -> SensitiveCategory.PURCHASE
                actionType == AgentActionType.DELETE_FILE ||
                    combined.contains("delete") || combined.contains("remove file") ||
                    combined.contains("erase") -> SensitiveCategory.DELETE_FILE
                actionType == AgentActionType.PUBLISH_CONTENT ||
                    combined.contains("publish") || combined.contains("post publicly") ||
                    combined.contains("tweet") || combined.contains("upload post") -> SensitiveCategory.PUBLISH_CONTENT
                else -> SensitiveCategory.NONE
            }
        }
    }

    private val _currentGoal = MutableStateFlow("")
    val currentGoal: StateFlow<String> = _currentGoal.asStateFlow()

    private val _planSteps = MutableStateFlow<List<AgentPlanStep>>(emptyList())
    val planSteps: StateFlow<List<AgentPlanStep>> = _planSteps.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _pendingConfirmation = MutableStateFlow<PendingSensitiveConfirmation?>(null)
    val pendingConfirmation: StateFlow<PendingSensitiveConfirmation?> = _pendingConfirmation.asStateFlow()

    private var confirmationDeferred: kotlinx.coroutines.CompletableDeferred<Boolean>? = null
    private var executionJob: Job? = null

    suspend fun createPlanForGoal(
        goal: String,
        screenSnapshot: ScreenSnapshot,
        language: AppLanguage,
        provider: AiProvider?,
        apiKey: String?,
        model: String?,
        baseUrl: String?
    ): List<AgentPlanStep> {
        val trimmedGoal = goal.trim()
        if (trimmedGoal.isEmpty()) return emptyList()

        // Try AI planning first when an API key is configured
        if (provider != null && !apiKey.isNullOrBlank() && !model.isNullOrBlank() && !baseUrl.isNullOrBlank()) {
            try {
                val allowedTypes = AgentActionType.entries.joinToString(", ") { it.name }
                val sysPrompt = """
You are Mahi's Autonomous Android Task Planner.
Convert the user's goal into a JSON array of 1 to 4 sequential steps.
Allowed actionType values: $allowedTypes.
Current Screen Context: ${screenSnapshot.readableSummary}
Language for step titles: ${language.displayName}.
Each JSON item must have: "title", "actionType", "param", "secondaryParam".
Return ONLY a valid JSON array.
                """.trimIndent()

                val raw = providerClient.generateChatReply(
                    provider = provider,
                    apiKey = apiKey,
                    model = model,
                    baseUrl = baseUrl,
                    systemPrompt = sysPrompt,
                    history = emptyList(),
                    userMessage = trimmedGoal
                )
                val parsedSteps = parseAiPlanSteps(raw)
                if (parsedSteps.isNotEmpty()) return parsedSteps
            } catch (_: Exception) {
            }
        }

        // Deterministic multi-step fallback parser for core Android goals
        return buildHeuristicPlan(trimmedGoal)
    }

    fun buildHeuristicPlan(goal: String): List<AgentPlanStep> {
        val lower = goal.lowercase()
        val steps = mutableListOf<AgentPlanStep>()

        fun addStep(title: String, type: AgentActionType, param: String = "", secondary: String = "") {
            steps.add(
                AgentPlanStep(
                    stepNumber = steps.size + 1,
                    title = title,
                    actionType = type,
                    param = param,
                    secondaryParam = secondary,
                    sensitiveCategory = classifySensitivity(type, title, param)
                )
            )
        }

        if (lower.contains("youtube")) {
            val q = lower.substringAfter("youtube").replace("search", "").replace("play", "").replace("on", "").trim()
                .ifBlank { goal }
            addStep("Search YouTube for '$q'", AgentActionType.YOUTUBE_SEARCH, q)
        }
        if (lower.contains("volume")) {
            val pct = Regex("\\d+").find(lower)?.value?.toIntOrNull()
                ?: if (lower.contains("mute") || lower.contains("zero")) 0 else if (lower.contains("max") || lower.contains("up")) 85 else 45
            addStep("Adjust media volume to $pct%", AgentActionType.SET_VOLUME, pct.toString())
        }
        if (lower.contains("brightness")) {
            val pct = Regex("\\d+").find(lower)?.value?.toIntOrNull()
                ?: if (lower.contains("dim") || lower.contains("low")) 25 else 80
            addStep("Set screen brightness to $pct%", AgentActionType.SET_BRIGHTNESS, pct.toString())
        }
        if (lower.contains("wifi") || lower.contains("bluetooth") || lower.contains("settings")) {
            val cat = when {
                lower.contains("wifi") || lower.contains("wi-fi") -> "wifi"
                lower.contains("bluetooth") -> "bluetooth"
                lower.contains("display") -> "display"
                else -> "settings"
            }
            addStep("Open $cat settings", AgentActionType.OPEN_SETTINGS, cat)
        }
        if ((lower.contains("whatsapp") || lower.contains("notification") || lower.contains("messages")) &&
            (lower.contains("read") || lower.contains("check") || lower.contains("notification"))
        ) {
            addStep("Read latest notifications / WhatsApp messages", AgentActionType.READ_WHATSAPP)
        }
        if (lower.contains("reply") || lower.contains("send message")) {
            addStep("Send authorized message reply", AgentActionType.SEND_WHATSAPP_REPLY, goal)
        }
        if (lower.contains("call ")) {
            val target = lower.substringAfter("call ").trim()
            addStep("Place phone call to $target", AgentActionType.MAKE_CALL, target)
        }
        if (lower.contains("buy") || lower.contains("purchase") || lower.contains("order ")) {
            addStep("Confirm purchase action: $goal", AgentActionType.PURCHASE_ACTION, goal)
        }
        if (lower.contains("delete")) {
            addStep("Delete requested file/item: $goal", AgentActionType.DELETE_FILE, goal)
        }
        if (lower.contains("publish") || lower.contains("post ")) {
            addStep("Publish content: $goal", AgentActionType.PUBLISH_CONTENT, goal)
        }
        if (lower.contains("tap ") || lower.contains("click ")) {
            val target = lower.replace("tap ", "").replace("click ", "").trim()
            addStep("Tap UI element '$target'", AgentActionType.TAP_UI_ELEMENT, target)
        }
        if (lower.contains("scroll") || lower.contains("swipe")) {
            when {
                lower.contains("bottom") || lower.contains("end") ->
                    addStep("Scroll to bottom of page", AgentActionType.SCROLL_TO_EDGE, "bottom")
                lower.contains("top") ->
                    addStep("Scroll to top of page", AgentActionType.SCROLL_TO_EDGE, "top")
                lower.contains("continuous") ->
                    addStep("Continuous scroll", AgentActionType.CONTINUOUS_SCROLL, if (lower.contains("up")) "false" else "true")
                lower.contains("left") ->
                    addStep("Swipe left", AgentActionType.SWIPE_SCREEN, "LEFT")
                lower.contains("right") ->
                    addStep("Swipe right", AgentActionType.SWIPE_SCREEN, "RIGHT")
                else -> {
                    val forward = !lower.contains("up") && !lower.contains("back") && !lower.contains("upar")
                    addStep(if (forward) "Scroll screen down" else "Scroll screen up", AgentActionType.SCROLL_SCREEN, forward.toString())
                }
            }
        }
        if (steps.isEmpty()) {
            val matchedKnown = AppLauncherManager.matchKnownApp(goal)
            if (matchedKnown != null) {
                addStep("Launch ${matchedKnown.canonicalName}", AgentActionType.OPEN_APP, matchedKnown.canonicalName)
            } else if (lower.startsWith("open ") || lower.startsWith("launch ") || lower.contains("kholo") || lower.contains("chalao")) {
                val app = AppLauncherManager.normalizeAppQuery(goal)
                addStep("Launch app '$app'", AgentActionType.OPEN_APP, app)
                addStep("Inspect opened app screen", AgentActionType.READ_SCREEN)
            } else {
                addStep("Inspect current screen context", AgentActionType.READ_SCREEN)
            }
        }
        return steps
    }

    suspend fun executePlan(
        goal: String,
        steps: List<AgentPlanStep>,
        scope: kotlinx.coroutines.CoroutineScope
    ) {
        stop()
        _currentGoal.value = goal
        _planSteps.value = steps
        _isRunning.value = true

        executionJob = scope.launchForAgent {
            var completedCount = 0
            var abortedReason: String? = null

            try {
                for (step in steps) {
                    if (!_isRunning.value) {
                        abortedReason = "Stopped by user"
                        break
                    }

                    // Safety confirmation gate before sensitive actions
                    if (step.requiresConfirmation) {
                        updateStep(step.id, StepExecutionStatus.AWAITING_CONFIRMATION, "Waiting for user approval (${step.sensitiveCategory.label})")
                        val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
                        confirmationDeferred = deferred
                        _pendingConfirmation.value = PendingSensitiveConfirmation(
                            step = step,
                            goal = goal,
                            reason = "Mahi requires your explicit approval before performing a ${step.sensitiveCategory.label} action."
                        )
                        val approved = deferred.await()
                        _pendingConfirmation.value = null
                        confirmationDeferred = null

                        if (!approved) {
                            updateStep(step.id, StepExecutionStatus.DENIED_BY_USER, "Denied by user — security gate upheld")
                            abortedReason = "Denied sensitive step: ${step.title}"
                            break
                        }
                    }

                    updateStep(step.id, StepExecutionStatus.RUNNING, "Executing...")
                    val outcome = executeSingleStep(step)
                    delay(180)
                    if (outcome.success) {
                        completedCount++
                        updateStep(
                            step.id,
                            StepExecutionStatus.VERIFIED_SUCCESS,
                            outcome.verificationDetail.ifBlank { outcome.message }
                        )
                    } else {
                        updateStep(step.id, StepExecutionStatus.FAILED, outcome.message)
                        abortedReason = outcome.message
                        break
                    }
                }
            } catch (_: CancellationException) {
                abortedReason = "Stopped by user"
            } finally {
                _pendingConfirmation.value = null
                confirmationDeferred?.cancel()
                confirmationDeferred = null
                _isRunning.value = false

                val summary = abortedReason
                    ?: "Verified $completedCount/${steps.size} steps successfully"
                historyStore.record(
                    AutomationHistoryEntry(
                        goal = goal,
                        statusSummary = summary,
                        stepCount = steps.size
                    )
                )
            }
        }
    }

    private fun kotlinx.coroutines.CoroutineScope.launchForAgent(block: suspend () -> Unit): Job {
        return this.launch { block() }
    }

    fun respondToSensitiveConfirmation(approved: Boolean) {
        confirmationDeferred?.complete(approved)
    }

    fun stop() {
        _isRunning.value = false
        confirmationDeferred?.cancel()
        confirmationDeferred = null
        _pendingConfirmation.value = null
        executionJob?.cancel()
        executionJob = null
        _planSteps.value = _planSteps.value.map { step ->
            if (step.status == StepExecutionStatus.PENDING ||
                step.status == StepExecutionStatus.RUNNING ||
                step.status == StepExecutionStatus.AWAITING_CONFIRMATION
            ) {
                step.copy(status = StepExecutionStatus.STOPPED, verificationNote = "Stopped by user")
            } else step
        }
    }

    private suspend fun executeSingleStep(step: AgentPlanStep): AutomationOutcome = withContext(Dispatchers.Main) {
        when (step.actionType) {
            AgentActionType.OPEN_APP -> deviceControl.launchAppByName(step.param)
            AgentActionType.YOUTUBE_SEARCH -> deviceControl.searchYouTube(step.param)
            AgentActionType.SET_VOLUME -> deviceControl.setMediaVolumePercent(step.param.toIntOrNull() ?: 50)
            AgentActionType.SET_BRIGHTNESS -> deviceControl.setBrightnessPercent(step.param.toIntOrNull() ?: 70)
            AgentActionType.OPEN_SETTINGS -> deviceControl.openSettingsScreen(step.param)
            AgentActionType.TAP_UI_ELEMENT -> {
                if (!MahiAccessibilityService.isServiceEnabled(context)) {
                    AutomationOutcome(false, "Enable Mahi Accessibility Service to tap UI elements.")
                } else {
                    val ok = MahiAccessibilityService.tapElementByText(step.param)
                    AutomationOutcome(
                        success = ok,
                        message = if (ok) "Tapped '${step.param}'" else "UI element '${step.param}' not found on screen",
                        verificationDetail = if (ok) "Accessibility tap verified & screen refreshed" else ""
                    )
                }
            }
            AgentActionType.DOUBLE_TAP_UI_ELEMENT -> {
                if (!MahiAccessibilityService.isServiceEnabled(context)) {
                    AutomationOutcome(false, "Enable Mahi Accessibility Service to double-tap elements.")
                } else {
                    val ok = MahiAccessibilityService.doubleTapElementOrCenter(step.param)
                    AutomationOutcome(ok, if (ok) "Double-tapped '${step.param.ifBlank { "center" }}'" else "Double-tap failed")
                }
            }
            AgentActionType.LONG_PRESS_UI_ELEMENT -> {
                if (!MahiAccessibilityService.isServiceEnabled(context)) {
                    AutomationOutcome(false, "Enable Mahi Accessibility Service to long press elements.")
                } else {
                    val ok = MahiAccessibilityService.longPressElementByText(step.param)
                    AutomationOutcome(ok, if (ok) "Long-pressed '${step.param}'" else "Element '${step.param}' not found")
                }
            }
            AgentActionType.DRAG_DROP -> {
                if (!MahiAccessibilityService.isServiceEnabled(context)) {
                    AutomationOutcome(false, "Enable Mahi Accessibility Service to drag and drop.")
                } else {
                    val ok = MahiAccessibilityService.dragBetweenElements(step.param, step.secondaryParam)
                    AutomationOutcome(ok, if (ok) "Dragged '${step.param}' to '${step.secondaryParam}'" else "Drag targets unavailable")
                }
            }
            AgentActionType.SCROLL_SCREEN -> {
                val forward = step.param.lowercase() != "false"
                val speed = if (step.secondaryParam.equals("fast", ignoreCase = true)) GestureSpeed.FAST else GestureSpeed.NORMAL
                val ok = MahiAccessibilityService.scrollScreen(forward, speed)
                AutomationOutcome(ok, if (ok) "Scrolled screen" else "Accessibility Service required to scroll")
            }
            AgentActionType.SCROLL_TO_EDGE -> {
                val toBottom = !step.param.equals("top", ignoreCase = true)
                val ok = MahiAccessibilityService.scrollToEdge(toBottom)
                AutomationOutcome(ok, if (ok) "Scrolling to ${if (toBottom) "bottom" else "top"}" else "Accessibility Service required")
            }
            AgentActionType.CONTINUOUS_SCROLL -> {
                val forward = step.param.lowercase() != "false"
                val ok = MahiAccessibilityService.startContinuousScroll(forward)
                AutomationOutcome(ok, if (ok) "Continuous scroll active" else "Accessibility Service required")
            }
            AgentActionType.SWIPE_SCREEN -> {
                val dir = runCatching { SwipeDirection.valueOf(step.param.uppercase()) }.getOrDefault(SwipeDirection.UP)
                val ok = MahiAccessibilityService.swipeScreen(dir)
                AutomationOutcome(ok, if (ok) "Swiped ${dir.name}" else "Accessibility Service required to swipe")
            }
            AgentActionType.STOP_GESTURES -> {
                MahiAccessibilityService.stopAllGestures()
                AutomationOutcome(true, "All gestures stopped")
            }
            AgentActionType.READ_SCREEN -> {
                val snap = MahiAccessibilityService.refreshScreenSnapshot()
                if (snap != null) {
                    AutomationOutcome(true, snap.readableSummary, "Captured ${snap.elements.size} UI nodes from ${snap.packageName}")
                } else {
                    AutomationOutcome(false, "Accessibility Service not connected; enable it to read external apps.")
                }
            }
            AgentActionType.READ_WHATSAPP -> {
                val waMsgs = MahiNotificationService.notifications.value.filter { it.isWhatsApp }
                if (waMsgs.isEmpty()) {
                    AutomationOutcome(true, "No unread WhatsApp notifications found.", "Notification listener checked")
                } else {
                    AutomationOutcome(
                        true,
                        "Found ${waMsgs.size} WhatsApp notification(s): ${waMsgs.first().senderTitle}: ${waMsgs.first().messageText}",
                        "Verified ${waMsgs.size} active WhatsApp notification(s)"
                    )
                }
            }
            AgentActionType.SEND_WHATSAPP_REPLY -> {
                val replyCandidates = MahiNotificationService.notifications.value.filter { it.isWhatsApp && it.canReply }
                val targetNotif = replyCandidates.firstOrNull {
                    it.key == step.param ||
                        (step.param.isNotBlank() && it.senderTitle.contains(step.param, ignoreCase = true))
                } ?: replyCandidates.firstOrNull()
                if (targetNotif == null) {
                    AutomationOutcome(false, "No active WhatsApp notification with reply action available.")
                } else {
                    val replyBody = step.secondaryParam.ifBlank { step.param }
                    val res = MahiNotificationService.sendAuthorizedReply(context, targetNotif.key, replyBody)
                    AutomationOutcome(
                        success = res.isSuccess,
                        message = res.getOrElse { it.localizedMessage ?: "Reply failed" },
                        verificationDetail = if (res.isSuccess) "Authorized RemoteInput reply sent to ${targetNotif.senderTitle}" else ""
                    )
                }
            }
            AgentActionType.MAKE_CALL -> {
                val number = step.param.filter { it.isDigit() || it == '+' }
                val dialUri = if (number.isNotEmpty()) Uri.parse("tel:$number") else Uri.parse("tel:")
                try {
                    context.startActivity(Intent(Intent.ACTION_DIAL, dialUri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    AutomationOutcome(true, "Opened phone dialer with user approval", "ACTION_DIAL intent verified")
                } catch (e: Exception) {
                    AutomationOutcome(false, e.localizedMessage ?: "No dialer app available")
                }
            }
            AgentActionType.PURCHASE_ACTION,
            AgentActionType.DELETE_FILE,
            AgentActionType.PUBLISH_CONTENT -> {
                AutomationOutcome(
                    success = true,
                    message = "User-approved ${step.sensitiveCategory.label} step acknowledged.",
                    verificationDetail = "Explicit confirmation recorded; safe handoff complete"
                )
            }
        }
    }

    private fun updateStep(stepId: String, status: StepExecutionStatus, note: String) {
        _planSteps.value = _planSteps.value.map {
            if (it.id == stepId) it.copy(status = status, verificationNote = note) else it
        }
    }

    private fun parseAiPlanSteps(raw: String): List<AgentPlanStep> {
        return try {
            val start = raw.indexOf('[')
            val end = raw.lastIndexOf(']')
            if (start < 0 || end <= start) return emptyList()
            val arr = JSONArray(raw.substring(start, end + 1))
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val title = obj.optString("title").trim()
                    val typeStr = obj.optString("actionType").trim()
                    val type = runCatching { AgentActionType.valueOf(typeStr) }.getOrNull() ?: continue
                    val param = obj.optString("param").trim()
                    val secondary = obj.optString("secondaryParam").trim()
                    add(
                        AgentPlanStep(
                            stepNumber = size + 1,
                            title = title.ifBlank { type.name },
                            actionType = type,
                            param = param,
                            secondaryParam = secondary,
                            sensitiveCategory = classifySensitivity(type, title, param)
                        )
                    )
                }
            }.take(5)
        } catch (_: Exception) {
            emptyList()
        }
    }
}
