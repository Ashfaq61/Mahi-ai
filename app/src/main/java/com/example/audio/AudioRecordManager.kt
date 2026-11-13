package com.example.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

enum class MicCaptureState {
    IDLE,
    CAPTURING,
    PERMISSION_DENIED,
    HARDWARE_ERROR
}

data class AudioFramePacket(
    val pcm16Bytes: ByteArray,
    val sampleRate: Int,
    val timestampMs: Long,
    val rmsAmplitude: Float,
    val isSpeechDetected: Boolean
)

/**
 * Persistent non-blocking 16kHz PCM16 microphone capture pipeline.
 * - Uses VOICE_COMMUNICATION + hardware AEC/NS when available
 * - Queues PCM frames into a bounded Channel (DROP_OLDEST) so AudioRecord.read never stalls
 * - Detects active speech energy above noise floor without falsely marking mic active on failure
 */
class AudioRecordManager {
    companion object {
        private const val TAG = "AudioRecordManager"
        const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val NUM_BANDS = 32
        private const val FRAME_SAMPLES = 640 // 40ms @ 16kHz -> 1280 bytes/chunk
        private const val SPEECH_RMS_THRESHOLD = 0.045f
    }

    private var audioRecord: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null

    private var captureJob: Job? = null
    private var dispatchJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var audioQueue: Channel<AudioFramePacket> = Channel(
        capacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val isRecordingFlag = AtomicBoolean(false)

    private val _captureState = MutableStateFlow(MicCaptureState.IDLE)
    val captureState: StateFlow<MicCaptureState> = _captureState.asStateFlow()

    private val _amplitudeFlow = MutableStateFlow(0f)
    val amplitudeFlow: StateFlow<Float> = _amplitudeFlow.asStateFlow()

    private val _waveformBarsFlow = MutableStateFlow(List(NUM_BANDS) { 0.06f })
    val waveformBarsFlow: StateFlow<List<Float>> = _waveformBarsFlow.asStateFlow()

    private val _isSpeechDetected = MutableStateFlow(false)
    val isSpeechDetected: StateFlow<Boolean> = _isSpeechDetected.asStateFlow()

    private val _lastCaptureError = MutableStateFlow<String?>(null)
    val lastCaptureError: StateFlow<String?> = _lastCaptureError.asStateFlow()

    @Volatile
    var onPcmAudioFrame: ((ByteArray) -> Unit)? = null

    @Volatile
    var onAudioPacket: ((AudioFramePacket) -> Unit)? = null

    @Volatile
    var isInputMuted: Boolean = false

    fun isActivelyCapturing(): Boolean =
        isRecordingFlag.get() && _captureState.value == MicCaptureState.CAPTURING

    @SuppressLint("MissingPermission")
    fun startListening(): Boolean {
        if (isRecordingFlag.get()) return true

        _lastCaptureError.value = null
        return try {
            val minBufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT
            )
            if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
                _captureState.value = MicCaptureState.HARDWARE_ERROR
                _lastCaptureError.value = "Microphone hardware rejected 16kHz PCM16 configuration."
                return false
            }

            val bufferSize = max(minBufferSize * 2, FRAME_SAMPLES * 2 * 4)
            var record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                record = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
                )
            }

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                _captureState.value = MicCaptureState.HARDWARE_ERROR
                _lastCaptureError.value = "Unable to initialize Android microphone AudioRecord."
                return false
            }

            attachAudioEffects(record.audioSessionId)
            record.startRecording()

            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                record.release()
                _captureState.value = MicCaptureState.HARDWARE_ERROR
                _lastCaptureError.value = "Android denied or failed active microphone recording."
                return false
            }

            audioRecord = record
            audioQueue.close()
            audioQueue = Channel(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
            isRecordingFlag.set(true)
            _captureState.value = MicCaptureState.CAPTURING

            startDispatcherLoop()
            startCaptureLoop(record)
            true
        } catch (se: SecurityException) {
            Log.e(TAG, "RECORD_AUDIO permission missing", se)
            stopListening()
            _captureState.value = MicCaptureState.PERMISSION_DENIED
            _lastCaptureError.value = "Microphone permission not granted."
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start persistent AudioRecord", e)
            stopListening()
            _captureState.value = MicCaptureState.HARDWARE_ERROR
            _lastCaptureError.value = e.localizedMessage ?: "Microphone capture error"
            false
        }
    }

    private fun startDispatcherLoop() {
        dispatchJob?.cancel()
        dispatchJob = scope.launch {
            for (packet in audioQueue) {
                if (!isRecordingFlag.get()) break
                try {
                    if (!isInputMuted) {
                        onPcmAudioFrame?.invoke(packet.pcm16Bytes)
                        onAudioPacket?.invoke(packet)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Audio consumer error", e)
                }
            }
        }
    }

    private fun startCaptureLoop(record: AudioRecord) {
        captureJob?.cancel()
        captureJob = scope.launch {
            val buffer = ShortArray(FRAME_SAMPLES)
            var phase = 0.0
            var noiseFloor = 0.012f
            var lastVoiceMs = 0L

            while (isActive && isRecordingFlag.get()) {
                val readCount = try {
                    record.read(buffer, 0, buffer.size)
                } catch (e: Exception) {
                    Log.e(TAG, "AudioRecord.read failed", e)
                    -1
                }

                if (readCount < 0) {
                    _lastCaptureError.value = "Microphone stream interrupted (code=$readCount)."
                    _captureState.value = MicCaptureState.HARDWARE_ERROR
                    break
                }

                if (readCount > 0) {
                    var sumSquares = 0.0
                    val pcmBytes = ByteArray(readCount * 2)
                    for (i in 0 until readCount) {
                        val s = buffer[i]
                        sumSquares += s.toDouble() * s.toDouble()
                        pcmBytes[i * 2] = (s.toInt() and 0xFF).toByte()
                        pcmBytes[i * 2 + 1] = ((s.toInt() ushr 8) and 0xFF).toByte()
                    }

                    val rms = sqrt(sumSquares / readCount).toFloat() / 32767f
                    val amp = min(1.0f, rms * 8.5f)
                    noiseFloor = (noiseFloor * 0.96f + min(rms, 0.08f) * 0.04f).coerceIn(0.008f, 0.06f)

                    val now = System.currentTimeMillis()
                    val speechActive = !isInputMuted && rms > max(SPEECH_RMS_THRESHOLD, noiseFloor * 2.2f)
                    if (speechActive) {
                        lastVoiceMs = now
                    }
                    val hangoverSpeech = !isInputMuted && (now - lastVoiceMs) < 350L

                    _isSpeechDetected.value = hangoverSpeech
                    _amplitudeFlow.value = if (isInputMuted) 0f else amp

                    phase += 0.18
                    _waveformBarsFlow.value = List(NUM_BANDS) { index ->
                        val centerFactor = 1.0f - abs(index - NUM_BANDS / 2) / (NUM_BANDS / 2f)
                        val wave1 = abs(sin(phase + index * 0.45).toFloat())
                        val wave2 = abs(sin(phase * 0.7 - index * 0.25).toFloat())
                        val effectiveAmp = if (isInputMuted) 0f else amp
                        val reactiveHeight = effectiveAmp * (0.4f * wave1 + 0.6f * wave2) * (0.6f + 0.4f * centerFactor)
                        val idleNoise = if (isInputMuted) 0.03f else (0.06f + 0.04f * wave1)
                        min(1.0f, max(idleNoise, reactiveHeight))
                    }

                    audioQueue.trySend(
                        AudioFramePacket(
                            pcm16Bytes = pcmBytes,
                            sampleRate = SAMPLE_RATE,
                            timestampMs = now,
                            rmsAmplitude = amp,
                            isSpeechDetected = hangoverSpeech
                        )
                    )
                }
            }
        }
    }

    private fun attachAudioEffects(sessionId: Int) {
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
            }
        } catch (_: Exception) {
        }
        try {
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
            }
        } catch (_: Exception) {
        }
    }

    fun stopListening() {
        isRecordingFlag.set(false)
        captureJob?.cancel()
        captureJob = null
        audioQueue.close()
        dispatchJob?.cancel()
        dispatchJob = null

        try {
            echoCanceler?.release()
        } catch (_: Exception) {
        }
        echoCanceler = null

        try {
            noiseSuppressor?.release()
        } catch (_: Exception) {
        }
        noiseSuppressor = null

        try {
            if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord?.stop()
            }
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioRecord", e)
        }
        audioRecord = null
        _captureState.value = MicCaptureState.IDLE
        _isSpeechDetected.value = false
        _amplitudeFlow.value = 0f
        _waveformBarsFlow.value = List(NUM_BANDS) { 0.06f }
    }
}
