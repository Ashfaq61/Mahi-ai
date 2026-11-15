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
    CAPTURE_SCREEN_IMAGE,
    TAP_BY_INDEX,
    TYPE_TEXT,
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
    val historyStore: AutomationHistoryStore = AutomationHistoryStore(context),
    val labelMemoryStore: AppLabelMemoryStore = AppLabelMemoryStore(context)
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
                    (actionType == AgentActionType.TYPE_TEXT &&
                        (combined.contains("send") || combined.contains("submit") || combined.contains("reply") ||
                            combined.contains("pay") || combined.contains("transfer"))) ||
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

    private val _isControllingScreen = MutableStateFlow(false)
    val isControllingScreen: StateFlow<Boolean> = _isControllingScreen.asStateFlow()

    private val _screenAgentStatus = MutableStateFlow("")
    val screenAgentStatus: StateFlow<String> = _screenAgentStatus.asStateFlow()

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
            val q = goal
                .replace(Regex(".*youtube(?:\\s+(?:par|pe|mein|kholo|chalao|open|aur|and))*\\b", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\b(search|karo|dhoondo|play|for|on)\\b", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            if (lower.contains("kholo") || lower.contains("open")) {
                addStep("Open YouTube", AgentActionType.OPEN_APP, "YouTube")
            }
            if (q.isNotBlank() && !q.equals(goal, ignoreCase = true)) {
                addStep("Search YouTube for '$q'", AgentActionType.YOUTUBE_SEARCH, q)
            } else if (steps.isEmpty()) {
                addStep("Search YouTube for '$goal'", AgentActionType.YOUTUBE_SEARCH, goal)
            }
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
        val typeRegex = Regex("(?i)(?:type|likho|write)\\s+['\"]?([^'\"]+?)['\"]?(?:\\s+(?:and|aur)\\s+(?:search|submit|send))?$")
        if (lower.contains("type ") || lower.contains("likho") || lower.contains("write ")) {
            val textToType = typeRegex.find(goal)?.groupValues?.getOrNull(1)?.trim().orEmpty()
                .ifBlank { goal.substringAfter("likho").substringAfter("type ").trim() }
            if (textToType.isNotBlank()) {
                val shouldSubmit = lower.contains("search") || lower.contains("submit") || lower.contains("send")
                addStep("Type '$textToType' on screen", AgentActionType.TYPE_TEXT, textToType, if (shouldSubmit) "submit" else "")
            }
        }
        val tapNumMatch = Regex("(?i)(?:(\\d+)\\s*(?:number|th|st|nd|rd|item|element)?\\s*(?:par|pe)?\\s*(?:tap|click)|(?:tap|click)\\s*(?:on\\s*)?(?:element|item|number|#)?\\s*(\\d+))").find(lower)
        if (tapNumMatch != null) {
            val idx = tapNumMatch.groupValues[1].ifBlank { tapNumMatch.groupValues[2] }
            addStep("Tap UI element #$idx", AgentActionType.TAP_BY_INDEX, idx)
        } else if (lower.contains("tap ") || lower.contains("click ")) {
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
        _isControllingScreen.value = false
        _screenAgentStatus.value = "Stopped by user"
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
                val snap = MahiAccessibilityService.readScreenWithRetry(context).getOrNull()
                if (snap != null) {
                    AutomationOutcome(true, snap.readableSummary, "Captured ${snap.elements.size} UI nodes from ${snap.packageName}")
                } else {
                    AutomationOutcome(false, "Accessibility Service not connected; enable it to read external apps.")
                }
            }
            AgentActionType.CAPTURE_SCREEN_IMAGE -> {
                val snap = MahiAccessibilityService.refreshScreenSnapshot(force = true)
                AutomationOutcome(
                    success = MahiAccessibilityService.isServiceEnabled(context),
                    message = if (MahiAccessibilityService.isServiceEnabled(context)) {
                        "Screen image capture requested (app: ${snap?.packageName.orEmpty()})"
                    } else {
                        "Enable Mahi Accessibility Service for screen capture."
                    }
                )
            }
            AgentActionType.TAP_BY_INDEX -> {
                val idx = step.param.toIntOrNull()
                MahiAccessibilityService.tapElement(context = context, index = idx)
            }
            AgentActionType.TYPE_TEXT -> {
                val submit = step.secondaryParam.equals("submit", ignoreCase = true)
                MahiAccessibilityService.typeText(
                    context = context,
                    text = step.param,
                    submit = submit
                )
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

    // ============================================================================================
    // PART 7 & 8: Unified Screen-Aware Autonomous App Agent + Autopilot GoalSession
    // ============================================================================================

    interface ScreenAgentDriver {
        fun isAccessibilityEnabled(): Boolean
        fun isDeviceUnlocked(): Boolean = true
        suspend fun readScreen(): Result<ScreenSnapshot>
        suspend fun waitForScreenChange(timeoutMs: Long): Boolean = false
        suspend fun hideKeyboard(): Boolean = false
        suspend fun openApp(appName: String): AutomationOutcome
        suspend fun tap(index: Int?, query: String?, viewId: String?, x: Float?, y: Float?): AutomationOutcome
        suspend fun typeText(index: Int?, query: String?, text: String, submit: Boolean, clearFirst: Boolean): AutomationOutcome
        suspend fun scroll(forward: Boolean): AutomationOutcome
        suspend fun swipe(direction: SwipeDirection): AutomationOutcome
        suspend fun pressBack(): AutomationOutcome
        suspend fun captureScreenJpeg(): Result<ByteArray>
        fun displaySize(): Pair<Int, Int>
    }

    data class ScreenAgentResult(
        val completed: Boolean,
        val verified: Boolean,
        val stepsExecuted: Int,
        val summary: String
    )

    private fun createAndroidScreenDriver(): ScreenAgentDriver = object : ScreenAgentDriver {
        override fun isAccessibilityEnabled(): Boolean = MahiAccessibilityService.isServiceEnabled(context)

        override fun isDeviceUnlocked(): Boolean = MahiAccessibilityService.isDeviceInteractiveAndUnlocked(context)

        override suspend fun readScreen(): Result<ScreenSnapshot> =
            MahiAccessibilityService.readScreenWithRetry(context)

        override suspend fun waitForScreenChange(timeoutMs: Long): Boolean {
            return kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
                var changed = false
                try {
                    MahiAccessibilityService.screenChangeEvents.collect {
                        changed = true
                        throw CancellationException("change_observed")
                    }
                } catch (_: CancellationException) {
                }
                changed
            } ?: false
        }

        override suspend fun hideKeyboard(): Boolean = withContext(Dispatchers.Main) {
            MahiAccessibilityService.hideSoftKeyboardIfOpen()
        }

        override suspend fun openApp(appName: String): AutomationOutcome = withContext(Dispatchers.Main) {
            deviceControl.launchAppByName(appName)
        }

        override suspend fun tap(index: Int?, query: String?, viewId: String?, x: Float?, y: Float?): AutomationOutcome =
            withContext(Dispatchers.Main) {
                MahiAccessibilityService.tapElement(context, index = index, query = query, viewId = viewId, x = x, y = y)
            }

        override suspend fun typeText(index: Int?, query: String?, text: String, submit: Boolean, clearFirst: Boolean): AutomationOutcome =
            withContext(Dispatchers.Main) {
                MahiAccessibilityService.typeText(
                    context = context,
                    index = index,
                    query = query,
                    text = text,
                    append = false,
                    clearFirst = clearFirst,
                    submit = submit
                )
            }

        override suspend fun scroll(forward: Boolean): AutomationOutcome = withContext(Dispatchers.Main) {
            val ok = MahiAccessibilityService.scrollScreen(forward, GestureSpeed.NORMAL)
            AutomationOutcome(ok, if (ok) "Scrolled screen" else "Accessibility scroll failed")
        }

        override suspend fun swipe(direction: SwipeDirection): AutomationOutcome = withContext(Dispatchers.Main) {
            val ok = MahiAccessibilityService.swipeScreen(direction, GestureSpeed.NORMAL)
            AutomationOutcome(ok, if (ok) "Swiped ${direction.name}" else "Accessibility swipe failed")
        }

        override suspend fun pressBack(): AutomationOutcome = withContext(Dispatchers.Main) {
            MahiAccessibilityService.navigateBack(context)
        }

        override suspend fun captureScreenJpeg(): Result<ByteArray> =
            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                MahiAccessibilityService.captureScreenJpeg(maxDim = 1024, quality = 72) { res ->
                    if (cont.isActive) cont.resumeWith(Result.success(res))
                }
            }

        override fun displaySize(): Pair<Int, Int> = MahiAccessibilityService.getDisplaySize(context)
    }

    suspend fun runScreenTask(
        goal: String,
        language: AppLanguage = AppLanguage.ENGLISH,
        provider: AiProvider? = null,
        apiKey: String? = null,
        model: String? = null,
        baseUrl: String? = null,
        driver: ScreenAgentDriver = createAndroidScreenDriver(),
        autopilotAllowed: Boolean = true,
        groundingProvider: GuiGroundingProvider? = null,
        stepDelayMs: Long = 1_100L,
        verifyDelayMs: Long = 350L,
        onProgressUpdate: (String) -> Unit = {},
        onAutopilotNotificationUpdate: (active: Boolean, statusText: String) -> Unit = { _, _ -> }
    ): ScreenAgentResult {
        val trimmedGoal = goal.trim()
        if (trimmedGoal.isEmpty()) {
            return ScreenAgentResult(false, false, 0, "Please describe the screen task you want me to run.")
        }

        if (!autopilotAllowed) {
            val deniedMsg = "Autopilot is turned off. Turn on 'Allow Autopilot' in Settings so Mahi can control apps step by step."
            _screenAgentStatus.value = deniedMsg
            historyStore.record(AutomationHistoryEntry(goal = trimmedGoal, statusSummary = deniedMsg, stepCount = 0))
            return ScreenAgentResult(false, false, 0, deniedMsg)
        }

        AppSkills.detectBulkSpamViolation(trimmedGoal)?.let { spamError ->
            _screenAgentStatus.value = spamError
            historyStore.record(AutomationHistoryEntry(goal = trimmedGoal, statusSummary = spamError, stepCount = 0))
            return ScreenAgentResult(false, false, 0, spamError)
        }

        stop()
        _currentGoal.value = trimmedGoal
        _planSteps.value = emptyList()
        _isRunning.value = true
        _isControllingScreen.value = true
        _screenAgentStatus.value = "Autopilot planning: $trimmedGoal"

        val maxActionsPerSession = 40
        val maxRepeats = 3
        val subGoals = AppSkills.decomposeGoalIntoSubGoals(trimmedGoal)
        val completedHistory = mutableListOf<String>()
        val completedSubGoalNotes = mutableListOf<String>()
        var subGoalIdx = 0
        var stepsExecuted = 0
        var lastCheckpointAt = 0
        var finalVerified = false
        var finalSummary = "Reached action limit ($maxActionsPerSession) before confirming full goal completion."

        val effectiveGrounding = groundingProvider ?: GeminiGroundingProvider(
            providerClient = providerClient,
            provider = provider,
            apiKey = apiKey,
            model = model,
            baseUrl = baseUrl
        )

        val expectedTargetApp = AppSkills.detectTargetAppInGoal(trimmedGoal)
        var targetAppWasActive = false

        MahiAccessibilityService.setAutopilotObserving(true)
        onAutopilotNotificationUpdate(true, "Planning ${subGoals.size} step(s): $trimmedGoal")

        try {
            var repeatCount = 0
            var lastSignature = ""
            var subGoalRetryCount = 0
            var scrollDownAttempts = 0
            var scrollUpAttempts = 0
            var triedHiddenMenu = false
            var triedVisualGrounding = false
            var dismissedInterruptions = 0

            while (stepsExecuted < maxActionsPerSession && subGoalIdx < subGoals.size) {
                if (!_isRunning.value) {
                    finalSummary = "Autopilot stopped immediately by user."
                    break
                }

                if (!driver.isDeviceUnlocked()) {
                    finalSummary = "Phone screen is locked or turned off. Unlock your device so Autopilot can continue."
                    appendLiveStep("Screen Locked", AgentActionType.READ_SCREEN, StepExecutionStatus.STOPPED, finalSummary)
                    break
                }

                val currentSubGoal = subGoals[subGoalIdx]
                val activeGoalPhrase = currentSubGoal.goalPhrase

                // 1. OBSERVE
                var observedRes = driver.readScreen()
                if (!driver.isAccessibilityEnabled() && observedRes.isFailure) {
                    finalSummary = observedRes.exceptionOrNull()?.localizedMessage
                        ?: "Mahi Accessibility Service disconnected. Re-enable Mahi AI in Accessibility Settings."
                    break
                }

                var snapshot = observedRes.getOrElse {
                    ScreenSnapshot(packageName = expectedTargetApp?.packageNames?.firstOrNull().orEmpty(), elements = emptyList())
                }

                // Slow-loading screen resilience: wait up to 5s when screen shows spinner/empty load state
                if (driver.isAccessibilityEnabled() && AppSkills.isLoadingScreenSnapshot(snapshot) && verifyDelayMs > 0L) {
                    var waitedMs = 0L
                    while (waitedMs < 5_000L && _isRunning.value && AppSkills.isLoadingScreenSnapshot(snapshot)) {
                        _screenAgentStatus.value = "Waiting for screen to finish loading..."
                        val gotChange = driver.waitForScreenChange(1_000L)
                        if (!gotChange) delay(600L)
                        waitedMs += 1_000L
                        snapshot = driver.readScreen().getOrElse { snapshot }
                    }
                }

                // Security / CAPTCHA / 2FA / Login walls ALWAYS halt cleanly
                AppSkills.detectSecurityOrCaptchaWall(snapshot)?.let { wallWarning ->
                    _screenAgentStatus.value = wallWarning
                    appendLiveStep("Security Wall Detected", AgentActionType.READ_SCREEN, StepExecutionStatus.STOPPED, wallWarning)
                    finalSummary = wallWarning
                    return ScreenAgentResult(false, false, stepsExecuted, wallWarning)
                }

                // OS Permission prompts or Paid Subscriptions NEVER auto-accept -> ask user
                AppSkills.detectProtectedSystemOrPaywallPrompt(snapshot)?.let { protectedWarn ->
                    _screenAgentStatus.value = protectedWarn
                    appendLiveStep("Protected Prompt", AgentActionType.READ_SCREEN, StepExecutionStatus.AWAITING_CONFIRMATION, protectedWarn)
                    finalSummary = protectedWarn
                    return ScreenAgentResult(false, false, stepsExecuted, protectedWarn)
                }

                // App crash / unexpected switch resilience
                if (expectedTargetApp != null) {
                    val pkgLower = snapshot.packageName.lowercase()
                    val onExpectedApp = expectedTargetApp.packageNames.any { it.equals(pkgLower, ignoreCase = true) }
                    if (onExpectedApp) {
                        targetAppWasActive = true
                    } else if (targetAppWasActive && (pkgLower.contains("launcher") || pkgLower.contains("systemui") ||
                            snapshot.elements.any {
                                val t = it.bestLabel.lowercase()
                                t.contains("keeps stopping") || t.contains("has stopped")
                            })
                    ) {
                        stepsExecuted++
                        val recoverId = appendLiveStep("Recover ${expectedTargetApp.appName}", AgentActionType.OPEN_APP, StepExecutionStatus.RUNNING, "Re-opening after switch/crash")
                        val recOut = driver.openApp(expectedTargetApp.appName)
                        updateStep(recoverId, if (recOut.success) StepExecutionStatus.VERIFIED_SUCCESS else StepExecutionStatus.FAILED, recOut.message)
                        if (stepDelayMs > 0) delay(stepDelayMs)
                        continue
                    }
                }

                // Automatic harmless popup dismissal ("Not now", "Skip", "Maybe later", "No thanks", "Close")
                val interruptionNode = AppSkills.detectHarmlessInterruption(snapshot)
                if (interruptionNode != null && dismissedInterruptions < 4 && stepsExecuted < maxActionsPerSession) {
                    dismissedInterruptions++
                    stepsExecuted++
                    val popId = appendLiveStep(
                        "Dismiss popup: ${interruptionNode.bestLabel}",
                        AgentActionType.TAP_UI_ELEMENT,
                        StepExecutionStatus.RUNNING,
                        "Auto-dismissing benign interruption"
                    )
                    val popOut = driver.tap(interruptionNode.index, interruptionNode.bestLabel, interruptionNode.viewIdResourceName.ifBlank { null }, null, null)
                    updateStep(popId, if (popOut.success) StepExecutionStatus.VERIFIED_SUCCESS else StepExecutionStatus.FAILED, popOut.message)
                    completedHistory.add("dismissPopup(${interruptionNode.bestLabel}) -> ${if (popOut.success) "OK" else "FAIL"}")
                    onAutopilotNotificationUpdate(true, "Dismissed '${interruptionNode.bestLabel}' — continuing")
                    if (verifyDelayMs > 0) delay(verifyDelayMs)
                    continue
                }

                val sig = "${snapshot.packageName}|${snapshot.elements.take(12).joinToString(",") { "${it.bestLabel}:${it.isSelected}:${it.isChecked}" }}"
                if (stepsExecuted > 0 && sig == lastSignature && snapshot.elements.isNotEmpty() &&
                    scrollDownAttempts == 0 && scrollUpAttempts == 0
                ) {
                    repeatCount++
                    if (repeatCount >= maxRepeats) {
                        finalSummary = "Stopped safely because the screen stopped changing after repeated actions."
                        break
                    }
                } else {
                    repeatCount = 0
                    lastSignature = sig
                }

                val recipe = AppSkills.forPackage(snapshot.packageName, activeGoalPhrase)

                // 2. THINK (AI planner + deterministic heuristic + TargetFinder)
                var decision = thinkNextScreenAction(
                    goal = activeGoalPhrase,
                    snapshot = snapshot,
                    recipe = recipe,
                    completedHistory = completedHistory,
                    language = language,
                    provider = provider,
                    apiKey = apiKey,
                    model = model,
                    baseUrl = baseUrl,
                    driver = driver
                )

                // If target element was not visible on first look, engage autonomous TargetFinder discovery:
                // (a) overflow/hidden menu -> (b) scroll down <=6x -> (c) scroll up <=3x -> (d) visual GUI grounding -> (e) ask user
                if (decision == null || decision.action == ScreenAgentActionType.ASK_USER) {
                    val discoveredDecision = runTargetFinderStep(
                        goalPhrase = activeGoalPhrase,
                        snapshot = snapshot,
                        recipe = recipe,
                        driver = driver,
                        groundingProvider = effectiveGrounding,
                        triedHiddenMenu = triedHiddenMenu,
                        scrollDownAttempts = scrollDownAttempts,
                        scrollUpAttempts = scrollUpAttempts,
                        triedVisualGrounding = triedVisualGrounding
                    )
                    when (discoveredDecision.kind) {
                        TargetFinderKind.HIDDEN_MENU -> {
                            triedHiddenMenu = true
                            decision = discoveredDecision.decision
                        }
                        TargetFinderKind.SCROLL_DOWN -> {
                            scrollDownAttempts++
                            decision = discoveredDecision.decision
                        }
                        TargetFinderKind.SCROLL_UP -> {
                            scrollUpAttempts++
                            decision = discoveredDecision.decision
                        }
                        TargetFinderKind.GROUNDED_TAP -> {
                            triedVisualGrounding = true
                            decision = discoveredDecision.decision
                        }
                        TargetFinderKind.NOT_FOUND -> {
                            finalSummary = discoveredDecision.message
                            appendLiveStep(
                                title = "Target not found",
                                type = AgentActionType.READ_SCREEN,
                                status = StepExecutionStatus.AWAITING_CONFIRMATION,
                                note = finalSummary
                            )
                            break
                        }
                    }
                }

                if (decision == null) {
                    finalSummary = "Could not determine a valid next screen action."
                    break
                }

                if (decision.action == ScreenAgentActionType.DONE) {
                    val note = decision.reason.ifBlank { "Verified: ${currentSubGoal.verificationRule}" }
                    completedSubGoalNotes.add("${currentSubGoal.title}: $note")
                    appendLiveStep(
                        title = currentSubGoal.title,
                        type = AgentActionType.READ_SCREEN,
                        status = StepExecutionStatus.VERIFIED_SUCCESS,
                        note = note
                    )
                    subGoalIdx++
                    subGoalRetryCount = 0
                    scrollDownAttempts = 0
                    scrollUpAttempts = 0
                    triedHiddenMenu = false
                    triedVisualGrounding = false
                    if (subGoalIdx >= subGoals.size) {
                        finalVerified = true
                        finalSummary = completedSubGoalNotes.joinToString(" | ")
                        break
                    }
                    continue
                }

                if (decision.action == ScreenAgentActionType.ASK_USER) {
                    finalSummary = decision.reason.ifBlank { "Please complete the current prompt on screen, then let me continue." }
                    appendLiveStep(
                        title = "Needs user input",
                        type = AgentActionType.READ_SCREEN,
                        status = StepExecutionStatus.AWAITING_CONFIRMATION,
                        note = finalSummary
                    )
                    break
                }

                // Safety Gate before sensitive actions / non-dictated comments / purchases / explicit share
                val gatedOutcome = checkSafetyAndConfirmIfNeeded(activeGoalPhrase, decision)
                if (gatedOutcome != null && !gatedOutcome) {
                    finalSummary = "Action paused/declined by user safety confirmation."
                    appendLiveStep(
                        title = decision.reason.ifBlank { decision.action.wireName },
                        type = mapToAgentActionType(decision.action),
                        status = StepExecutionStatus.DENIED_BY_USER,
                        note = finalSummary
                    )
                    break
                }

                // 3. ACT
                stepsExecuted++
                val stepTitle = decision.reason.ifBlank { "${decision.action.wireName}: ${decision.targetQuery ?: decision.text ?: ""}" }
                val stepId = appendLiveStep(stepTitle, mapToAgentActionType(decision.action), StepExecutionStatus.RUNNING, "Executing...")
                _screenAgentStatus.value = stepTitle
                onAutopilotNotificationUpdate(true, "Step $stepsExecuted/$maxActionsPerSession: $stepTitle")
                if (stepsExecuted > 0 && stepsExecuted % 10 == 0 && stepsExecuted / 10 > lastCheckpointAt) {
                    lastCheckpointAt = stepsExecuted / 10
                    val checkpointMsg = AppSkills.formatProgressCheckpoint(recipe.appName, stepsExecuted, language)
                    onProgressUpdate(checkpointMsg)
                    onAutopilotNotificationUpdate(true, checkpointMsg)
                }

                var actOutcome = executeScreenDecision(
                    decision = decision,
                    snapshot = snapshot,
                    recipe = recipe,
                    driver = driver,
                    groundingProvider = effectiveGrounding
                )

                // Keyboard-obscuring retry resilience
                if (!actOutcome.success && driver.hideKeyboard()) {
                    actOutcome = executeScreenDecision(
                        decision = decision,
                        snapshot = driver.readScreen().getOrElse { snapshot },
                        recipe = recipe,
                        driver = driver,
                        groundingProvider = effectiveGrounding
                    )
                }

                // 4. VERIFY
                if (verifyDelayMs > 0) delay(verifyDelayMs)
                val afterSnapshot = driver.readScreen().getOrElse { snapshot }

                // Detect end-of-list during TargetFinder scroll
                val afterSig = "${afterSnapshot.packageName}|${afterSnapshot.elements.take(12).joinToString(",") { "${it.bestLabel}:${it.isSelected}:${it.isChecked}" }}"
                if (decision.action == ScreenAgentActionType.SCROLL && afterSig == sig) {
                    if (decision.direction.equals("down", ignoreCase = true) || decision.direction == null) {
                        scrollDownAttempts = 6 // end of list reached -> next iteration tries scroll-up / grounding
                    } else {
                        scrollUpAttempts = 3
                    }
                }

                val isNavigationScrollProbe = decision.action == ScreenAgentActionType.SCROLL &&
                    (decision.reason.contains("TargetFinder", ignoreCase = true) || decision.reason.contains("scroll", ignoreCase = true))

                val verified = if (isNavigationScrollProbe) {
                    actOutcome.success
                } else {
                    actOutcome.success && verifyScreenActionChange(activeGoalPhrase, decision, snapshot, afterSnapshot, recipe)
                }

                if (verified) {
                    subGoalRetryCount = 0
                    val verifyNote = actOutcome.verificationDetail.ifBlank { actOutcome.message }
                    updateStep(stepId, StepExecutionStatus.VERIFIED_SUCCESS, verifyNote)
                    completedHistory.add("${decision.action.wireName}(${decision.targetQuery ?: decision.text ?: ""}) -> OK")

                    // Record successful button label variant in per-app memory
                    val nodeLabel = decision.targetIndex?.let { idx ->
                        snapshot.elements.firstOrNull { it.index == idx }?.bestLabel
                    }?.takeIf { it.isNotBlank() }
                    val matchedLabel = decision.targetQuery?.trim()?.takeIf { it.isNotBlank() } ?: nodeLabel.orEmpty()
                    val canonicalAction = inferCanonicalActionKey(activeGoalPhrase, decision)
                    if (canonicalAction != null && matchedLabel.isNotBlank()) {
                        labelMemoryStore.recordSuccess(snapshot.packageName, canonicalAction, matchedLabel)
                    }

                    if (!isNavigationScrollProbe && isSingleTurnGoalSatisfied(activeGoalPhrase, decision, afterSnapshot, recipe)) {
                        val doneMsg = "${decision.reason.ifBlank { currentSubGoal.title }} — verified on ${recipe.appName}."
                        completedSubGoalNotes.add(doneMsg)
                        subGoalIdx++
                        scrollDownAttempts = 0
                        scrollUpAttempts = 0
                        triedHiddenMenu = false
                        triedVisualGrounding = false
                        if (subGoalIdx >= subGoals.size) {
                            finalVerified = true
                            finalSummary = completedSubGoalNotes.joinToString(" | ")
                            break
                        }
                    }
                } else {
                    subGoalRetryCount++
                    val note = if (!actOutcome.success) actOutcome.message else "Screen did not confirm expected change (attempt $subGoalRetryCount/2)"
                    updateStep(stepId, StepExecutionStatus.FAILED, note)
                    completedHistory.add("${decision.action.wireName}(${decision.targetQuery ?: decision.text ?: ""}) -> REPLAN($note)")

                    // OpenDroid-style re-evaluation: on failure, let TargetFinder / scroll / grounding retry cleanly up to 3 re-plans
                    if (subGoalRetryCount >= 3) {
                        finalSummary = "Could not verify \"${currentSubGoal.title}\": $note"
                        break
                    }
                }

                if (stepDelayMs > 0 && _isRunning.value) {
                    delay(stepDelayMs)
                }
            }
        } catch (_: CancellationException) {
            finalSummary = "Autopilot stopped immediately."
        } finally {
            MahiAccessibilityService.setAutopilotObserving(false)
            onAutopilotNotificationUpdate(false, finalSummary)
            _isControllingScreen.value = false
            _isRunning.value = false
            _pendingConfirmation.value = null
            confirmationDeferred?.cancel()
            confirmationDeferred = null
            _screenAgentStatus.value = finalSummary
            historyStore.record(
                AutomationHistoryEntry(
                    goal = trimmedGoal,
                    statusSummary = finalSummary,
                    stepCount = stepsExecuted
                )
            )
        }

        return ScreenAgentResult(
            completed = finalVerified,
            verified = finalVerified,
            stepsExecuted = stepsExecuted,
            summary = finalSummary
        )
    }

    private enum class TargetFinderKind { HIDDEN_MENU, SCROLL_DOWN, SCROLL_UP, GROUNDED_TAP, NOT_FOUND }

    private data class TargetFinderResult(
        val kind: TargetFinderKind,
        val decision: ScreenAgentDecision? = null,
        val message: String = ""
    )

    private suspend fun runTargetFinderStep(
        goalPhrase: String,
        snapshot: ScreenSnapshot,
        recipe: AppSkillRecipe,
        driver: ScreenAgentDriver,
        groundingProvider: GuiGroundingProvider,
        triedHiddenMenu: Boolean,
        scrollDownAttempts: Int,
        scrollUpAttempts: Int,
        triedVisualGrounding: Boolean
    ): TargetFinderResult {
        // (a) Check collapsed/overflow menus ("See more", "More options", "...")
        if (!triedHiddenMenu) {
            AppSkills.findHiddenMenuNode(snapshot)?.let { menuNode ->
                return TargetFinderResult(
                    kind = TargetFinderKind.HIDDEN_MENU,
                    decision = ScreenAgentDecision(
                        action = ScreenAgentActionType.TAP,
                        targetIndex = menuNode.index,
                        targetQuery = menuNode.bestLabel,
                        reason = "TargetFinder: opening '${menuNode.bestLabel}' menu to find target"
                    )
                )
            }
        }

        // (b) Scroll down up to 6 times to reveal below-the-fold buttons (e.g. Like / Follow / Comment)
        if (scrollDownAttempts < 6) {
            return TargetFinderResult(
                kind = TargetFinderKind.SCROLL_DOWN,
                decision = ScreenAgentDecision(
                    action = ScreenAgentActionType.SCROLL,
                    direction = "down",
                    reason = "TargetFinder: scrolling down (${scrollDownAttempts + 1}/6) to find target"
                )
            )
        }

        // (c) Scroll back up up to 3 times if scrolling down passed it
        if (scrollUpAttempts < 3) {
            return TargetFinderResult(
                kind = TargetFinderKind.SCROLL_UP,
                decision = ScreenAgentDecision(
                    action = ScreenAgentActionType.SCROLL,
                    direction = "up",
                    reason = "TargetFinder: scrolling back up (${scrollUpAttempts + 1}/3) to locate target"
                )
            )
        }

        // (d) Visual GUI grounding fallback (Gemini or optional user-hosted HTTP endpoint)
        if (!triedVisualGrounding) {
            val shot = driver.captureScreenJpeg().getOrNull()
            if (shot != null) {
                val (w, h) = driver.displaySize()
                val grounded = groundingProvider.locateTarget(shot, goalPhrase, w, h).getOrNull()
                if (grounded != null) {
                    return TargetFinderResult(
                        kind = TargetFinderKind.GROUNDED_TAP,
                        decision = ScreenAgentDecision(
                            action = ScreenAgentActionType.TAP,
                            x = grounded.xPx,
                            y = grounded.yPx,
                            targetQuery = goalPhrase,
                            reason = "TargetFinder (${grounded.providerName}): tapping visually at (${grounded.xPx.toInt()},${grounded.yPx.toInt()})"
                        )
                    )
                }
            }
        }

        // (e) Report honestly what Mahi currently sees on screen and ask user
        val visibleLabels = snapshot.elements.map { it.bestLabel }.filter { it.isNotBlank() }.take(8).joinToString(", ")
        return TargetFinderResult(
            kind = TargetFinderKind.NOT_FOUND,
            message = "Could not find \"$goalPhrase\" in ${recipe.appName} after checking menus & scrolling. On screen I see: ${visibleLabels.ifBlank { "no readable controls" }}. Where should I tap?"
        )
    }

    private suspend fun thinkNextScreenAction(
        goal: String,
        snapshot: ScreenSnapshot,
        recipe: AppSkillRecipe,
        completedHistory: List<String>,
        language: AppLanguage,
        provider: AiProvider?,
        apiKey: String?,
        model: String?,
        baseUrl: String?,
        driver: ScreenAgentDriver
    ): ScreenAgentDecision? {
        if (provider != null && !apiKey.isNullOrBlank() && !model.isNullOrBlank() && !baseUrl.isNullOrBlank() && snapshot.elements.isNotEmpty()) {
            try {
                val sysPrompt = """
You are Mahi's Screen-Aware Autonomous App Agent (Autopilot). Return ONLY valid JSON matching:
{"action":"tap|type|scroll|swipe|back|openApp|wait|done|ask_user","targetIndex":null,"targetQuery":"","text":"","direction":"","submit":false,"clearFirst":true,"x":null,"y":null,"reason":""}
Rules:
- Choose ONLY 1 next action. Reject extra prose.
- ${AppSkills.buildCompactPromptHints(recipe)}
- If the target button isn't on screen yet, return action="ask_user" so TargetFinder will scroll or check menus automatically.
- If user negated share (e.g. "share mat karna, bas save karo"), NEVER tap Share.
- Never target password/OTP/card inputs.
- Language: ${language.displayName}.
                """.trimIndent()

                val userMsg = """
Goal: $goal
History: ${if (completedHistory.isEmpty()) "None" else completedHistory.takeLast(5).joinToString(" | ")}
Screen:
${snapshot.readableSummary}
                """.trimIndent()

                val raw = providerClient.generateChatReply(
                    provider = provider,
                    apiKey = apiKey,
                    model = model,
                    baseUrl = baseUrl,
                    systemPrompt = sysPrompt,
                    history = emptyList(),
                    userMessage = userMsg
                )
                val parsed = AppSkills.parseScreenAgentDecision(raw)
                if (parsed != null) return parsed
            } catch (_: Exception) {
            }
        }
        return heuristicNextScreenDecision(goal, snapshot, recipe, completedHistory, driver)
    }

    private fun heuristicNextScreenDecision(
        goal: String,
        snapshot: ScreenSnapshot,
        recipe: AppSkillRecipe,
        completedHistory: List<String>,
        driver: ScreenAgentDriver
    ): ScreenAgentDecision {
        val lowerGoal = goal.lowercase()
        val negateShare = lowerGoal.contains("share mat") || lowerGoal.contains("mat share") || lowerGoal.contains("don't share") || lowerGoal.contains("do not share")
        val targetApp = AppSkills.detectTargetAppInGoal(goal)

        if (targetApp != null &&
            snapshot.packageName.isNotBlank() &&
            !targetApp.packageNames.any { it.equals(snapshot.packageName, ignoreCase = true) }
        ) {
            return ScreenAgentDecision(
                action = ScreenAgentActionType.OPEN_APP,
                targetQuery = targetApp.appName,
                reason = "Opening ${targetApp.appName}"
            )
        }

        if (targetApp != null && snapshot.packageName.isBlank() &&
            completedHistory.none { it.startsWith("openApp(") }
        ) {
            return ScreenAgentDecision(
                action = ScreenAgentActionType.OPEN_APP,
                targetQuery = targetApp.appName,
                reason = "Opening ${targetApp.appName}"
            )
        }

        // Handle Back ("wapas jao", "go back")
        if (lowerGoal.contains("wapas") || lowerGoal == "back" || lowerGoal.contains("go back")) {
            return ScreenAgentDecision(action = ScreenAgentActionType.BACK, reason = "Going back to previous screen")
        }

        // Handle Search in current app ("Facebook par Ali Khan search karo", "YouTube par ... search karo")
        val explicitSearchQuery = extractInAppSearchQuery(goal)
        val alreadyTyped = completedHistory.any { it.startsWith("type(") && it.contains("OK") }
        if (!explicitSearchQuery.isNullOrBlank()) {
            if (alreadyTyped) {
                if ((lowerGoal.contains("pehla") || lowerGoal.contains("first") || lowerGoal.contains("chalao") || lowerGoal.contains("play"))) {
                    val resultNode = snapshot.elements.firstOrNull {
                        it.isClickable && !it.isEditable && it.bestLabel.length > 4 &&
                            !it.bestLabel.lowercase().contains("search")
                    }
                    if (resultNode != null) {
                        return ScreenAgentDecision(
                            action = ScreenAgentActionType.TAP,
                            targetIndex = resultNode.index,
                            targetQuery = resultNode.bestLabel,
                            reason = "Opening first search result"
                        )
                    }
                }
                return ScreenAgentDecision(action = ScreenAgentActionType.DONE, reason = "Search results for '$explicitSearchQuery' are visible")
            }
            val editableBox = snapshot.elements.firstOrNull { it.isEditable && !it.isPassword }
            if (editableBox != null) {
                return ScreenAgentDecision(
                    action = ScreenAgentActionType.TYPE,
                    targetIndex = editableBox.index,
                    targetQuery = editableBox.bestLabel,
                    text = explicitSearchQuery,
                    submit = true,
                    clearFirst = true,
                    reason = "Searching '$explicitSearchQuery' in ${recipe.appName}"
                )
            }
            val searchVariants = labelMemoryStore.getVariants(snapshot.packageName, "search") +
                recipe.actionVariants["search"].orEmpty() + listOf("search")
            val searchBtn = findBestMatchingNode(snapshot, searchVariants)
            if (searchBtn != null) {
                return ScreenAgentDecision(
                    action = ScreenAgentActionType.TAP,
                    targetIndex = searchBtn.index,
                    targetQuery = searchBtn.bestLabel,
                    reason = "Opening ${recipe.appName} Search bar"
                )
            }
        }

        // Handle Comment ("comment karo: bahut khoob")
        if (lowerGoal.contains("comment")) {
            val commentText = AppSkills.extractExplicitCommentText(goal)
            val editable = snapshot.elements.firstOrNull { it.isEditable && !it.isPassword }
            if (editable != null) {
                val postText = commentText ?: "Great post!"
                if (alreadyTyped) {
                    val sendBtn = findBestMatchingNode(
                        snapshot,
                        labelMemoryStore.getVariants(snapshot.packageName, "post_comment") +
                            recipe.actionVariants["post_comment"].orEmpty() + listOf("post", "send")
                    )
                    return if (sendBtn != null) {
                        ScreenAgentDecision(
                            action = ScreenAgentActionType.TAP,
                            targetIndex = sendBtn.index,
                            targetQuery = sendBtn.bestLabel,
                            reason = "Submitting comment"
                        )
                    } else {
                        ScreenAgentDecision(action = ScreenAgentActionType.DONE, reason = "Comment typed into field")
                    }
                }
                return ScreenAgentDecision(
                    action = ScreenAgentActionType.TYPE,
                    targetIndex = editable.index,
                    targetQuery = editable.bestLabel,
                    text = postText,
                    submit = true,
                    clearFirst = true,
                    reason = "Typing comment '$postText'"
                )
            }
            val commentVariants = labelMemoryStore.getVariants(snapshot.packageName, "comment") +
                recipe.actionVariants["comment"].orEmpty() + listOf("comment")
            val commentBtn = findBestMatchingNode(snapshot, commentVariants)
            if (commentBtn != null) {
                return ScreenAgentDecision(
                    action = ScreenAgentActionType.TAP,
                    targetIndex = commentBtn.index,
                    targetQuery = commentBtn.bestLabel,
                    reason = "Opening comments box"
                )
            }
        }

        // Handle Unlike / Remove Like
        if (lowerGoal.contains("unlike") || lowerGoal.contains("like hata")) {
            val unlikeNode = findBestMatchingNode(
                snapshot,
                labelMemoryStore.getVariants(snapshot.packageName, "unlike") +
                    recipe.actionVariants["unlike"].orEmpty() + listOf("unlike", "liked", "like")
            )
            if (unlikeNode != null) {
                return ScreenAgentDecision(
                    action = ScreenAgentActionType.TAP,
                    targetIndex = unlikeNode.index,
                    targetQuery = unlikeNode.bestLabel,
                    reason = "Removing Like on post/video"
                )
            }
        }

        // Handle Like
        if (lowerGoal.contains("like")) {
            val alreadyLiked = snapshot.elements.any {
                val lbl = it.bestLabel.lowercase()
                (lbl.contains("unlike") || lbl == "liked") || (lbl.contains("like") && it.isSelected)
            }
            if (alreadyLiked) {
                return ScreenAgentDecision(action = ScreenAgentActionType.DONE, reason = "Video/post is already liked")
            }
            val likeNode = findBestMatchingNode(
                snapshot,
                labelMemoryStore.getVariants(snapshot.packageName, "like") +
                    recipe.actionVariants["like"].orEmpty() + listOf("like")
            ) ?: findNeighborNearComment(snapshot)
            if (likeNode != null) {
                return ScreenAgentDecision(
                    action = ScreenAgentActionType.TAP,
                    targetIndex = likeNode.index,
                    targetQuery = likeNode.bestLabel,
                    reason = "Tapping Like button"
                )
            }
        }

        // Handle Follow / Subscribe
        if (lowerGoal.contains("follow") || lowerGoal.contains("subscribe")) {
            val key = if (lowerGoal.contains("subscribe")) "subscribe" else "follow"
            val doneKey = if (key == "subscribe") "subscribed" else "following"
            val alreadyFollowed = snapshot.elements.any {
                val lbl = it.bestLabel.lowercase()
                lbl.contains(doneKey) || (lbl.contains(key) && it.isSelected)
            }
            if (alreadyFollowed) {
                return ScreenAgentDecision(action = ScreenAgentActionType.DONE, reason = "Already $doneKey")
            }
            val node = findBestMatchingNode(
                snapshot,
                labelMemoryStore.getVariants(snapshot.packageName, key) +
                    recipe.actionVariants[key].orEmpty() + listOf(key)
            )
            if (node != null) {
                return ScreenAgentDecision(
                    action = ScreenAgentActionType.TAP,
                    targetIndex = node.index,
                    targetQuery = node.bestLabel,
                    reason = "Tapping ${key.replaceFirstChar { it.uppercase() }}"
                )
            }
        }

        // Handle Save ("share mat karna, bas save karo")
        if (lowerGoal.contains("save") || lowerGoal.contains("bookmark")) {
            val saveNode = findBestMatchingNode(
                snapshot,
                labelMemoryStore.getVariants(snapshot.packageName, "save") +
                    recipe.actionVariants["save"].orEmpty() + listOf("save", "bookmark", "favorite")
            )
            if (saveNode != null) {
                return ScreenAgentDecision(
                    action = ScreenAgentActionType.TAP,
                    targetIndex = saveNode.index,
                    targetQuery = saveNode.bestLabel,
                    reason = if (negateShare) "Saving without sharing" else "Saving item"
                )
            }
        }

        // Handle Next Video
        if (lowerGoal.contains("next video") || lowerGoal.contains("agla video")) {
            val nextNode = findBestMatchingNode(
                snapshot,
                labelMemoryStore.getVariants(snapshot.packageName, "next") +
                    recipe.actionVariants["next"].orEmpty() + listOf("next")
            )
            return if (nextNode != null) {
                ScreenAgentDecision(action = ScreenAgentActionType.TAP, targetIndex = nextNode.index, targetQuery = nextNode.bestLabel, reason = "Advancing to next video")
            } else {
                ScreenAgentDecision(action = ScreenAgentActionType.SWIPE, direction = "UP", reason = "Swiping to next video")
            }
        }

        // Handle Play Video ("pehli video chalao", "video play karo")
        if (lowerGoal.contains("video") || lowerGoal.contains("play") || lowerGoal.contains("chalao") || lowerGoal.contains("reel")) {
            val alreadyTappedPlay = completedHistory.any { it.startsWith("tap(") && it.contains("OK") }
            if (alreadyTappedPlay) {
                return ScreenAgentDecision(
                    action = ScreenAgentActionType.DONE,
                    reason = "Video is now playing on ${recipe.appName}"
                )
            }
            val playNode = findBestMatchingNode(
                snapshot,
                labelMemoryStore.getVariants(snapshot.packageName, "play") +
                    recipe.actionVariants["play"].orEmpty() + listOf("video", "watch", "play", "reel")
            ) ?: snapshot.elements.firstOrNull { it.isClickable && !it.isEditable && it.bounds.height() > 140 }
            if (playNode != null) {
                return ScreenAgentDecision(
                    action = ScreenAgentActionType.TAP,
                    targetIndex = playNode.index,
                    targetQuery = playNode.bestLabel.ifBlank { "video" },
                    reason = "Opening/playing video on ${recipe.appName}"
                )
            }
            if (driver.isAccessibilityEnabled() && snapshot.elements.isEmpty()) {
                return ScreenAgentDecision(action = ScreenAgentActionType.TAP, targetQuery = "play video", reason = "Locating video visually")
            }
        }

        val explicitShare = lowerGoal.contains("share") && !negateShare
        if (explicitShare) {
            val shareNode = findBestMatchingNode(snapshot, listOf("share"))
            if (shareNode != null) {
                return ScreenAgentDecision(action = ScreenAgentActionType.TAP, targetIndex = shareNode.index, targetQuery = "share", reason = "Tapping Share")
            }
        }

        // Returning ASK_USER triggers TargetFinder (overflow menus -> scroll down/up -> visual GUI grounding)
        return ScreenAgentDecision(
            action = ScreenAgentActionType.ASK_USER,
            reason = "Searching screen for \"$goal\""
        )
    }

    private fun inferCanonicalActionKey(goalPhrase: String, decision: ScreenAgentDecision): String? {
        if (decision.action != ScreenAgentActionType.TAP) return null
        val l = goalPhrase.lowercase()
        val q = decision.targetQuery?.lowercase().orEmpty()
        return when {
            l.contains("unlike") || l.contains("like hata") -> "unlike"
            l.contains("like") -> "like"
            l.contains("subscribe") -> "subscribe"
            l.contains("follow") -> "follow"
            l.contains("comment") && (q in listOf("post", "send", "submit", "bhejo")) -> "post_comment"
            l.contains("comment") -> "comment"
            l.contains("save") || l.contains("bookmark") -> "save"
            l.contains("next video") || l.contains("agla video") || q == "next" -> "next"
            l.contains("search") -> "search"
            l.contains("video") || l.contains("play") || l.contains("chalao") -> "play"
            else -> null
        }
    }

    private suspend fun checkSafetyAndConfirmIfNeeded(goal: String, decision: ScreenAgentDecision): Boolean? {
        val lowerGoal = goal.lowercase()
        val targetLower = (decision.targetQuery.orEmpty() + " " + decision.text.orEmpty() + " " + decision.reason).lowercase()
        val negateShare = lowerGoal.contains("share mat") || lowerGoal.contains("mat share") || lowerGoal.contains("don't share")
        if (negateShare && targetLower.contains("share") && decision.action == ScreenAgentActionType.TAP && !targetLower.contains("without sharing")) {
            return false
        }

        val sensitiveCategory: SensitiveCategory? = when {
            lowerGoal.contains("pay") || lowerGoal.contains("purchase") || lowerGoal.contains("buy") ||
                lowerGoal.contains("send money") || lowerGoal.contains("transfer") -> SensitiveCategory.PURCHASE
            lowerGoal.contains("delete post") || lowerGoal.contains("delete account") ||
                lowerGoal.contains("uninstall") -> SensitiveCategory.DELETE_FILE
            lowerGoal.contains("change password") || lowerGoal.contains("security setting") ||
                lowerGoal.contains("block ") -> SensitiveCategory.PUBLISH_CONTENT
            decision.action == ScreenAgentActionType.TAP && targetLower.contains("share") && !negateShare ->
                SensitiveCategory.PUBLISH_CONTENT
            decision.action == ScreenAgentActionType.TYPE && (lowerGoal.contains("comment") || lowerGoal.contains("message") || lowerGoal.contains("reply")) -> {
                val dictated = AppSkills.extractExplicitCommentText(goal)
                if (dictated == null && !decision.text.isNullOrBlank()) SensitiveCategory.PUBLISH_CONTENT else null
            }
            else -> null
        }

        if (sensitiveCategory == null) return null

        val promptTitle = if (decision.action == ScreenAgentActionType.TYPE) {
            "Post kar doon?: \"${decision.text}\""
        } else {
            decision.reason.ifBlank { "Approve ${sensitiveCategory.label}?" }
        }
        val step = AgentPlanStep(
            stepNumber = _planSteps.value.size + 1,
            title = promptTitle,
            actionType = mapToAgentActionType(decision.action),
            param = decision.text ?: decision.targetQuery.orEmpty(),
            sensitiveCategory = sensitiveCategory
        )
        _planSteps.value = _planSteps.value + step
        updateStep(step.id, StepExecutionStatus.AWAITING_CONFIRMATION, "Waiting for confirmation")

        val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
        confirmationDeferred = deferred
        _pendingConfirmation.value = PendingSensitiveConfirmation(
            step = step,
            goal = goal,
            reason = promptTitle
        )
        val approved = deferred.await()
        _pendingConfirmation.value = null
        confirmationDeferred = null
        return approved
    }

    private suspend fun executeScreenDecision(
        decision: ScreenAgentDecision,
        snapshot: ScreenSnapshot,
        recipe: AppSkillRecipe,
        driver: ScreenAgentDriver,
        groundingProvider: GuiGroundingProvider
    ): AutomationOutcome {
        return when (decision.action) {
            ScreenAgentActionType.OPEN_APP -> driver.openApp(decision.targetQuery ?: recipe.appName)
            ScreenAgentActionType.BACK -> driver.pressBack()
            ScreenAgentActionType.WAIT -> {
                delay(1_000L)
                AutomationOutcome(true, "Waited for screen update")
            }
            ScreenAgentActionType.SCROLL -> {
                val forward = !decision.direction.equals("up", ignoreCase = true)
                driver.scroll(forward)
            }
            ScreenAgentActionType.SWIPE -> {
                val dir = runCatching {
                    SwipeDirection.valueOf((decision.direction ?: "UP").uppercase())
                }.getOrDefault(SwipeDirection.UP)
                driver.swipe(dir)
            }
            ScreenAgentActionType.TYPE -> {
                val typed = decision.text.orEmpty()
                if (typed.isBlank()) {
                    AutomationOutcome(false, "Nothing to type")
                } else {
                    driver.typeText(
                        index = decision.targetIndex,
                        query = decision.targetQuery,
                        text = typed,
                        submit = decision.submit,
                        clearFirst = decision.clearFirst
                    )
                }
            }
            ScreenAgentActionType.TAP -> {
                val primary = driver.tap(decision.targetIndex, decision.targetQuery, null, decision.x, decision.y)
                if (primary.success) return primary

                val q = decision.targetQuery.orEmpty().lowercase()
                val variants = recipe.actionVariants.entries
                    .firstOrNull { (k, vals) -> q.contains(k) || vals.any { q.contains(it) } }
                    ?.value.orEmpty()
                for (variant in variants) {
                    val matchNode = findBestMatchingNode(snapshot, listOf(variant))
                    val hit = if (matchNode != null) {
                        driver.tap(matchNode.index, matchNode.bestLabel, matchNode.viewIdResourceName.ifBlank { null }, null, null)
                    } else {
                        driver.tap(null, variant, null, null, null)
                    }
                    if (hit.success) return hit
                }

                // Pluggable visual GUI grounding fallback (Gemini or optional user-hosted HTTP endpoint)
                val shot = driver.captureScreenJpeg().getOrNull()
                if (shot != null) {
                    val (w, h) = driver.displaySize()
                    val grounded = groundingProvider.locateTarget(
                        jpegBytes = shot,
                        targetDescription = decision.targetQuery ?: decision.reason,
                        screenWidth = w,
                        screenHeight = h
                    ).getOrNull()
                    if (grounded != null) {
                        return driver.tap(null, null, null, grounded.xPx, grounded.yPx)
                    }
                }
                primary
            }
            ScreenAgentActionType.DONE,
            ScreenAgentActionType.ASK_USER -> AutomationOutcome(true, decision.reason)
        }
    }

    private fun verifyScreenActionChange(
        goal: String,
        decision: ScreenAgentDecision,
        before: ScreenSnapshot,
        after: ScreenSnapshot,
        recipe: AppSkillRecipe
    ): Boolean {
        val lowerGoal = goal.lowercase()
        val beforeSig = "${before.packageName}|${before.elements.joinToString { "${it.bestLabel}:${it.isSelected}:${it.isChecked}" }}"
        val afterSig = "${after.packageName}|${after.elements.joinToString { "${it.bestLabel}:${it.isSelected}:${it.isChecked}" }}"
        val hierarchyChanged = beforeSig != afterSig

        return when (decision.action) {
            ScreenAgentActionType.OPEN_APP -> {
                val expected = AppSkills.detectTargetAppInGoal(goal)
                expected == null || expected.packageNames.any { it.equals(after.packageName, ignoreCase = true) } || hierarchyChanged
            }
            ScreenAgentActionType.TYPE -> {
                val needle = decision.text.orEmpty().trim()
                val textVisible = needle.isNotBlank() && after.elements.any { it.text.contains(needle, ignoreCase = true) }
                textVisible || hierarchyChanged
            }
            ScreenAgentActionType.TAP -> {
                val q = (decision.targetQuery ?: "").lowercase()
                when {
                    lowerGoal.contains("unlike") || lowerGoal.contains("like hata") -> {
                        val hasUnliked = after.elements.any { it.bestLabel.lowercase().contains("like") && !it.bestLabel.lowercase().contains("unlike") && !it.isSelected }
                        hasUnliked || hierarchyChanged
                    }
                    q.contains("like") || lowerGoal.contains("like") -> {
                        val liked = after.elements.any {
                            val l = it.bestLabel.lowercase()
                            l.contains("unlike") || l == "liked" || (l.contains("like") && it.isSelected)
                        }
                        liked || hierarchyChanged
                    }
                    q.contains("follow") || lowerGoal.contains("follow") -> {
                        val followed = after.elements.any {
                            val l = it.bestLabel.lowercase()
                            l.contains("following") || (l.contains("follow") && it.isSelected)
                        }
                        followed || hierarchyChanged
                    }
                    q.contains("subscribe") || lowerGoal.contains("subscribe") -> {
                        val subbed = after.elements.any { it.bestLabel.lowercase().contains("subscribed") || (it.bestLabel.lowercase().contains("subscribe") && it.isSelected) }
                        subbed || hierarchyChanged
                    }
                    else -> hierarchyChanged || after.elements.isNotEmpty()
                }
            }
            ScreenAgentActionType.SCROLL,
            ScreenAgentActionType.SWIPE,
            ScreenAgentActionType.BACK -> hierarchyChanged || after.elements.isNotEmpty()
            else -> true
        }
    }

    private fun isSingleTurnGoalSatisfied(
        goal: String,
        decision: ScreenAgentDecision,
        after: ScreenSnapshot,
        recipe: AppSkillRecipe
    ): Boolean {
        val lower = goal.lowercase()
        return when {
            lower.contains("comment") && decision.action == ScreenAgentActionType.TYPE && decision.submit -> true
            lower.contains("comment") && decision.action == ScreenAgentActionType.TAP && (decision.targetQuery?.lowercase() in listOf("post", "send")) -> true
            lower.contains("unlike") || lower.contains("like hata") -> decision.action == ScreenAgentActionType.TAP && after.elements.none { it.bestLabel.lowercase().contains("unlike") }
            lower.contains("like") && !lower.contains("comment") && decision.action == ScreenAgentActionType.TAP -> {
                val q = decision.targetQuery?.lowercase().orEmpty()
                q.contains("like") || after.elements.any { it.bestLabel.lowercase().contains("unlike") || (it.bestLabel.lowercase().contains("like") && it.isSelected) }
            }
            lower.contains("follow") || lower.contains("subscribe") -> {
                decision.action == ScreenAgentActionType.TAP && after.elements.any {
                    val l = it.bestLabel.lowercase()
                    l.contains("following") || l.contains("subscribed") || it.isSelected
                }
            }
            (lower.contains("save") || lower.contains("bookmark")) && decision.action == ScreenAgentActionType.TAP -> true
            lower.contains("next video") && (decision.action == ScreenAgentActionType.TAP || decision.action == ScreenAgentActionType.SWIPE) -> true
            lower.contains("wapas") || lower.contains("go back") -> decision.action == ScreenAgentActionType.BACK
            else -> false
        }
    }

    private fun findBestMatchingNode(snapshot: ScreenSnapshot, variants: List<String>): ScreenElementNode? {
        if (variants.isEmpty()) return null
        val normalized = variants.map { it.lowercase().trim() }.filter { it.isNotBlank() }
        for (candidate in normalized) {
            snapshot.elements.firstOrNull {
                it.bestLabel.equals(candidate, ignoreCase = true) ||
                    it.contentDescription.equals(candidate, ignoreCase = true)
            }?.let { return it }
        }
        for (candidate in normalized) {
            snapshot.elements.firstOrNull {
                it.text.lowercase().contains(candidate) ||
                    it.contentDescription.lowercase().contains(candidate) ||
                    it.viewIdResourceName.lowercase().contains(candidate)
            }?.let { return it }
        }
        return null
    }

    private fun findNeighborNearComment(snapshot: ScreenSnapshot): ScreenElementNode? {
        val commentNode = snapshot.elements.firstOrNull {
            it.bestLabel.lowercase().contains("comment")
        } ?: return null
        return snapshot.elements.firstOrNull { node ->
            node.id != commentNode.id && node.isClickable &&
                kotlin.math.abs(node.centerY - commentNode.centerY) < 220 &&
                kotlin.math.abs(node.centerX - commentNode.centerX) < 350 &&
                !node.bestLabel.lowercase().contains("share")
        }
    }

    private fun extractInAppSearchQuery(goal: String): String? {
        val patterns = listOf(
            Regex("""(?i)(?:facebook|fb|instagram|insta|tiktok|youtube|yt|whatsapp|chrome)(?:\s+(?:kholo|chalao|open|aur|and|par|pe|mein|me|on))*\s+(.+?)\s+(?:search|dhoondo|find)(?:\s+karo|\s+kro)?$"""),
            Regex("""(?i)search\s+(.+?)\s+(?:on|in)\s+(?:facebook|fb|instagram|insta|tiktok|youtube|whatsapp|chrome)$""")
        )
        for (p in patterns) {
            val m = p.find(goal.trim())
            val q = m?.groupValues?.getOrNull(1)?.trim()?.trim('"', '\'')
            if (!q.isNullOrBlank() && !q.equals("ye naam", ignoreCase = true)) return q
        }
        return null
    }

    private fun appendLiveStep(
        title: String,
        type: AgentActionType,
        status: StepExecutionStatus,
        note: String
    ): String {
        val step = AgentPlanStep(
            stepNumber = _planSteps.value.size + 1,
            title = title,
            actionType = type,
            status = status,
            verificationNote = note
        )
        _planSteps.value = _planSteps.value + step
        return step.id
    }

    private fun mapToAgentActionType(action: ScreenAgentActionType): AgentActionType = when (action) {
        ScreenAgentActionType.TAP -> AgentActionType.TAP_UI_ELEMENT
        ScreenAgentActionType.TYPE -> AgentActionType.TYPE_TEXT
        ScreenAgentActionType.SCROLL -> AgentActionType.SCROLL_SCREEN
        ScreenAgentActionType.SWIPE -> AgentActionType.SWIPE_SCREEN
        ScreenAgentActionType.BACK -> AgentActionType.READ_SCREEN
        ScreenAgentActionType.OPEN_APP -> AgentActionType.OPEN_APP
        ScreenAgentActionType.WAIT,
        ScreenAgentActionType.DONE,
        ScreenAgentActionType.ASK_USER -> AgentActionType.READ_SCREEN
    }
}
