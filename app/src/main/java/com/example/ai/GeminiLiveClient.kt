package com.example.ai

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Base64
import android.util.Log
import com.example.api.ApiErrorCategory
import com.example.api.isGeminiLiveAudioModel
import com.example.tools.ToolAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class GeminiLiveError(
    val category: ApiErrorCategory,
    val message: String
)

interface GeminiLiveCallbacks {
    fun onSetupComplete()
    fun onUserTranscript(text: String)
    fun onAssistantTranscript(text: String)
    fun onAssistantSpeakingChanged(isSpeaking: Boolean)
    fun onToolCall(id: String, action: ToolAction)
    fun onInterrupted()
    fun onError(error: GeminiLiveError)
    fun onDisconnected()
}

/**
 * Real-time Gemini Multimodal Live API WebSocket client (Audio-to-Audio ONLY).
 * - Streams PCM16 16kHz microphone audio upward
 * - Plays 24kHz PCM16 voice responses via AudioTrack
 * - Executes function calls and sends toolResponse immediately
 * - Handles server & client interruptions
 * - Reports explicit API/key/quota/model/network errors without silent text fallback
 */
class GeminiLiveClient(
    private val okHttpClient: OkHttpClient = defaultWebSocketClient()
) {
    companion object {
        private const val TAG = "GeminiLiveClient"
        private const val LIVE_WS_ENDPOINT =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        private const val OUTPUT_SAMPLE_RATE = 24000

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

        fun buildSetupMessage(model: String, systemPrompt: String): String {
            val cleanModel = if (model.startsWith("models/")) model else "models/$model"
            return JSONObject().apply {
                put("setup", JSONObject().apply {
                    put("model", cleanModel)
                    put("generationConfig", JSONObject().apply {
                        put("responseModalities", JSONArray().apply { put("AUDIO") })
                        put("speechConfig", JSONObject().apply {
                            put("voiceConfig", JSONObject().apply {
                                put("prebuiltVoiceConfig", JSONObject().apply {
                                    put("voiceName", "Aoede")
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

        private fun buildToolDeclarations(): JSONArray = JSONArray().apply {
            put(JSONObject().apply {
                put("name", "openWebsite")
                put("description", "Open a website or app URL on the user's phone.")
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
                put("name", "searchWeb")
                put("description", "Search the web for a query.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("query", JSONObject().apply { put("type", "STRING") })
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
    }

    private var webSocket: WebSocket? = null
    private var audioTrack: AudioTrack? = null
    private var isSetupComplete = false
    private var isConnected = false
    private var isMuted = false
    private var manualDisconnect = false
    private var callbacks: GeminiLiveCallbacks? = null

    private val _speakingWaveform = MutableStateFlow(List(32) { 0.05f })
    val speakingWaveform: StateFlow<List<Float>> = _speakingWaveform.asStateFlow()

    private var waveformPhase = 0.0

    fun setMuted(muted: Boolean) {
        isMuted = muted
        if (muted) stopAudioPlayback()
    }

    fun connect(
        apiKey: String,
        model: String,
        systemPrompt: String,
        callbacks: GeminiLiveCallbacks
    ) {
        disconnect()
        val trimmedKey = apiKey.trim()
        if (trimmedKey.isEmpty()) {
            callbacks.onError(
                GeminiLiveError(
                    ApiErrorCategory.MISSING_KEY,
                    "Google Gemini API key is missing. Add your key in API Keys & Providers to start Gemini Live."
                )
            )
            return
        }

        if (!isGeminiLiveAudioModel(model)) {
            callbacks.onError(
                GeminiLiveError(
                    ApiErrorCategory.UNSUPPORTED_MODEL,
                    "Model '$model' does not support Gemini Live audio-to-audio. Select a Live model (e.g., gemini-2.5-flash-native-audio-preview-09-2025)."
                )
            )
            return
        }

        this.callbacks = callbacks
        this.manualDisconnect = false
        this.isSetupComplete = false
        initAudioTrack()

        val request = buildWebSocketRequest(trimmedKey)
        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnected = true
                val setupPayload = buildSetupMessage(model, systemPrompt)
                webSocket.send(setupPayload)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleServerMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleServerMessage(bytes.utf8())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                if (!manualDisconnect && code != 1000) {
                    isConnected = false
                    isSetupComplete = false
                    stopAudioPlayback()
                    callbacks.onError(classifyCloseOrFailureError(code, reason))
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                val wasManual = manualDisconnect
                isConnected = false
                isSetupComplete = false
                stopAudioPlayback()
                if (!wasManual) {
                    if (code != 1000) {
                        callbacks.onError(classifyCloseOrFailureError(code, reason))
                    } else {
                        callbacks.onDisconnected()
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val wasManual = manualDisconnect
                isConnected = false
                isSetupComplete = false
                stopAudioPlayback()
                if (!wasManual) {
                    val msg = response?.message?.takeIf { it.isNotBlank() }
                        ?: t.localizedMessage
                        ?: "WebSocket handshake failed"
                    callbacks.onError(classifyCloseOrFailureError(response?.code, msg))
                }
            }
        })
    }

    private fun handleServerMessage(rawJson: String) {
        try {
            val json = JSONObject(rawJson)

            if (json.has("error")) {
                val errObj = json.optJSONObject("error")
                val code = errObj?.optInt("code")
                val msg = errObj?.optString("message") ?: json.optString("error")
                callbacks?.onError(classifyCloseOrFailureError(code, msg))
                return
            }

            if (json.has("setupComplete")) {
                isSetupComplete = true
                callbacks?.onSetupComplete()
            }

            val serverContent = json.optJSONObject("serverContent")
            if (serverContent != null) {
                if (serverContent.optBoolean("interrupted", false)) {
                    stopAudioPlayback()
                    callbacks?.onAssistantSpeakingChanged(false)
                    callbacks?.onInterrupted()
                }

                serverContent.optJSONObject("inputTranscription")?.optString("text")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { callbacks?.onUserTranscript(it) }

                serverContent.optJSONObject("outputTranscription")?.optString("text")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { callbacks?.onAssistantTranscript(it) }

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
                                playPcmAudioChunk(pcmBytes)
                            }
                        }
                    }
                }

                if (serverContent.optBoolean("turnComplete", false)) {
                    _speakingWaveform.value = List(32) { 0.05f }
                    callbacks?.onAssistantSpeakingChanged(false)
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

    fun sendPcmAudioChunk(pcm16At16kBytes: ByteArray) {
        if (!isConnected || !isSetupComplete || pcm16At16kBytes.isEmpty()) return
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
        if (!isConnected || !isSetupComplete) return
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
        stopAudioPlayback()
        callbacks?.onAssistantSpeakingChanged(false)
    }

    private fun initAudioTrack() {
        stopAudioPlayback()
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
                .setBufferSizeInBytes(maxOf(minBuf * 2, 8192))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            audioTrack?.play()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize 24kHz AudioTrack", e)
        }
    }

    private fun playPcmAudioChunk(pcmBytes: ByteArray) {
        if (isMuted || pcmBytes.isEmpty()) return
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
            audioTrack?.write(pcmBytes, 0, pcmBytes.size)
        } catch (e: Exception) {
            Log.w(TAG, "Error writing PCM chunk to AudioTrack", e)
        }
    }

    private fun stopAudioPlayback() {
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

    fun isSessionActive(): Boolean = isConnected && isSetupComplete

    fun disconnect() {
        manualDisconnect = true
        isConnected = false
        isSetupComplete = false
        try {
            webSocket?.close(1000, "Client closed session")
        } catch (_: Exception) {
        }
        webSocket = null
        stopAudioPlayback()
    }
}
