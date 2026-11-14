package com.example.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class WakeEngineStatus {
    OFF,
    STARTING,
    LISTENING_FOR_WAKE,
    PAUSED_FOR_ACTIVE_VOICE,
    PAUSED_AUDIO_FOCUS_LOST,
    ERROR
}

data class MicTestSnapshot(
    val isTesting: Boolean = false,
    val rmsLevel: Float = 0f,
    val dbLevel: Float = -60f,
    val isVoiceActive: Boolean = false,
    val statusText: String = "Tap Test Mic to verify microphone input."
)

/**
 * Offline Vosk + Energy/Duration calibrated wake word detector ("Hey Mahi") + real Mic Test +
 * AudioFocus arbitration. Strictly respects single-mic ownership: calling pauseForActiveVoice()
 * stops & releases AudioRecord completely before Gemini Live / SpeechRecognizer starts.
 */
class WakeWordEngine(
    private val context: Context,
    private val enrollmentStore: WakeEnrollmentStore,
    private val modelManager: WakeModelManager
) {
    companion object {
        private const val TAG = "WakeWordEngine"
        private const val SAMPLE_RATE = 16000
        private const val READ_SAMPLES = 1600 // 100ms frames
        private const val WAKE_COOLDOWN_MS = 2800L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val isRunning = AtomicBoolean(false)
    private var listenJob: Job? = null
    private var micTestJob: Job? = null
    private var sampleCaptureJob: Job? = null

    private var voskModel: Model? = null
    private var voskRecognizer: Recognizer? = null
    private var wakeAudioRecord: AudioRecord? = null

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var focusRequest: AudioFocusRequest? = null

    private val _status = MutableStateFlow(WakeEngineStatus.OFF)
    val status: StateFlow<WakeEngineStatus> = _status.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _micTest = MutableStateFlow(MicTestSnapshot())
    val micTest: StateFlow<MicTestSnapshot> = _micTest.asStateFlow()

    var onWakePhraseDetected: ((String) -> Unit)? = null

    private var lastWakeTimestampMs = 0L

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (_status.value == WakeEngineStatus.LISTENING_FOR_WAKE) {
                    stopCaptureOnly()
                    _status.value = WakeEngineStatus.PAUSED_AUDIO_FOCUS_LOST
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (_status.value == WakeEngineStatus.PAUSED_AUDIO_FOCUS_LOST) {
                    startWakeListening()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startWakeListening(): Boolean {
        if (isRunning.get() && _status.value == WakeEngineStatus.LISTENING_FOR_WAKE) return true
        if (!modelManager.isModelReady()) {
            _status.value = WakeEngineStatus.ERROR
            _lastError.value = "Offline wake model not downloaded yet."
            return false
        }

        _status.value = WakeEngineStatus.STARTING
        _lastError.value = null
        stopMicTest()

        listenJob?.cancel()
        listenJob = scope.launch {
            try {
                if (voskModel == null) {
                    voskModel = Model(modelManager.modelDirectory.absolutePath)
                }
                val model = voskModel ?: throw IllegalStateException("Vosk model failed to load")
                val grammarJson = enrollmentStore.buildVoskGrammarJson()
                voskRecognizer?.close()
                voskRecognizer = Recognizer(model, SAMPLE_RATE.toFloat(), grammarJson)

                val minBuf = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                if (minBuf <= 0) {
                    throw IllegalStateException("Microphone hardware returned invalid buffer size ($minBuf)")
                }
                val record = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    max(minBuf * 2, READ_SAMPLES * 4)
                )
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    record.release()
                    throw IllegalStateException("AudioRecord failed to initialize for wake detection")
                }

                record.startRecording()
                if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    record.release()
                    throw IllegalStateException("Microphone is busy or blocked by Android")
                }

                wakeAudioRecord = record
                isRunning.set(true)
                _status.value = WakeEngineStatus.LISTENING_FOR_WAKE

                val shortBuf = ShortArray(READ_SAMPLES)
                var activeSpeechAccumMs = 0L

                while (isActive && isRunning.get()) {
                    val read = record.read(shortBuf, 0, shortBuf.size)
                    if (read <= 0) {
                        if (read < 0) {
                            _lastError.value = "Wake mic interrupted (code $read)"
                            break
                        }
                        continue
                    }

                    val rms = computeRms(shortBuf, read)
                    val enrollment = enrollmentStore.state.value
                    val energyOk = rms >= (enrollment.calibratedMinRms * 0.7f)
                    if (energyOk) {
                        activeSpeechAccumMs += (read * 1000L) / SAMPLE_RATE
                    } else {
                        activeSpeechAccumMs = max(0L, activeSpeechAccumMs - 60L)
                    }

                    val recognizer = voskRecognizer ?: break
                    val isFinal = recognizer.acceptWaveForm(shortBuf, read)
                    val detectedText = if (isFinal) {
                        extractField(recognizer.result, "text")
                    } else {
                        extractField(recognizer.partialResult, "partial")
                    }

                    if (isMatchingWakePhrase(detectedText) &&
                        (energyOk || activeSpeechAccumMs >= 160L)
                    ) {
                        val now = System.currentTimeMillis()
                        if (now - lastWakeTimestampMs > WAKE_COOLDOWN_MS) {
                            lastWakeTimestampMs = now
                            recognizer.reset()
                            activeSpeechAccumMs = 0L
                            Log.i(TAG, "Wake phrase matched offline: '$detectedText'")
                            onWakePhraseDetected?.invoke(detectedText)
                        }
                    }
                }
            } catch (se: SecurityException) {
                Log.e(TAG, "RECORD_AUDIO permission denied for wake detection", se)
                _status.value = WakeEngineStatus.ERROR
                _lastError.value = "Microphone permission is required for Wake Up Detection."
            } catch (t: Throwable) {
                Log.e(TAG, "Wake word engine failure", t)
                _status.value = WakeEngineStatus.ERROR
                _lastError.value = t.localizedMessage ?: "Wake detection engine failed."
            } finally {
                stopCaptureOnly()
            }
        }
        return true
    }

    fun pauseForActiveVoice() {
        if (_status.value == WakeEngineStatus.LISTENING_FOR_WAKE ||
            _status.value == WakeEngineStatus.STARTING
        ) {
            stopCaptureOnly()
            _status.value = WakeEngineStatus.PAUSED_FOR_ACTIVE_VOICE
        }
    }

    fun stopAll() {
        stopCaptureOnly()
        releaseVoskObjects()
        _status.value = WakeEngineStatus.OFF
    }

    private fun stopCaptureOnly() {
        isRunning.set(false)
        listenJob?.cancel()
        listenJob = null
        try {
            if (wakeAudioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                wakeAudioRecord?.stop()
            }
            wakeAudioRecord?.release()
        } catch (_: Exception) {
        }
        wakeAudioRecord = null
    }

    private fun releaseVoskObjects() {
        try {
            voskRecognizer?.close()
        } catch (_: Throwable) {
        }
        voskRecognizer = null
        try {
            voskModel?.close()
        } catch (_: Throwable) {
        }
        voskModel = null
    }

    fun requestActiveVoiceAudioFocus(): Boolean {
        val am = audioManager ?: return true
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener(focusChangeListener)
                .build()
            focusRequest = req
            am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
                focusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    fun abandonActiveVoiceAudioFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { am.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(focusChangeListener)
        }
    }

    /**
     * Captures a single 2.2-second enrollment sample for "Hey Mahi" and persists it via WakeEnrollmentStore.
     */
    @SuppressLint("MissingPermission")
    fun captureEnrollmentSample(onFinished: (Result<WakeEnrollmentSample>) -> Unit) {
        if (sampleCaptureJob?.isActive == true) return
        stopMicTest()
        val wasListening = _status.value == WakeEngineStatus.LISTENING_FOR_WAKE
        if (wasListening) stopCaptureOnly()

        enrollmentStore.setRecordingSample(true, "Listening now… Say \"Hey Mahi\"")
        sampleCaptureJob = scope.launch {
            var rec: AudioRecord? = null
            try {
                val minBuf = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                rec = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    max(minBuf * 2, READ_SAMPLES * 4)
                )
                if (rec.state != AudioRecord.STATE_INITIALIZED) {
                    throw IllegalStateException("Microphone could not initialize for enrollment.")
                }
                rec.startRecording()
                val pcmOut = ByteArrayOutputStream()
                val shortBuf = ShortArray(READ_SAMPLES)
                val endTime = System.currentTimeMillis() + 2100L
                while (isActive && System.currentTimeMillis() < endTime) {
                    val read = rec.read(shortBuf, 0, shortBuf.size)
                    if (read > 0) {
                        for (i in 0 until read) {
                            val s = shortBuf[i].toInt()
                            pcmOut.write(s and 0xFF)
                            pcmOut.write((s ushr 8) and 0xFF)
                        }
                    }
                }
                val result = enrollmentStore.addRecordedPcmSample(
                    pcm16Bytes = pcmOut.toByteArray(),
                    sampleRate = SAMPLE_RATE,
                    transcriptHint = "Hey Mahi"
                )
                onFinished(result)
            } catch (e: Exception) {
                val msg = e.localizedMessage ?: "Mic capture failed during enrollment."
                enrollmentStore.setRecordingSample(false, msg)
                onFinished(Result.failure(e))
            } finally {
                try {
                    if (rec?.recordingState == AudioRecord.RECORDSTATE_RECORDING) rec.stop()
                    rec?.release()
                } catch (_: Exception) {
                }
                if (wasListening) startWakeListening()
            }
        }
    }

    /**
     * Real 16kHz microphone test loop for Settings -> Voice & Mic Calibration.
     */
    @SuppressLint("MissingPermission")
    fun startMicTest() {
        if (_micTest.value.isTesting) return
        val wasWake = _status.value == WakeEngineStatus.LISTENING_FOR_WAKE
        if (wasWake) stopCaptureOnly()

        _micTest.value = MicTestSnapshot(isTesting = true, statusText = "Speak normally to verify microphone pickup…")
        micTestJob = scope.launch {
            var rec: AudioRecord? = null
            try {
                val minBuf = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                rec = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    max(minBuf * 2, READ_SAMPLES * 2)
                )
                if (rec.state != AudioRecord.STATE_INITIALIZED) {
                    rec.release()
                    rec = AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        max(minBuf * 2, READ_SAMPLES * 2)
                    )
                }
                if (rec.state != AudioRecord.STATE_INITIALIZED) {
                    throw IllegalStateException("Microphone hardware unavailable.")
                }
                rec.startRecording()
                val buf = ShortArray(640)
                while (isActive && _micTest.value.isTesting) {
                    val read = rec.read(buf, 0, buf.size)
                    if (read > 0) {
                        val rms = computeRms(buf, read)
                        val db = if (rms > 1e-5f) (20f * log10(rms)).coerceIn(-60f, 0f) else -60f
                        val active = rms >= 0.025f
                        _micTest.value = MicTestSnapshot(
                            isTesting = true,
                            rmsLevel = min(1f, rms * 8f),
                            dbLevel = db,
                            isVoiceActive = active,
                            statusText = if (active) "Voice detected cleanly!" else "Quiet room / listening…"
                        )
                    }
                }
            } catch (e: Exception) {
                _micTest.value = MicTestSnapshot(
                    isTesting = false,
                    statusText = "Mic test failed: ${e.localizedMessage ?: "Permission or hardware error"}"
                )
            } finally {
                try {
                    if (rec?.recordingState == AudioRecord.RECORDSTATE_RECORDING) rec.stop()
                    rec?.release()
                } catch (_: Exception) {
                }
            }
        }
    }

    fun stopMicTest() {
        micTestJob?.cancel()
        micTestJob = null
        if (_micTest.value.isTesting) {
            _micTest.value = MicTestSnapshot(isTesting = false, statusText = "Mic test stopped.")
        }
    }

    private fun computeRms(buf: ShortArray, count: Int): Float {
        if (count <= 0) return 0f
        var sumSq = 0.0
        for (i in 0 until count) {
            val v = buf[i].toDouble()
            sumSq += v * v
        }
        return (sqrt(sumSq / count) / 32767.0).toFloat()
    }

    internal fun isMatchingWakePhrase(transcript: String): Boolean {
        val clean = transcript.trim().lowercase()
        if (clean.isBlank() || clean == "[unk]") return false
        return clean.contains("hey mahi") ||
            clean.contains("hey mahee") ||
            clean.contains("hey maahi") ||
            clean == "mahi"
    }

    private fun extractField(jsonStr: String?, key: String): String {
        if (jsonStr.isNullOrBlank()) return ""
        return try {
            JSONObject(jsonStr).optString(key, "").trim()
        } catch (_: Exception) {
            ""
        }
    }
}
