package com.example.ai

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Base64
import android.util.Log
import com.example.api.ApiErrorCategory
import com.example.api.isGeminiLiveAudioModel
import com.example.tools.ToolAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class GeminiLiveError(
    val category: ApiErrorCategory,
    val message: String
)

enum class GeminiLiveConnectionPhase {
    DISCONNECTED,
    CONNECTING,
    CONNECTED_LISTENING,
    SPEAKING,
    RECONNECTING,
    ERROR
}

interface GeminiLiveCallbacks {
    fun onSetupComplete()
    fun onUserTranscript(text: String)
    fun onAssistantTranscript(text: String)
    fun onAssistantSpeakingChanged(isSpeaking: Boolean)
    fun onToolCall(id: String, action: ToolAction)
    fun onInterrupted()
    fun onReconnecting(attempt: Int, maxAttempts: Int, reason: String) {}
    fun onError(error: GeminiLiveError)
    fun onDisconnected()
}

/**
 * Persistent full-duplex Gemini Multimodal Live WebSocket client (Audio-to-Audio ONLY):
 * - Awaits setupComplete strictly before sending queued 16kHz PCM audio chunks
 * - Decodes & plays every 24kHz PCM audio chunk sequentially via a persistent Channel -> AudioTrack worker
 * - Prevents premature turnComplete playback truncation by letting queued audio finish draining
 * - Supports immediate interruption / barge-in without stopping continuous mic streaming
 * - Prevents duplicate sessions via monotonic sessionGeneration IDs
 * - Auto-reconnects on transient drops with bounded exponential backoff + conversation context preservation
 * - Never exposes API keys in logs and never silently downgrades to text-only mode
 */
