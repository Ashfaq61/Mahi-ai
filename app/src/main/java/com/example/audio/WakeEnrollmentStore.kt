package com.example.audio

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.max
import kotlin.math.sqrt

data class WakeEnrollmentSample(
    val index: Int,
    val rmsEnergy: Float,
    val peakAmplitude: Float,
    val durationMs: Long,
    val transcriptHint: String,
    val recordedAtMs: Long
)

data class WakeEnrollmentState(
    val samples: List<WakeEnrollmentSample> = emptyList(),
    val requiredSamples: Int = 5,
    val calibratedMinRms: Float = 0.025f,
    val calibratedMinDurationMs: Long = 260L,
    val wakePhrase: String = "Hey Mahi",
    val isRecordingSample: Boolean = false,
    val lastFeedback: String = "Say \"Hey Mahi\" 5 times to calibrate your voice."
) {
    val completedCount: Int get() = samples.size
    val isComplete: Boolean get() = samples.size >= requiredSamples
}

/**
 * Persists the user's 5-sample "Hey Mahi" enrollment calibration in filesDir/wake_enrollment/.
 * Saves actual PCM audio snippets + measured RMS energy / duration bounds so wake detection
 * adapts to the user's vocal loudness and environment without faking enrollment.
 */
class WakeEnrollmentStore(context: Context) {
    companion object {
        const val REQUIRED_SAMPLES = 5
        private const val DIR_NAME = "wake_enrollment"
        private const val META_FILE = "enrollment_profile.json"
        val DEFAULT_GRAMMAR_PHRASES = listOf("hey mahi", "hey mahee", "hey maahi", "mahi", "[unk]")
    }

    private val enrollmentDir = File(context.applicationContext.filesDir, DIR_NAME).apply { mkdirs() }
    private val metaFile = File(enrollmentDir, META_FILE)

    private val _state = MutableStateFlow(loadFromDisk())
    val state: StateFlow<WakeEnrollmentState> = _state.asStateFlow()

