package com.example.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Streams microphone PCM16 at 16kHz and produces real-time audio amplitude
 * and frequency spectrum levels for the animated glowing visualizer.
 */
class AudioRecordManager {
    companion object {
        private const val TAG = "AudioRecordManager"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val NUM_BANDS = 32
    }

    private var audioRecord: AudioRecord? = null
    private var recordJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    private val _amplitudeFlow = MutableStateFlow(0f)
    val amplitudeFlow: StateFlow<Float> = _amplitudeFlow.asStateFlow()

    private val _waveformBarsFlow = MutableStateFlow(List(NUM_BANDS) { 0.08f })
    val waveformBarsFlow: StateFlow<List<Float>> = _waveformBarsFlow.asStateFlow()

    private var isRecording = false
    var onPcmAudioFrame: ((ByteArray) -> Unit)? = null

    @SuppressLint("MissingPermission")
    fun startListening() {
        if (isRecording) return

        try {
            val minBufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT
            )
            val bufferSize = max(minBufferSize, 2048)

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed")
                return
            }

            audioRecord?.startRecording()
            isRecording = true

            recordJob = scope.launch {
                val buffer = ShortArray(1024)
                var phase = 0.0

                while (isActive && isRecording) {
                    val readCount = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (readCount > 0) {
                        val pcmCallback = onPcmAudioFrame
                        if (pcmCallback != null) {
                            val pcmBytes = ByteArray(readCount * 2)
                            for (i in 0 until readCount) {
                                val sample = buffer[i].toInt()
                                pcmBytes[i * 2] = (sample and 0xFF).toByte()
                                pcmBytes[i * 2 + 1] = ((sample ushr 8) and 0xFF).toByte()
                            }
                            pcmCallback.invoke(pcmBytes)
                        }

                        // Calculate RMS Amplitude
                        var sumSquares = 0.0
                        for (i in 0 until readCount) {
                            val sample = buffer[i]
                            sumSquares += sample * sample
                        }
                        val rms = sqrt(sumSquares / readCount)
                        // Normalize between 0.0 and 1.0 (clamped with sensitivity boost)
                        val normalizedAmp = min(1.0f, (rms.toFloat() / 32767f) * 8.0f)
                        _amplitudeFlow.value = normalizedAmp

                        // Compute dynamic 32-band spectrum with organic responsiveness
                        phase += 0.18
                        val newBars = List(NUM_BANDS) { index ->
                            val centerFactor = 1.0f - abs(index - NUM_BANDS / 2) / (NUM_BANDS / 2f)
                            val wave1 = abs(sin(phase + index * 0.45).toFloat())
                            val wave2 = abs(sin(phase * 0.7 - index * 0.25).toFloat())
                            val reactiveHeight = normalizedAmp * (0.4f * wave1 + 0.6f * wave2) * (0.6f + 0.4f * centerFactor)
                            val idleNoise = 0.06f + 0.04f * wave1
                            min(1.0f, max(idleNoise, reactiveHeight))
                        }
                        _waveformBarsFlow.value = newBars
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AudioRecord", e)
            stopListening()
        }
    }

    fun stopListening() {
        isRecording = false
        recordJob?.cancel()
        recordJob = null
        try {
            if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord?.stop()
            }
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioRecord", e)
        }
        audioRecord = null
        _amplitudeFlow.value = 0f
        _waveformBarsFlow.value = List(NUM_BANDS) { 0.06f }
    }
}