class GeminiLiveClient(
    private val okHttpClient: OkHttpClient = defaultWebSocketClient()
) {
    companion object {
        private const val TAG = "GeminiLiveClient"
        private const val LIVE_WS_ENDPOINT =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        private const val OUTPUT_SAMPLE_RATE = 24000
        const val MAX_RECONNECT_ATTEMPTS = 3

        fun defaultWebSocketClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()

        fun buildWebSocketRequest(apiKey: String, baseWsUrl: String = LIVE_WS_ENDPOINT): Request {
            return Request.Builder()
                .url(baseWsUrl)
                .header("x-goog-api-key", apiKey.trim())
                .build()
        }

        fun buildSetupMessage(
            model: String,
            systemPrompt: String,
            voiceName: String = "Aoede"
        ): String {
            val cleanModel = if (model.startsWith("models/")) model else "models/$model"
            return JSONObject().apply {
                put("setup", JSONObject().apply {
                    put("model", cleanModel)
                    put("generationConfig", JSONObject().apply {
                        put("responseModalities", JSONArray().apply { put("AUDIO") })
                        put("speechConfig", JSONObject().apply {
                            put("voiceConfig", JSONObject().apply {
                                put("prebuiltVoiceConfig", JSONObject().apply {
                                    put("voiceName", voiceName)
                                })
                            })
                        })
                    })
                    put("systemInstruction", JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply { put("text", systemPrompt) })
                        })
                    })
                    put("inputAudioTranscription", JSONObject())
                    put("outputAudioTranscription", JSONObject())
                    put("tools", JSONArray().apply {
                        put(JSONObject().apply {
                            put("functionDeclarations", buildToolDeclarations())
                        })
                    })
                })
            }.toString()
        }

        fun buildToolDeclarations(): JSONArray = JSONArray().apply {
            put(JSONObject().apply {
                put("name", "openApp")
                put("description", "Launch any installed Android app on the phone such as Facebook, TikTok, YouTube, Google, Chrome, WhatsApp, Instagram, Spotify, Settings, or Camera.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("appName", JSONObject().apply { put("type", "STRING") })
                        put("url", JSONObject().apply { put("type", "STRING") })
                        put("followUpGesture", JSONObject().apply { put("type", "STRING") })
                    })
                    put("required", JSONArray().apply { put("appName") })
                })
            })
            put(JSONObject().apply {
                put("name", "openCamera")
                put("description", "Open the Camera Studio with live CameraX preview (back or front lens).")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("frontOrBack", JSONObject().apply { put("type", "STRING") })
                    })
                })
            })
            put(JSONObject().apply {
                put("name", "switchCamera")
                put("description", "Switch active camera between front selfie lens and back lens.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("frontOrBack", JSONObject().apply { put("type", "STRING") })
                    })
                    put("required", JSONArray().apply { put("frontOrBack") })
                })
            })
            put(JSONObject().apply {
                put("name", "capturePhoto")
                put("description", "Capture a photo or 3-second countdown selfie and save it to MediaStore Gallery.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("isSelfie", JSONObject().apply { put("type", "BOOLEAN") })
                    })
                })
            })
            put(JSONObject().apply {
                put("name", "startVideoRecording")
                put("description", "Start visible video recording with live timer using CameraX.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject())
                })
            })
            put(JSONObject().apply {
                put("name", "stopVideoRecording")
                put("description", "Stop the active CameraX video recording and save the MP4 to MediaStore Movies.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject())
                })
            })
            put(JSONObject().apply {
                put("name", "setFlash")
                put("description", "Enable or disable camera flash or torch.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("enabled", JSONObject().apply { put("type", "BOOLEAN") })
                    })
                    put("required", JSONArray().apply { put("enabled") })
                })
            })
            put(JSONObject().apply {
                put("name", "setZoom")
                put("description", "Adjust camera zoom ratio level (e.g. 1.0, 1.5, 2.0) or step direction ('in' / 'out').")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("level", JSONObject().apply { put("type", "STRING") })
                    })
                    put("required", JSONArray().apply { put("level") })
                })
            })
            put(JSONObject().apply {
                put("name", "openGoogle")
                put("description", "Open the Google Search app or Google homepage.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject())
                })
            })
            put(JSONObject().apply {
                put("name", "googleSearch")
                put("description", "Search Google for a query using the Google app or browser with proper URL encoding and show summary options.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("query", JSONObject().apply { put("type", "STRING") })
                    })
                    put("required", JSONArray().apply { put("query") })
                })
            })
            put(JSONObject().apply {
                put("name", "youtubeSearch")
                put("description", "Search YouTube natively for videos matching the query.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("query", JSONObject().apply { put("type", "STRING") })
                    })
                    put("required", JSONArray().apply { put("query") })
                })
            })
            put(JSONObject().apply {
                put("name", "openWebsite")
                put("description", "Open a website URL in Chrome or the default browser on the user's phone.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("url", JSONObject().apply { put("type", "STRING") })
                        put("title", JSONObject().apply { put("type", "STRING") })
                    })
                    put("required", JSONArray().apply { put("url") })
                })
            })
            put(JSONObject().apply {
                put("name", "verifyAppLaunch")
                put("description", "Verify whether a specific Android package name is installed, enabled, and launchable.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("packageName", JSONObject().apply { put("type", "STRING") })
                    })
                    put("required", JSONArray().apply { put("packageName") })
                })
            })
            put(JSONObject().apply {
                put("name", "stopAutomation")
                put("description", "Immediately stop all running screen gestures, scrolling, and autonomous automation.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject())
                })
            })
            put(JSONObject().apply {
                put("name", "screenGesture")
                put("description", "Control screen gestures: SWIPE_UP, SWIPE_DOWN, SWIPE_LEFT, SWIPE_RIGHT, FAST_SCROLL_DOWN, FAST_SCROLL_UP, SCROLL_TOP, SCROLL_BOTTOM, CONTINUOUS_SCROLL_DOWN, CONTINUOUS_SCROLL_UP, TAP_TEXT, DOUBLE_TAP, LONG_PRESS, STOP.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("action", JSONObject().apply { put("type", "STRING") })
                        put("targetText", JSONObject().apply { put("type", "STRING") })
                    })
                    put("required", JSONArray().apply { put("action") })
                })
            })
            put(JSONObject().apply {
                put("name", "searchWeb")
                put("description", "General web or YouTube search fallback.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("query", JSONObject().apply { put("type", "STRING") })
                        put("target", JSONObject().apply { put("type", "STRING") })
                    })
                    put("required", JSONArray().apply { put("query") })
                })
            })
            put(JSONObject().apply {
                put("name", "setTimer")
                put("description", "Set a countdown timer in minutes.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("minutes", JSONObject().apply { put("type", "INTEGER") })
                        put("label", JSONObject().apply { put("type", "STRING") })
                    })
                    put("required", JSONArray().apply { put("minutes") })
                })
            })
            put(JSONObject().apply {
                put("name", "toggleFlashlight")
                put("description", "Turn the phone flashlight on or off.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("state", JSONObject().apply { put("type", "STRING") })
                    })
                    put("required", JSONArray().apply { put("state") })
                })
            })
            put(JSONObject().apply {
                put("name", "changeMood")
                put("description", "Change Mahi's persona mood: SASSY, FLIRTY, PLAYFUL, SAVAGE, or SWEET.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("mood", JSONObject().apply { put("type", "STRING") })
                    })
                    put("required", JSONArray().apply { put("mood") })
                })
            })
        }

        fun classifyCloseOrFailureError(code: Int?, reasonOrMessage: String): GeminiLiveError {
            val lower = reasonOrMessage.lowercase()
            return when {
                code == 401 || code == 403 || lower.contains("api key") ||
                    lower.contains("unauthenticated") || lower.contains("permission_denied") ||
                    lower.contains("unauthorized") -> {
                    GeminiLiveError(
                        ApiErrorCategory.INVALID_KEY,
                        "Invalid Gemini API key or access denied${if (reasonOrMessage.isNotBlank()) ": ${reasonOrMessage.take(140)}" else "."}"
                    )
                }
                code == 429 || lower.contains("quota") || lower.contains("resource_exhausted") ||
                    lower.contains("rate limit") -> {
                    GeminiLiveError(
                        ApiErrorCategory.QUOTA_EXCEEDED,
                        "Gemini Live quota or rate limit exceeded${if (reasonOrMessage.isNotBlank()) ": ${reasonOrMessage.take(140)}" else "."}"
                    )
                }
                code == 404 || lower.contains("not found") || lower.contains("not supported for bidigeneratecontent") ||
                    lower.contains("unsupported model") || (lower.contains("model") && lower.contains("invalid")) -> {
                    GeminiLiveError(
                        ApiErrorCategory.UNSUPPORTED_MODEL,
                        "Selected Gemini model does not support Live audio-to-audio${if (reasonOrMessage.isNotBlank()) ": ${reasonOrMessage.take(140)}" else "."}"
                    )
                }
                else -> {
                    GeminiLiveError(
                        ApiErrorCategory.CONNECTION_FAILED,
                        "Gemini Live connection failed${if (reasonOrMessage.isNotBlank()) ": ${reasonOrMessage.take(140)}" else "."}"
                    )
                }
            }
        }

        fun isRecoverableError(error: GeminiLiveError): Boolean {
            return error.category == ApiErrorCategory.CONNECTION_FAILED
        }
    }

    private sealed class PlaybackCommand {
        class AudioChunk(val pcmBytes: ByteArray) : PlaybackCommand()
        object TurnCompleteMarker : PlaybackCommand()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionCounter = AtomicLong(0L)
    private val reconnectAttempts = AtomicInteger(0)
    private val isPlaybackDrainingTurn = AtomicBoolean(false)

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var audioTrack: AudioTrack? = null

    @Volatile
    private var isSetupComplete = false

    @Volatile
    private var isConnected = false

    @Volatile
    private var isMuted = false

    @Volatile
    private var manualDisconnect = false

    @Volatile
    private var callbacks: GeminiLiveCallbacks? = null

    // Session params retained for bounded reconnect & context continuity
    private var lastApiKey: String = ""
    private var lastModel: String = ""
    private var lastSystemPrompt: String = ""
    private var lastVoiceName: String = "Aoede"
    private val recentTurns = mutableListOf<Pair<String, String>>() // role to text
    private var reconnectJob: Job? = null

    // Inbound pre-setup audio buffer (drops oldest if setup handshake is still in progress)
    private var inboundAudioBuffer = Channel<ByteArray>(
        capacity = 48,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private var inboundFlushJob: Job? = null

    // Persistent sequential playback queue so all PCM chunks play cleanly in order
    private var playbackQueue = Channel<PlaybackCommand>(capacity = Channel.UNLIMITED)
    private var playbackJob: Job? = null

    private val _connectionPhase = MutableStateFlow(GeminiLiveConnectionPhase.DISCONNECTED)
    val connectionPhase: StateFlow<GeminiLiveConnectionPhase> = _connectionPhase.asStateFlow()

    private val _speakingWaveform = MutableStateFlow(List(32) { 0.05f })
    val speakingWaveform: StateFlow<List<Float>> = _speakingWaveform.asStateFlow()

    private var waveformPhase = 0.0

    fun setMuted(muted: Boolean) {
        isMuted = muted
        if (muted) flushPlaybackQueueOnly()
    }

    fun updateSystemInstruction(newSystemPrompt: String) {
        lastSystemPrompt = newSystemPrompt
    }

    fun connect(
        apiKey: String,
        model: String,
        systemPrompt: String,
        callbacks: GeminiLiveCallbacks,
        voiceName: String = "Aoede"
    ) {
        reconnectJob?.cancel()
        reconnectAttempts.set(0)
        recentTurns.clear()

        val trimmedKey = apiKey.trim()
        if (trimmedKey.isEmpty()) {
            _connectionPhase.value = GeminiLiveConnectionPhase.ERROR
            callbacks.onError(
                GeminiLiveError(
                    ApiErrorCategory.MISSING_KEY,
                    "Google Gemini API key is missing. Add your key in API Keys & Providers to start Gemini Live."
                )
            )
            return
        }

        if (!isGeminiLiveAudioModel(model)) {
            _connectionPhase.value = GeminiLiveConnectionPhase.ERROR
            callbacks.onError(
                GeminiLiveError(
                    ApiErrorCategory.UNSUPPORTED_MODEL,
                    "Model '$model' does not support Gemini Live audio-to-audio. Select a Live model (e.g., gemini-2.5-flash-native-audio-preview-09-2025)."
                )
            )
            return
        }

        lastApiKey = trimmedKey
        lastModel = model
        lastSystemPrompt = systemPrompt
        lastVoiceName = voiceName
        this.callbacks = callbacks

        openWebSocketInternal(isReconnectAttempt = false)
    }

    private fun openWebSocketInternal(isReconnectAttempt: Boolean) {
        // Cleanly close any stale socket before opening a new generation
        closeSocketOnly(manual = true)

        val generation = sessionCounter.incrementAndGet()
        manualDisconnect = false
        isConnected = false
        isSetupComplete = false
        _connectionPhase.value = if (isReconnectAttempt) {
            GeminiLiveConnectionPhase.RECONNECTING
        } else {
            GeminiLiveConnectionPhase.CONNECTING
        }

        ensurePlaybackWorkerRunning()
        ensureInboundAudioWorkerRunning(generation)

        val request = buildWebSocketRequest(lastApiKey)
        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (sessionCounter.get() != generation) {
                    webSocket.close(1000, "Superseded session")
                    return
                }
                isConnected = true
                val setupPayload = buildSetupMessage(lastModel, lastSystemPrompt, lastVoiceName)
                webSocket.send(setupPayload)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (sessionCounter.get() != generation) return
                handleServerMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (sessionCounter.get() != generation) return
                handleServerMessage(bytes.utf8())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (sessionCounter.get() != generation) return
                webSocket.close(1000, null)
                if (!manualDisconnect && code != 1000) {
                    handleConnectionInterruption(code, reason)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (sessionCounter.get() != generation) return
                isConnected = false
                isSetupComplete = false
                if (!manualDisconnect) {
                    if (code != 1000) {
                        handleConnectionInterruption(code, reason)
                    } else {
                        _connectionPhase.value = GeminiLiveConnectionPhase.DISCONNECTED
                        callbacks?.onDisconnected()
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (sessionCounter.get() != generation) return
                isConnected = false
                isSetupComplete = false
                if (!manualDisconnect) {
                    val msg = response?.message?.takeIf { it.isNotBlank() }
                        ?: t.localizedMessage
                        ?: "WebSocket handshake failed"
                    handleConnectionInterruption(response?.code, msg)
                }
            }
        })
    }

    private fun handleConnectionInterruption(code: Int?, reasonOrMessage: String) {
        val error = classifyCloseOrFailureError(code, reasonOrMessage)
        val attempt = reconnectAttempts.incrementAndGet()

        if (isRecoverableError(error) && attempt <= MAX_RECONNECT_ATTEMPTS && lastApiKey.isNotBlank()) {
            val backoffMs = 1000L * (1 shl (attempt - 1)) // 1s, 2s, 4s
            _connectionPhase.value = GeminiLiveConnectionPhase.RECONNECTING
            callbacks?.onReconnecting(attempt, MAX_RECONNECT_ATTEMPTS, error.message)
            reconnectJob?.cancel()
            reconnectJob = scope.launch {
                delay(backoffMs)
                if (!manualDisconnect) {
                    openWebSocketInternal(isReconnectAttempt = true)
                }
            }
        } else {
            isConnected = false
            isSetupComplete = false
            stopAudioPlaybackWorker()
            _connectionPhase.value = GeminiLiveConnectionPhase.ERROR
            callbacks?.onError(error)
        }
    }

    private fun handleServerMessage(rawJson: String) {
        try {
            val json = JSONObject(rawJson)

            if (json.has("error")) {
                val errObj = json.optJSONObject("error")
                val code = errObj?.optInt("code")
                val msg = errObj?.optString("message") ?: json.optString("error")
                val error = classifyCloseOrFailureError(code, msg)
                _connectionPhase.value = GeminiLiveConnectionPhase.ERROR
                callbacks?.onError(error)
                return
            }

            if (json.has("setupComplete")) {
                val wasReconnect = reconnectAttempts.get() > 0
                reconnectAttempts.set(0)
                isSetupComplete = true
                _connectionPhase.value = GeminiLiveConnectionPhase.CONNECTED_LISTENING
                if (wasReconnect && recentTurns.isNotEmpty()) {
                    replayRecentContextSummary()
                }
                callbacks?.onSetupComplete()
            }

            val serverContent = json.optJSONObject("serverContent")
            if (serverContent != null) {
                if (serverContent.optBoolean("interrupted", false)) {
                    flushPlaybackQueueOnly()
                    _connectionPhase.value = GeminiLiveConnectionPhase.CONNECTED_LISTENING
                    callbacks?.onAssistantSpeakingChanged(false)
                    callbacks?.onInterrupted()
                }

                serverContent.optJSONObject("inputTranscription")?.optString("text")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { userText ->
                        appendRecentTurn("user", userText)
                        callbacks?.onUserTranscript(userText)
                    }

                serverContent.optJSONObject("outputTranscription")?.optString("text")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { modelText ->
                        appendRecentTurn("model", modelText)
                        callbacks?.onAssistantTranscript(modelText)
                    }

                val modelTurn = serverContent.optJSONObject("modelTurn")
                val parts = modelTurn?.optJSONArray("parts")
                if (parts != null) {
                    for (i in 0 until parts.length()) {
                        val part = parts.optJSONObject(i) ?: continue
                        val inlineData = part.optJSONObject("inlineData")
                        if (inlineData != null) {
                            val mime = inlineData.optString("mimeType")
                            val b64 = inlineData.optString("data")
                            if (mime.startsWith("audio/pcm") && b64.isNotBlank()) {
                                val pcmBytes = Base64.decode(b64, Base64.DEFAULT)
                                if (pcmBytes.isNotEmpty() && !isMuted) {
                                    isPlaybackDrainingTurn.set(true)
                                    _connectionPhase.value = GeminiLiveConnectionPhase.SPEAKING
                                    playbackQueue.trySend(PlaybackCommand.AudioChunk(pcmBytes))
                                }
                            }
                        }
                    }
                }

                if (serverContent.optBoolean("turnComplete", false)) {
                    // Signal worker that turn finished generating; worker fires speakingChanged(false) ONLY after draining!
                    playbackQueue.trySend(PlaybackCommand.TurnCompleteMarker)
                }
            }

            val toolCall = json.optJSONObject("toolCall")
            val functionCalls = toolCall?.optJSONArray("functionCalls")
            if (functionCalls != null) {
                for (i in 0 until functionCalls.length()) {
                    val call = functionCalls.optJSONObject(i) ?: continue
                    val callId = call.optString("id")
                    val name = call.optString("name")
                    val argsObj = call.optJSONObject("args") ?: JSONObject()
                    val params = mutableMapOf<String, String>()
                    argsObj.keys().forEach { k ->
                        params[k] = argsObj.opt(k)?.toString().orEmpty()
                    }
                    callbacks?.onToolCall(callId, ToolAction(name, params))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Gemini Live server frame", e)
        }
    }

    private fun appendRecentTurn(role: String, text: String) {
        synchronized(recentTurns) {
            if (recentTurns.lastOrNull()?.first == role) {
                val prev = recentTurns.removeAt(recentTurns.lastIndex)
                recentTurns.add(role to (prev.second + " " + text).takeLast(320))
            } else {
                recentTurns.add(role to text.take(320))
            }
            while (recentTurns.size > 6) recentTurns.removeAt(0)
        }
    }

    private fun replayRecentContextSummary() {
        val summaryText = synchronized(recentTurns) {
            recentTurns.joinToString("\n") { "${it.first}: ${it.second}" }
        }
        if (summaryText.isBlank()) return
        val payload = JSONObject().apply {
            put("clientContent", JSONObject().apply {
                put("turns", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", "[Context continuity after reconnect — do not reply unless spoken to]:\n$summaryText")
                            })
                        })
                    })
                })
                put("turnComplete", false)
            })
        }.toString()
        webSocket?.send(payload)
    }

    private fun ensureInboundAudioWorkerRunning(generation: Long) {
        inboundFlushJob?.cancel()
        inboundAudioBuffer.close()
        inboundAudioBuffer = Channel(capacity = 48, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        inboundFlushJob = scope.launch {
            for (chunk in inboundAudioBuffer) {
                if (sessionCounter.get() != generation || manualDisconnect) break
                if (isConnected && isSetupComplete) {
                    sendPcmChunkOverSocket(chunk)
                }
            }
        }
    }

    fun sendPcmAudioChunk(pcm16At16kBytes: ByteArray) {
        if (pcm16At16kBytes.isEmpty() || manualDisconnect) return
        if (isConnected && isSetupComplete) {
            sendPcmChunkOverSocket(pcm16At16kBytes)
        } else {
            // Buffer cleanly during brief setupComplete / reconnect window without blocking caller
            inboundAudioBuffer.trySend(pcm16At16kBytes)
        }
    }

    private fun sendPcmChunkOverSocket(pcm16At16kBytes: ByteArray) {
        val b64 = Base64.encodeToString(pcm16At16kBytes, Base64.NO_WRAP)
        val payload = JSONObject().apply {
            put("realtimeInput", JSONObject().apply {
                put("mediaChunks", JSONArray().apply {
                    put(JSONObject().apply {
                        put("mimeType", "audio/pcm;rate=16000")
                        put("data", b64)
                    })
                })
            })
        }.toString()
        webSocket?.send(payload)
    }

    fun sendToolResponse(callId: String, name: String, resultMessage: String, success: Boolean) {
        if (!isConnected) return
        val payload = JSONObject().apply {
            put("toolResponse", JSONObject().apply {
                put("functionResponses", JSONArray().apply {
                    put(JSONObject().apply {
                        put("id", callId)
                        put("name", name)
                        put("response", JSONObject().apply {
                            put("success", success)
                            put("message", resultMessage)
                        })
                    })
                })
            })
        }.toString()
        webSocket?.send(payload)
    }

    fun sendTextPromptOverAudioSession(prompt: String) {
        if (!isConnected || !isSetupComplete || prompt.isBlank()) return
        appendRecentTurn("user", prompt)
        val payload = JSONObject().apply {
            put("clientContent", JSONObject().apply {
                put("turns", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply { put("text", prompt) })
                        })
                    })
                })
                put("turnComplete", true)
            })
        }.toString()
        webSocket?.send(payload)
    }

    fun interruptPlayback() {
        flushPlaybackQueueOnly()
        if (isConnected && isSetupComplete) {
            _connectionPhase.value = GeminiLiveConnectionPhase.CONNECTED_LISTENING
        }
        callbacks?.onAssistantSpeakingChanged(false)
    }

    private fun ensurePlaybackWorkerRunning() {
        if (playbackJob?.isActive == true) return
        playbackQueue.close()
        playbackQueue = Channel(capacity = Channel.UNLIMITED)
        playbackJob = scope.launch {
            initAudioTrack()
            for (cmd in playbackQueue) {
                if (!isActive || manualDisconnect) break
                when (cmd) {
                    is PlaybackCommand.AudioChunk -> {
                        if (!isMuted) {
                            writePcmToTrack(cmd.pcmBytes)
                        }
                    }
                    PlaybackCommand.TurnCompleteMarker -> {
                        isPlaybackDrainingTurn.set(false)
                        _speakingWaveform.value = List(32) { 0.05f }
                        if (isConnected && isSetupComplete) {
                            _connectionPhase.value = GeminiLiveConnectionPhase.CONNECTED_LISTENING
                        }
                        callbacks?.onAssistantSpeakingChanged(false)
                    }
                }
            }
        }
    }

    private fun initAudioTrack() {
        releaseAudioTrackOnly()
        try {
            val minBuf = AudioTrack.getMinBufferSize(
                OUTPUT_SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(OUTPUT_SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuf * 3, 16384))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            audioTrack?.play()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize persistent 24kHz AudioTrack", e)
        }
    }

    private fun writePcmToTrack(pcmBytes: ByteArray) {
        if (pcmBytes.isEmpty()) return
        callbacks?.onAssistantSpeakingChanged(true)

        var sumSquares = 0.0
        val sampleCount = pcmBytes.size / 2
        for (i in 0 until sampleCount) {
            val lo = pcmBytes[i * 2].toInt() and 0xFF
            val hi = pcmBytes[i * 2 + 1].toInt()
            val sample = ((hi shl 8) or lo).toShort().toDouble()
            sumSquares += sample * sample
        }
        if (sampleCount > 0) {
            val rms = sqrt(sumSquares / sampleCount)
            val amp = min(1.0f, (rms.toFloat() / 32767f) * 6.0f).coerceAtLeast(0.15f)
            waveformPhase += 0.28
            _speakingWaveform.value = List(32) { idx ->
                val centerFactor = 1.0f - abs(idx - 16) / 16f
                val wave = abs(sin(waveformPhase + idx * 0.35).toFloat())
                (amp * (0.45f + 0.55f * wave) * (0.55f + 0.45f * centerFactor)).coerceIn(0.08f, 1.0f)
            }
        }

        try {
            if (audioTrack == null || audioTrack?.playState != AudioTrack.PLAYSTATE_PLAYING) {
                initAudioTrack()
            }
            var offset = 0
            while (offset < pcmBytes.size && !manualDisconnect && !isMuted) {
                val written = audioTrack?.write(pcmBytes, offset, pcmBytes.size - offset) ?: break
                if (written <= 0) break
                offset += written
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error streaming PCM chunk to AudioTrack", e)
        }
    }

    private fun flushPlaybackQueueOnly() {
        while (playbackQueue.tryReceive().isSuccess) {
            // Drain unplayed audio chunks immediately on barge-in
        }
        isPlaybackDrainingTurn.set(false)
        _speakingWaveform.value = List(32) { 0.05f }
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.play()
        } catch (_: Exception) {
        }
    }

    private fun releaseAudioTrackOnly() {
        _speakingWaveform.value = List(32) { 0.05f }
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {
        }
        audioTrack = null
    }

    private fun stopAudioPlaybackWorker() {
        playbackJob?.cancel()
        playbackJob = null
        playbackQueue.close()
        releaseAudioTrackOnly()
    }

    private fun closeSocketOnly(manual: Boolean) {
        manualDisconnect = manual
        isConnected = false
        isSetupComplete = false
        try {
            webSocket?.close(1000, "Client closed session")
        } catch (_: Exception) {
        }
        webSocket = null
    }

    fun isSessionActive(): Boolean = isConnected && isSetupComplete

    fun disconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempts.set(0)
        inboundFlushJob?.cancel()
        inboundFlushJob = null
        inboundAudioBuffer.close()
        closeSocketOnly(manual = true)
        stopAudioPlaybackWorker()
        _connectionPhase.value = GeminiLiveConnectionPhase.DISCONNECTED
    }
}
