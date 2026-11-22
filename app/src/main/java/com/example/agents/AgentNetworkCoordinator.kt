package com.example.agents

import android.app.ActivityManager
import android.content.Context
import com.example.data.AgentCollaborationEntity
import com.example.data.MahiDataRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.UUID

enum class AgentNodeStatus {
    READY,
    ACTIVE,
    RECOVERED,
    FAULTED
}

data class AgentNodeInfo(
    val id: String,
    val displayName: String,
    val role: String,
    val status: AgentNodeStatus = AgentNodeStatus.READY,
    val lastHeartbeatMs: Long = System.currentTimeMillis(),
    val messagesHandled: Int = 0
)

data class AgentBusMessage(
    val id: String = UUID.randomUUID().toString(),
    val fromAgentId: String,
    val toAgentId: String,
    val topic: String,
    val summary: String,
    val timestampMs: Long = System.currentTimeMillis()
)

class AgentNetworkCoordinator(
    private val context: Context,
    private val repository: MahiDataRepository? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Single-active-LLM lock on devices <= 6 GB RAM so heavy AI tasks never OOM-thrash
    val isLowRamMode: Boolean = run {
        val am = context.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(mi)
        val totalGb = mi.totalMem.toFloat() / (1024f * 1024f * 1024f)
        totalGb in 0.1f..6.2f
    }

    private val llmConcurrencySemaphore = Semaphore(if (isLowRamMode) 1 else 2)

    private val _busMessages = MutableSharedFlow<AgentBusMessage>(replay = 20, extraBufferCapacity = 32)
    val busMessages: SharedFlow<AgentBusMessage> = _busMessages.asSharedFlow()

    private val _recentLogs = MutableStateFlow<List<AgentBusMessage>>(emptyList())
    val recentLogs: StateFlow<List<AgentBusMessage>> = _recentLogs.asStateFlow()

    private val _nodes = MutableStateFlow(buildInitial17Agents())
    val nodes: StateFlow<List<AgentNodeInfo>> = _nodes.asStateFlow()

    private fun buildInitial17Agents(): List<AgentNodeInfo> = listOf(
        AgentNodeInfo("orchestrator", "01 Orchestrator", "Primary intent router & coordinator"),
        AgentNodeInfo("voice_pipeline", "02 Voice Pipeline", "Gemini Live / STT / TTS arbitration"),
        AgentNodeInfo("model_registry", "03 Model Registry", "Live capability discovery & fallback"),
        AgentNodeInfo("screen_reader", "04 Screen Reader V2", "Accessibility tree + redaction"),
        AgentNodeInfo("screen_autopilot", "05 Screen Autopilot", "Observe-Think-Act-Verify loop"),
        AgentNodeInfo("vision_grounding", "06 Vision Grounding", "Set-of-Mark pixel localization"),
        AgentNodeInfo("neural_lens", "07 Neural Lens", "CameraX & multimodal image Q&A"),
        AgentNodeInfo("notification_guard", "08 Notification Guard", "Redacted notifications & replies"),
        AgentNodeInfo("call_screen_bridge", "09 Call Bridge", "Telecom Screening & Speaker AI"),
        AgentNodeInfo("device_controller", "10 Device Control", "Volume, brightness, settings & torch"),
        AgentNodeInfo("permission_auditor", "11 Permission Auditor", "Live OS consent & OEM hints"),
        AgentNodeInfo("memory_guardian", "12 Memory Guardian", "Consent-gated user preferences"),
        AgentNodeInfo("research_scout", "13 Research Scout", "Web & Wikipedia verified answers"),
        AgentNodeInfo("repo_evaluator", "14 Repo & Model Scout", "GitHub & Hugging Face vetting"),
        AgentNodeInfo("geo_places_agent", "15 Geo & Places", "GPS location & nearby lookup"),
        AgentNodeInfo("marketplace_guard", "16 Marketplace Guard", "Static security & tool self-test"),
        AgentNodeInfo("sandbox_release", "17 Sandbox & Release", "Isolated patch scanner & SHA-256")
    )

    suspend fun <T> withLlmConcurrencyGuard(agentId: String, topic: String, block: suspend () -> T): T {
        markNodeStatus(agentId, AgentNodeStatus.ACTIVE)
        dispatchMessage(agentId, "orchestrator", topic, "Acquired LLM execution slot")
        return try {
            llmConcurrencySemaphore.withPermit {
                block()
            }
        } catch (t: Throwable) {
            markNodeStatus(agentId, AgentNodeStatus.FAULTED)
            dispatchMessage(agentId, "orchestrator", "fault_recovery", "Recovered from ${t.javaClass.simpleName}: ${t.localizedMessage}")
            markNodeStatus(agentId, AgentNodeStatus.RECOVERED)
            throw t
        } finally {
            if (_nodes.value.firstOrNull { it.id == agentId }?.status == AgentNodeStatus.ACTIVE) {
                markNodeStatus(agentId, AgentNodeStatus.READY)
            }
        }
    }

    fun dispatchMessage(fromId: String, toId: String, topic: String, summary: String) {
        val msg = AgentBusMessage(fromAgentId = fromId, toAgentId = toId, topic = topic, summary = summary)
        _busMessages.tryEmit(msg)
        _recentLogs.value = (listOf(msg) + _recentLogs.value).take(50)
        _nodes.value = _nodes.value.map { node ->
            if (node.id == fromId || node.id == toId) {
                node.copy(lastHeartbeatMs = msg.timestampMs, messagesHandled = node.messagesHandled + 1)
            } else {
                node
            }
        }
        repository?.let { repo ->
            scope.launch {
                repo.logAgentCollaboration(
                    AgentCollaborationEntity(
                        id = msg.id,
                        fromAgent = fromId,
                        toAgent = toId,
                        topic = topic,
                        payloadSummary = summary,
                        status = "DELIVERED",
                        timestampMs = msg.timestampMs
                    )
                )
            }
        }
    }

    fun runHeartbeatSweep(): Int {
        val now = System.currentTimeMillis()
        var recovered = 0
        _nodes.value = _nodes.value.map { node ->
            if (node.status == AgentNodeStatus.FAULTED) {
                recovered++
                node.copy(status = AgentNodeStatus.RECOVERED, lastHeartbeatMs = now)
            } else {
                node.copy(lastHeartbeatMs = now)
            }
        }
        dispatchMessage("orchestrator", "all", "heartbeat", "Swept 17 agents (recovered=$recovered)")
        return _nodes.value.size
    }

    private fun markNodeStatus(agentId: String, status: AgentNodeStatus) {
        val now = System.currentTimeMillis()
        _nodes.value = _nodes.value.map {
            if (it.id == agentId) it.copy(status = status, lastHeartbeatMs = now) else it
        }
    }
}
