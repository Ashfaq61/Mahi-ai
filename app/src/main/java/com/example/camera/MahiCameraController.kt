package com.example.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Locale

data class CameraUiState(
    val isBound: Boolean = false,
    val useFrontCamera: Boolean = false,
    val torchEnabled: Boolean = false,
    val isRecordingVideo: Boolean = false,
    val zoomRatio: Float = 1f,
    val lastCapturedUri: Uri? = null,
    val statusMessage: String = "Camera idle"
)

class MahiCameraController(private val context: Context) {
    private val _state = MutableStateFlow(CameraUiState())
    val state: StateFlow<CameraUiState> = _state.asStateFlow()

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null

    fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    fun bindToPreview(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView
    ) {
        if (!hasCameraPermission()) {
            _state.value = _state.value.copy(isBound = false, statusMessage = "Grant Camera permission to preview.")
            return
        }
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                val imgCap = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.HD))
                    .build()
                val vidCap = VideoCapture.withOutput(recorder)
                val selector = if (_state.value.useFrontCamera) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }
                provider.unbindAll()
                camera = provider.bindToLifecycle(lifecycleOwner, selector, preview, imgCap, vidCap)
                imageCapture = imgCap
                videoCapture = vidCap
                _state.value = _state.value.copy(
                    isBound = true,
                    statusMessage = "CameraX live (${if (_state.value.useFrontCamera) "Front" else "Back"})"
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isBound = false,
                    statusMessage = "Camera bind failed: ${e.localizedMessage}"
                )
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun toggleLens(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        _state.value = _state.value.copy(useFrontCamera = !_state.value.useFrontCamera, torchEnabled = false)
        bindToPreview(lifecycleOwner, previewView)
    }

    fun toggleTorch() {
        val next = !_state.value.torchEnabled
        camera?.cameraControl?.enableTorch(next)
        _state.value = _state.value.copy(torchEnabled = next)
    }

    fun setZoomRatio(ratio: Float) {
        val clamped = ratio.coerceIn(1f, 5f)
        camera?.cameraControl?.setZoomRatio(clamped)
        _state.value = _state.value.copy(zoomRatio = clamped)
    }

    fun capturePhoto(onResult: (Result<Uri>) -> Unit) {
        val cap = imageCapture ?: run {
            onResult(Result.failure(IllegalStateException("CameraX ImageCapture not ready.")))
            return
        }
        val name = "MAHI_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MahiAI")
            }
        }
        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            context.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values
        ).build()

        cap.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val uri = outputFileResults.savedUri
                    _state.value = _state.value.copy(
                        lastCapturedUri = uri,
                        statusMessage = "Photo saved: ${uri?.lastPathSegment ?: name}"
                    )
                    if (uri != null) onResult(Result.success(uri))
                }

                override fun onError(exception: ImageCaptureException) {
                    _state.value = _state.value.copy(statusMessage = "Capture failed: ${exception.localizedMessage}")
                    onResult(Result.failure(exception))
                }
            }
        )
    }

    @SuppressLint("MissingPermission")
    fun toggleVideoRecording() {
        val existing = activeRecording
        if (existing != null) {
            existing.stop()
            activeRecording = null
            _state.value = _state.value.copy(isRecordingVideo = false, statusMessage = "Video recording saved.")
            return
        }
        val vidCap = videoCapture ?: return
        val name = "MAHI_VID_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/MahiAI")
            }
        }
        val options = MediaStoreOutputOptions.Builder(
            context.contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(values).build()

        var pending = vidCap.output.prepareRecording(context, options)
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            pending = pending.withAudioEnabled()
        }
        activeRecording = pending.start(ContextCompat.getMainExecutor(context)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> _state.value = _state.value.copy(isRecordingVideo = true, statusMessage = "Recording video…")
                is VideoRecordEvent.Finalize -> {
                    activeRecording = null
                    val uri = event.outputResults.outputUri
                    _state.value = _state.value.copy(
                        isRecordingVideo = false,
                        lastCapturedUri = uri,
                        statusMessage = if (event.hasError()) "Video error: ${event.cause?.localizedMessage}" else "Saved video to Movies/MahiAI"
                    )
                }
            }
        }
    }

    fun loadJpegBytesFromUri(uri: Uri, maxDim: Int = 1024): ByteArray? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val raw = BitmapFactory.decodeStream(stream) ?: return null
            val scale = minOf(1f, maxDim.toFloat() / maxOf(raw.width, raw.height).coerceAtLeast(1))
            val bmp = if (scale < 1f) {
                Bitmap.createScaledBitmap(raw, (raw.width * scale).toInt().coerceAtLeast(1), (raw.height * scale).toInt().coerceAtLeast(1), true)
            } else {
                raw
            }
            ByteArrayOutputStream().use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 82, out)
                out.toByteArray()
            }
        }
    }.getOrNull()

    fun release() {
        activeRecording?.stop()
        activeRecording = null
        cameraProvider?.unbindAll()
    }
}
