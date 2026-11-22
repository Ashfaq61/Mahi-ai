package com.example.ai

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.util.Base64
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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class GeminiLiveStatus(val label: String) {
    IDLE("Disconnected"),
    CONNECTING("Connecting Gemini Live…"),
    LISTENING("Gemini Live Listening"),
    THINKING("Gemini Live Thinking…"),
    SPEAKING("Gemini Live Speaking"),
    RECONNECTING("Reconnecting Gemini Live…"),
    BLOCKED("Blocked / Key or Network Required"),
    ERROR("Connection Error")
}

data class LiveToolCallRequest(
    val id: String,
    val name: String,
    val params: Map<String, String>
)

class GeminiLiveClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
) {
    companion object {
        private const val LIVE_WS_URL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        const val INPUT_SAMPLE_RATE = 16_000
        const val OUTPUT_SAMPLE_RATE = 24_000

        private fun prop(type: String, description: String): JSONObject =
            JSONObject().put("type", type).put("description", description)

        private fun fnDecl(
            name: String,
            description: String,
            properties: Map<String, JSONObject>,
            required: List<String> = emptyList()
        ): JSONObject = JSONObject().apply {
            put("name", name)
            put("description", description)
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    properties.forEach { (k, v) -> put(k, v) }
                })
                if (required.isNotEmpty()) {
                    put("required", JSONArray(required))
                }
            })
        }

        fun buildToolDeclarations(): JSONArray = JSONArray().apply {
            put(fnDecl("readScreen", "Read current foreground app accessibility UI tree.", emptyMap()))
            put(fnDecl(
                "tapElement",
                "Tap a UI element on screen by index, text query, viewId, or coordinates.",
                mapOf(
                    "index" to prop("INTEGER", "1-based element index from readScreen"),
                    "query" to prop("STRING", "Visible label or contentDescription"),
                    "viewId" to prop("STRING", "Optional resource viewId"),
                    "x" to prop("NUMBER", "Optional X coordinate"),
                    "y" to prop("NUMBER", "Optional Y coordinate")
                )
            ))
            put(fnDecl(
                "typeText",
                "Type text into an editable field on screen.",
                mapOf(
                    "index" to prop("INTEGER", "1-based target input index"),
                    "query" to prop("STRING", "Input field label"),
                    "text" to prop("STRING", "Text to type"),
                    "append" to prop("BOOLEAN", "Append instead of replacing"),
                    "clearFirst" to prop("BOOLEAN", "Clear text before typing"),
                    "submit" to prop("BOOLEAN", "Submit or press send after typing")
                ),
                required = listOf("text")
            ))
            put(fnDecl("captureScreenImage", "Capture current screen screenshot for visual inspection.", mapOf("question" to prop("STRING", "Question about screen"))))
            put(fnDecl("describeScreen", "Read and explain the current screen using accessibility + AI.", mapOf("question" to prop("STRING", "Specific question"))))
            put(fnDecl("analyzeScreenshot", "Capture screenshot and explain it.", mapOf("question" to prop("STRING", "Specific question"))))
            put(fnDecl("readTextFromCamera", "Read OCR text from latest camera frame.", mapOf("explain" to prop("BOOLEAN", "Explain with AI"))))
            put(fnDecl("readTextFromScreenshot", "Read OCR text from screenshot.", mapOf("explain" to prop("BOOLEAN", "Explain with AI"))))
            put(fnDecl("identifyObjects", "Identify objects in latest camera/image.", emptyMap()))
            put(fnDecl("recognizeScene", "Recognize scene from latest image.", emptyMap()))
            put(fnDecl("describeCameraView", "Describe what camera sees.", mapOf("question" to prop("STRING", "Question"))))
            put(fnDecl("summarizeNotifications", "Safely summarize notifications with PII redaction.", emptyMap()))
            put(fnDecl("openGalleryAnalysis", "Open photo picker to analyze an image.", emptyMap()))
            put(fnDecl("compareImages", "Compare two selected images.", emptyMap()))
            put(fnDecl("askAboutLastImage", "Ask question about last selected image.", mapOf("question" to prop("STRING", "Question")), required = listOf("question")))
            put(fnDecl(
                "screenGesture",
                "Perform swipe, scroll, back, or STOP gesture on screen.",
                mapOf(
                    "action" to prop("STRING", "SWIPE_UP, SWIPE_DOWN, SWIPE_LEFT, SWIPE_RIGHT, FAST_SCROLL_DOWN, FAST_SCROLL_UP, TAP_INDEX, DOUBLE_TAP, LONG_PRESS, STOP"),
                    "targetText" to prop("STRING", "Optional target text"),
                    "targetIndex" to prop("INTEGER", "Optional 1-based target index"),
                    "startX" to prop("NUMBER", "Optional drag start X"),
                    "startY" to prop("NUMBER", "Optional drag start Y"),
                    "endX" to prop("NUMBER", "Optional drag end X"),
                    "endY" to prop("NUMBER", "Optional drag end Y")
                ),
                required = listOf("action")
            ))
            put(fnDecl("setVolume", "Adjust Android audio stream volume percentage.", mapOf("stream" to prop("STRING", "media, ringtone, alarm, notification, call"), "percent" to prop("INTEGER", "0 to 100")), required = listOf("percent")))
            put(fnDecl("openQuickSettings", "Expand Android Quick Settings panel.", emptyMap()))
            put(fnDecl("performScreenTask", "Execute multi-step autonomous screen goal on an app.", mapOf("goal" to prop("STRING", "User goal")), required = listOf("goal")))
            put(fnDecl("setWatchMode", "Turn continuous screen watch mode ON or OFF.", mapOf("enabled" to prop("BOOLEAN", "true or false")), required = listOf("enabled")))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var webSocket: WebSocket? = null
    private var micJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var audioTrack: AudioTrack? = null
    private val activeFlag = AtomicBoolean(false)
    private var resumptionHandle: String? = null
    private var lastApiKey: String = ""
    private var lastModel: String = "gemini-2.0-flash-live-001"
    private var lastSystemPrompt: String = ""
    private var lastVoiceName: String = "Aoede"

    private val _status = MutableStateFlow(GeminiLiveStatus.IDLE)
    val status: StateFlow<GeminiLiveStatus> = _status.asStateFlow()

    private val _inputTranscript = MutableStateFlow("")
    val inputTranscript: StateFlow<String> = _inputTranscript.asStateFlow()

    private val _outputTranscript = MutableStateFlow("")
    val outputTranscript: StateFlow<String> = _outputTranscript.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    var onToolCall: (suspend (LiveToolCallRequest) -> String)? = null
    var onMicAmplitude: ((Float) -> Unit)? = null

    fun isSessionActive(): Boolean = activeFlag.get() && webSocket != null

    fun connect(
        apiKey: String,
        model: String = "gemini-2.0-flash-live-001",
        systemPrompt: String,
        voiceName: String = "Aoede",
        startMicCapture: Boolean = true
    ): Result<Unit> {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) {
            val msg = "Gemini API key required for Gemini Live BidiGenerateContent."
            _status.value = GeminiLiveStatus.BLOCKED
            _lastError.value = msg
            return Result.failure(IllegalStateException(msg))
        }
        disconnect()
        lastApiKey = cleanKey
        lastModel = model.removePrefix("models/").ifBlank { "gemini-2.0-flash-live-001" }
        lastSystemPrompt = systemPrompt
        lastVoiceName = voiceName
        activeFlag.set(true)
        _status.value = GeminiLiveStatus.CONNECTING
        _lastError.value = null

        val request = Request.Builder()
            .url(LIVE_WS_URL)
            .addHeader("x-goog-api-key", cleanKey)
            .build()

        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                sendSetup(webSocket, lastModel, lastSystemPrompt, lastVoiceName)
                initAudioTrack()
                if (startMicCapture) startMicLoop()
                _status.value = GeminiLiveStatus.LISTENING
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleServerJson(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val msg = "Gemini Live disconnected (${response?.code ?: 0}): ${t.localizedMessage ?: "socket failure"}"
                _lastError.value = msg
                _status.value = GeminiLiveStatus.ERROR
                if (activeFlag.get() && resumptionHandle != null) {
                    scheduleReconnect()
                } else {
                    stopAudioHardware()
                    activeFlag.set(false)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                stopAudioHardware()
                activeFlag.set(false)
                _status.value = GeminiLiveStatus.IDLE
            }
        })
        return Result.success(Unit)
    }

    private fun scheduleReconnect() {
        _status.value = GeminiLiveStatus.RECONNECTING
        scope.launch {
            delay(1_400L)
            if (activeFlag.get() && lastApiKey.isNotBlank()) {
                connect(lastApiKey, lastModel, lastSystemPrompt, lastVoiceName, startMicCapture = true)
            }
        }
    }

    private fun sendSetup(ws: WebSocket, model: String, systemPrompt: String, voiceName: String) {
        val setup = JSONObject().apply {
            put("model", "models/$model")
            put("generationConfig", JSONObject().apply {
                put("responseModalities", JSONArray().put("AUDIO"))
                put("speechConfig", JSONObject().apply {
                    put("voiceConfig", JSONObject().apply {
                        put("prebuiltVoiceConfig", JSONObject().apply {
                            put("voiceName", voiceName)
                        })
                    })
                })
            })
            put("inputAudioTranscription", JSONObject())
            put("outputAudioTranscription", JSONObject())
            if (resumptionHandle != null) {
                put("sessionResumption", JSONObject().put("handle", resumptionHandle))
            } else {
                put("sessionResumption", JSONObject())
            }
            if (systemPrompt.isNotBlank()) {
                put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
            }
            put("tools", JSONArray().put(JSONObject().put("functionDeclarations", buildToolDeclarations())))
        }
        ws.send(JSONObject().put("setup", setup).toString())
    }

    fun sendTextTurn(userText: String): Boolean {
        val ws = webSocket ?: return false
        interruptPlayback()
        _status.value = GeminiLiveStatus.THINKING
        val msg = JSONObject().apply {
            put("clientContent", JSONObject().apply {
                put("turns", JSONArray().put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().put(JSONObject().put("text", userText)))
                }))
                put("turnComplete", true)
            })
        }
        return ws.send(msg.toString())
    }

    fun sendImageFrame(jpegBytes: ByteArray): Boolean {
        val ws = webSocket ?: return false
        if (jpegBytes.isEmpty()) return false
        val msg = JSONObject().apply {
            put("realtimeInput", JSONObject().apply {
                put("mediaChunks", JSONArray().put(JSONObject().apply {
                    put("mimeType", "image/jpeg")
                    put("data", Base64.encodeToString(jpegBytes, Base64.NO_WRAP))
                }))
            })
        }
        return ws.send(msg.toString())
    }

    fun interruptPlayback() {
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.play()
            if (_status.value == GeminiLiveStatus.SPEAKING) {
                _status.value = GeminiLiveStatus.LISTENING
            }
        } catch (_: Exception) {
        }
    }

    private fun handleServerJson(raw: String) {
        try {
            val root = JSONObject(raw)
            root.optJSONObject("sessionResumptionUpdate")?.optString("newHandle")?.takeIf { it.isNotBlank() }?.let {
                resumptionHandle = it
            }
            if (root.has("goAway")) {
                scheduleReconnect()
                return
            }
            root.optJSONObject("serverContent")?.let { sc ->
                if (sc.optBoolean("interrupted", false)) {
                    interruptPlayback()
                }
                sc.optJSONObject("inputTranscription")?.optString("text")?.takeIf { it.isNotBlank() }?.let { t ->
                    interruptPlayback()
                    _inputTranscript.value = t
                }
                sc.optJSONObject("outputTranscription")?.optString("text")?.takeIf { it.isNotBlank() }?.let { t ->
                    _outputTranscript.value = (_outputTranscript.value + t).takeLast(600)
                }
                val parts = sc.optJSONObject("modelTurn")?.optJSONArray("parts")
                if (parts != null) {
                    for (i in 0 until parts.length()) {
                        val p = parts.optJSONObject(i) ?: continue
                        p.optString("text").takeIf { it.isNotBlank() }?.let { t ->
                            _outputTranscript.value = t
                        }
                        val inline = p.optJSONObject("inlineData")
                        val mime = inline?.optString("mimeType").orEmpty()
                        val b64 = inline?.optString("data").orEmpty()
                        if (mime.startsWith("audio/pcm") && b64.isNotBlank()) {
                            _status.value = GeminiLiveStatus.SPEAKING
                            val pcm = Base64.decode(b64, Base64.DEFAULT)
                            playPcmChunk(pcm)
                        }
                    }
                }
                if (sc.optBoolean("turnComplete", false)) {
                    _status.value = GeminiLiveStatus.LISTENING
                }
            }
            root.optJSONObject("toolCall")?.optJSONArray("functionCalls")?.let { calls ->
                handleToolCalls(calls)
            }
        } catch (_: Exception) {
        }
    }

    private fun handleToolCalls(calls: JSONArray) {
        val handler = onToolCall ?: return
        scope.launch {
            val responses = JSONArray()
            for (i in 0 until calls.length()) {
                val c = calls.optJSONObject(i) ?: continue
                val id = c.optString("id")
                val name = c.optString("name")
                val argsObj = c.optJSONObject("args") ?: JSONObject()
                val params = mutableMapOf<String, String>()
                val keys = argsObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    params[k] = argsObj.opt(k)?.toString().orEmpty()
                }
                val resultText = runCatching { handler(LiveToolCallRequest(id, name, params)) }
                    .getOrElse { "Tool error: ${it.localizedMessage}" }
                responses.put(JSONObject().apply {
                    put("id", id)
                    put("name", name)
                    put("response", JSONObject().put("result", resultText))
                })
            }
            val payload = JSONObject().put("toolResponse", JSONObject().put("functionResponses", responses))
            webSocket?.send(payload.toString())
        }
    }

    private fun initAudioTrack() {
        try {
            val minBuf = AudioTrack.getMinBufferSize(
                OUTPUT_SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(OUTPUT_SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBuf * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build().also { it.play() }
        } catch (_: Exception) {
        }
    }

    private fun playPcmChunk(pcm: ByteArray) {
        try {
            audioTrack?.write(pcm, 0, pcm.size)
        } catch (_: Exception) {
        }
    }

    private fun startMicLoop() {
        micJob?.cancel()
        micJob = scope.launch {
            try {
                val minBuf = AudioRecord.getMinBufferSize(
                    INPUT_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(3200)
                val rec = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    INPUT_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf * 2
                )
                if (rec.state != AudioRecord.STATE_INITIALIZED) {
                    rec.release()
                    return@launch
                }
                audioRecord = rec
                if (AcousticEchoCanceler.isAvailable()) {
                    echoCanceler = runCatching {
                        AcousticEchoCanceler.create(rec.audioSessionId)?.apply { enabled = true }
                    }.getOrNull()
                }
                rec.startRecording()
                val buf = ByteArray(1600)
                while (isActive && activeFlag.get()) {
                    val read = rec.read(buf, 0, buf.size)
                    if (read > 0) {
                        var sum = 0L
                        var idx = 0
                        while (idx + 1 < read) {
                            val sample = (buf[idx].toInt() and 0xFF) or (buf[idx + 1].toInt() shl 8)
                            sum += kotlin.math.abs(sample.toShort().toInt())
                            idx += 2
                        }
                        val avgAmp = (sum.toFloat() / (read / 2).coerceAtLeast(1) / 32768f).coerceIn(0f, 1f)
                        onMicAmplitude?.invoke(avgAmp)
                        if (avgAmp > 0.14f && _status.value == GeminiLiveStatus.SPEAKING) {
                            // Barge-in: user started speaking over Mahi
                            interruptPlayback()
                        }
                        val b64 = Base64.encodeToString(buf.copyOf(read), Base64.NO_WRAP)
                        val chunkJson = JSONObject().put(
                            "realtimeInput",
                            JSONObject().put(
                                "mediaChunks",
                                JSONArray().put(
                                    JSONObject().put("mimeType", "audio/pcm;rate=16000").put("data", b64)
                                )
                            )
                        )
                        webSocket?.send(chunkJson.toString())
                    }
                }
            } catch (se: SecurityException) {
                _lastError.value = "RECORD_AUDIO permission required for Gemini Live mic: ${se.localizedMessage}"
                _status.value = GeminiLiveStatus.BLOCKED
            } catch (_: Exception) {
            }
        }
    }

    private fun stopAudioHardware() {
        micJob?.cancel()
        micJob = null
        runCatching { echoCanceler?.release() }
        echoCanceler = null
        runCatching {
            audioRecord?.stop()
            audioRecord?.release()
        }
        audioRecord = null
        runCatching {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.release()
        }
        audioTrack = null
    }

    fun disconnect() {
        activeFlag.set(false)
        stopAudioHardware()
        runCatching { webSocket?.close(1000, "User stopped") }
        webSocket = null
        _status.value = GeminiLiveStatus.IDLE
    }
}