    private fun loadFromDisk(): WakeEnrollmentState {
        if (!metaFile.exists()) return WakeEnrollmentState()
        return try {
            val root = JSONObject(metaFile.readText())
            val arr = root.optJSONArray("samples") ?: JSONArray()
            val list = buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    add(
                        WakeEnrollmentSample(
                            index = obj.optInt("index", i + 1),
                            rmsEnergy = obj.optDouble("rmsEnergy", 0.03).toFloat(),
                            peakAmplitude = obj.optDouble("peakAmplitude", 0.1).toFloat(),
                            durationMs = obj.optLong("durationMs", 400L),
                            transcriptHint = obj.optString("transcriptHint", "Hey Mahi"),
                            recordedAtMs = obj.optLong("recordedAtMs", 0L)
                        )
                    )
                }
            }
            val minRms = root.optDouble("calibratedMinRms", 0.025).toFloat().coerceIn(0.012f, 0.25f)
            val minDur = root.optLong("calibratedMinDurationMs", 260L).coerceIn(180L, 1200L)
            val feedback = if (list.size >= REQUIRED_SAMPLES) {
                "5/5 samples calibrated for \"Hey Mahi\"."
            } else {
                "${list.size}/$REQUIRED_SAMPLES samples recorded."
            }
            WakeEnrollmentState(
                samples = list,
                requiredSamples = REQUIRED_SAMPLES,
                calibratedMinRms = minRms,
                calibratedMinDurationMs = minDur,
                lastFeedback = feedback
            )
        } catch (_: Exception) {
            WakeEnrollmentState()
        }
    }

    private fun persist(state: WakeEnrollmentState) {
        val arr = JSONArray()
        state.samples.forEach { sample ->
            arr.put(
                JSONObject().apply {
                    put("index", sample.index)
                    put("rmsEnergy", sample.rmsEnergy.toDouble())
                    put("peakAmplitude", sample.peakAmplitude.toDouble())
                    put("durationMs", sample.durationMs)
                    put("transcriptHint", sample.transcriptHint)
                    put("recordedAtMs", sample.recordedAtMs)
                }
            )
        }
        val root = JSONObject().apply {
            put("wakePhrase", state.wakePhrase)
            put("calibratedMinRms", state.calibratedMinRms.toDouble())
            put("calibratedMinDurationMs", state.calibratedMinDurationMs)
            put("samples", arr)
        }
        metaFile.writeText(root.toString())
    }

    fun setRecordingSample(recording: Boolean, feedback: String? = null) {
        _state.value = _state.value.copy(
            isRecordingSample = recording,
            lastFeedback = feedback ?: _state.value.lastFeedback
        )
    }

    /**
     * Validates a captured PCM16 16kHz buffer and stores sample N (1..5).
     * Rejects silence or tiny noise pops (< 180ms or too quiet) so user retries cleanly.
     */
    fun addRecordedPcmSample(
        pcm16Bytes: ByteArray,
        sampleRate: Int = 16000,
        transcriptHint: String = "Hey Mahi"
    ): Result<WakeEnrollmentSample> {
        if (pcm16Bytes.size < 2000) {
            val msg = "Sample too short — hold and speak \"Hey Mahi\" clearly."
            _state.value = _state.value.copy(isRecordingSample = false, lastFeedback = msg)
            return Result.failure(IllegalArgumentException(msg))
        }

        val numSamples = pcm16Bytes.size / 2
        var sumSq = 0.0
        var peak = 0
        for (i in 0 until numSamples) {
            val lo = pcm16Bytes[i * 2].toInt() and 0xFF
            val hi = pcm16Bytes[i * 2 + 1].toInt()
            val s = (hi shl 8) or lo
            sumSq += s.toDouble() * s.toDouble()
            val absS = kotlin.math.abs(s)
            if (absS > peak) peak = absS
        }
        val rms = (sqrt(sumSq / numSamples) / 32767.0).toFloat()
        val peakNorm = (peak / 32767.0f).coerceIn(0f, 1f)
        val durationMs = ((numSamples * 1000L) / sampleRate).coerceAtLeast(1L)

        if (rms < 0.008f || peakNorm < 0.03f || durationMs < 220L) {
            val msg = "Audio too quiet or brief (RMS=${"%.3f".format(rms)}). Please retry \"Hey Mahi\"."
            _state.value = _state.value.copy(isRecordingSample = false, lastFeedback = msg)
            return Result.failure(IllegalArgumentException(msg))
        }

        val nextIdx = (_state.value.samples.size + 1).coerceAtMost(REQUIRED_SAMPLES)
        val sampleFile = File(enrollmentDir, "sample_$nextIdx.pcm")
        sampleFile.writeBytes(pcm16Bytes)

        val newSample = WakeEnrollmentSample(
            index = nextIdx,
            rmsEnergy = rms,
            peakAmplitude = peakNorm,
            durationMs = durationMs,
            transcriptHint = transcriptHint.ifBlank { "Hey Mahi" },
            recordedAtMs = System.currentTimeMillis()
        )

        val updatedList = (_state.value.samples.filterNot { it.index == nextIdx } + newSample)
            .sortedBy { it.index }
            .take(REQUIRED_SAMPLES)

        val avgRms = updatedList.map { it.rmsEnergy }.average().toFloat()
        val minDur = updatedList.minOf { it.durationMs }
        val calibratedRms = max(0.015f, avgRms * 0.42f).coerceAtMost(0.12f)
        val calibratedDur = (minDur * 0.55f).toLong().coerceIn(200L, 600L)

        val feedback = if (updatedList.size >= REQUIRED_SAMPLES) {
            "5/5 \"Hey Mahi\" samples saved! Voice calibration ready."
        } else {
            "Saved sample ${updatedList.size}/$REQUIRED_SAMPLES. Say \"Hey Mahi\" again."
        }

        val updatedState = _state.value.copy(
            samples = updatedList,
            calibratedMinRms = calibratedRms,
            calibratedMinDurationMs = calibratedDur,
            isRecordingSample = false,
            lastFeedback = feedback
        )
        persist(updatedState)
        _state.value = updatedState
        return Result.success(newSample)
    }

    fun resetEnrollment() {
        enrollmentDir.listFiles()?.forEach { it.delete() }
        _state.value = WakeEnrollmentState(
            lastFeedback = "Enrollment cleared. Record 5 \"Hey Mahi\" samples to re-calibrate."
        )
    }

    fun buildVoskGrammarJson(): String {
        val phrases = JSONArray()
        DEFAULT_GRAMMAR_PHRASES.forEach { phrases.put(it) }
        return phrases.toString()
    }
}
