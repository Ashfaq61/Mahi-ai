package com.example.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

class AudioRecordManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var record: AudioRecord? = null

    private val _micLevel = MutableStateFlow(0f)
    val micLevel: StateFlow<Float> = _micLevel.asStateFlow()

    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()

    fun startMonitoring(onLevel: (Float) -> Unit = {}): Boolean {
        stopMonitoring()
        return try {
            val minBuf = AudioRecord.getMinBufferSize(
                16_000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(2048)
            val rec = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                16_000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                return false
            }
            record = rec
            rec.startRecording()
            _isCapturing.value = true
            job = scope.launch {
                val buf = ShortArray(1024)
                while (isActive && _isCapturing.value) {
                    val count = rec.read(buf, 0, buf.size)
                    if (count > 0) {
                        var sum = 0L
                        for (i in 0 until count) sum += abs(buf[i].toInt())
                        val level = (sum.toFloat() / count / 16_000f).coerceIn(0.05f, 1f)
                        _micLevel.value = level
                        onLevel(level)
                    }
                }
            }
            true
        } catch (_: SecurityException) {
            _isCapturing.value = false
            false
        } catch (_: Exception) {
            _isCapturing.value = false
            false
        }
    }

    fun stopMonitoring() {
        _isCapturing.value = false
        job?.cancel()
        job = null
        runCatching {
            record?.stop()
            record?.release()
        }
        record = null
        _micLevel.value = 0f
    }
}
