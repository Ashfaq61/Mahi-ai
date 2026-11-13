package com.example.camera

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class CameraLensFacing {
    BACK,
    FRONT
}

enum class PendingCameraAction {
    NONE,
    CAPTURE_PHOTO,
    CAPTURE_SELFIE_COUNTDOWN,
    START_VIDEO
}

data class CameraStudioState(
    val lensFacing: CameraLensFacing = CameraLensFacing.BACK,
    val isPreviewRequested: Boolean = false,
    val isCameraBound: Boolean = false,
    val isBackCameraAvailable: Boolean = true,
    val isFrontCameraAvailable: Boolean = true,
    val hasFlashUnit: Boolean = false,
    val isFlashEnabled: Boolean = false,
    val zoomRatio: Float = 1.0f,
    val minZoomRatio: Float = 1.0f,
    val maxZoomRatio: Float = 4.0f,
    val selfieCountdownSeconds: Int = 0,
    val isCapturingPhoto: Boolean = false,
    val isRecordingVideo: Boolean = false,
    val recordingDurationSeconds: Long = 0L,
    val lastCapturedUri: Uri? = null,
    val lastCapturedIsVideo: Boolean = false,
    val lastSavedDisplayLabel: String = "",
    val pendingAction: PendingCameraAction = PendingCameraAction.NONE,
    val statusMessage: String = "Camera ready for photo, selfie, or video",
    val errorMessage: String? = null
)

class MahiCameraController(private val context: Context) {
    companion object {
        private const val TAG = "MahiCameraController"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(CameraStudioState())
    val state: StateFlow<CameraStudioState> = _state.asStateFlow()

    private var boundProvider: ProcessCameraProvider? = null
    private var boundCamera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var selfieCountdownJob: Job? = null
    private var recordingTickerJob: Job? = null

    fun prepareOpenCamera(lens: CameraLensFacing? = null): String {
        val targetLens = lens ?: _state.value.lensFacing
        _state.value = _state.value.copy(
            lensFacing = targetLens,
            isPreviewRequested = true,
            errorMessage = null,
            statusMessage = "Opening ${targetLens.name.lowercase()} camera..."
        )
        return "Opening ${targetLens.name.lowercase()} camera preview"
    }

    fun requestSwitchCamera(lens: CameraLensFacing): String {
        if (lens == CameraLensFacing.FRONT && !_state.value.isFrontCameraAvailable) {
            val msg = "Front camera is not available on this device."
            _state.value = _state.value.copy(errorMessage = msg)
            return msg
        }
        if (lens == CameraLensFacing.BACK && !_state.value.isBackCameraAvailable) {
            val msg = "Back camera is not available on this device."
            _state.value = _state.value.copy(errorMessage = msg)
            return msg
        }
        _state.value = _state.value.copy(
            lensFacing = lens,
            isPreviewRequested = true,
            isFlashEnabled = false,
            errorMessage = null,
            statusMessage = "Switched to ${lens.name.lowercase()} camera"
        )
        return "Switched to ${lens.name.lowercase()} camera"
    }

    fun requestPhotoOrSelfie(isSelfie: Boolean, countdownSeconds: Int = 3): String {
        val desiredLens = if (isSelfie) CameraLensFacing.FRONT else _state.value.lensFacing
        if (desiredLens == CameraLensFacing.FRONT && !_state.value.isFrontCameraAvailable) {
            val msg = "Cannot take selfie: front camera unavailable."
            _state.value = _state.value.copy(errorMessage = msg)
            return msg
        }

        val currentCapture = imageCapture
        if (_state.value.isCameraBound && currentCapture != null && _state.value.lensFacing == desiredLens) {
            return if (isSelfie && countdownSeconds > 0) {
                startSelfieCountdown(countdownSeconds)
                "Selfie mode active — taking photo in $countdownSeconds seconds"
            } else {
                capturePhotoNow()
                "Capturing photo..."
            }
        }

        // Queue execution right after CameraX finishes binding the preview & requested lens
        _state.value = _state.value.copy(
            lensFacing = desiredLens,
            isPreviewRequested = true,
            pendingAction = if (isSelfie && countdownSeconds > 0) {
                PendingCameraAction.CAPTURE_SELFIE_COUNTDOWN
            } else {
                PendingCameraAction.CAPTURE_PHOTO
            },
            errorMessage = null,
            statusMessage = if (isSelfie) "Opening front camera for selfie..." else "Preparing camera for photo..."
        )
        return _state.value.statusMessage
    }

    fun startSelfieCountdown(seconds: Int = 3) {
        selfieCountdownJob?.cancel()
        selfieCountdownJob = scope.launch {
            for (sec in seconds downTo 1) {
                _state.value = _state.value.copy(
                    selfieCountdownSeconds = sec,
                    statusMessage = "Selfie in $sec...",
                    errorMessage = null
                )
                delay(1000L)
            }
            _state.value = _state.value.copy(selfieCountdownSeconds = 0)
            capturePhotoNow()
        }
    }

    fun capturePhotoNow() {
        val capture = imageCapture
        if (capture == null || !_state.value.isCameraBound) {
            val msg = "Camera preview is not active yet. Open Camera first or use System Camera fallback."
            _state.value = _state.value.copy(errorMessage = msg)
            return
        }
        if (_state.value.isCapturingPhoto) return

        val name = "Mahi_IMG_${timestampTag()}"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Mahi AI")
            }
        }
        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            context.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()

        capture.flashMode = if (_state.value.isFlashEnabled && _state.value.hasFlashUnit) {
            ImageCapture.FLASH_MODE_ON
        } else {
            ImageCapture.FLASH_MODE_OFF
        }

        _state.value = _state.value.copy(
            isCapturingPhoto = true,
            errorMessage = null,
            statusMessage = "Capturing photo..."
        )

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val savedUri = outputFileResults.savedUri
                    _state.value = _state.value.copy(
                        isCapturingPhoto = false,
                        lastCapturedUri = savedUri,
                        lastCapturedIsVideo = false,
                        lastSavedDisplayLabel = "$name.jpg saved to Gallery",
                        statusMessage = "Photo saved: $name.jpg",
                        errorMessage = null
                    )
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed", exception)
                    _state.value = _state.value.copy(
                        isCapturingPhoto = false,
                        errorMessage = "Photo capture failed: ${exception.localizedMessage ?: "Camera error"}"
                    )
                }
            }
        )
    }

    fun requestStartVideoRecording(hasAudioPermission: Boolean): String {
        val vc = videoCapture
        if (!_state.value.isCameraBound || vc == null) {
            _state.value = _state.value.copy(
                isPreviewRequested = true,
                pendingAction = PendingCameraAction.START_VIDEO,
                errorMessage = null,
                statusMessage = "Opening camera to start video recording..."
            )
            return _state.value.statusMessage
        }
        return startVideoRecordingNow(hasAudioPermission)
    }

    @SuppressLint("MissingPermission")
    fun startVideoRecordingNow(hasAudioPermission: Boolean): String {
        val vc = videoCapture ?: return "Video recorder unavailable on this camera."
        if (activeRecording != null || _state.value.isRecordingVideo) {
            return "Video recording is already in progress."
        }

        val name = "Mahi_VID_${timestampTag()}"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Mahi AI")
            }
        }
        val mediaStoreOutput = MediaStoreOutputOptions.Builder(
            context.contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(contentValues).build()

        return try {
            val pending = vc.output
                .prepareRecording(context, mediaStoreOutput)
                .apply {
                    if (hasAudioPermission) {
                        withAudioEnabled()
                    }
                }

            activeRecording = pending.start(ContextCompat.getMainExecutor(context)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        startRecordingTicker()
                        _state.value = _state.value.copy(
                            isRecordingVideo = true,
                            recordingDurationSeconds = 0L,
                            errorMessage = null,
                            statusMessage = if (hasAudioPermission) {
                                "Recording video with audio..."
                            } else {
                                "Recording video (mic muted: audio permission not granted)"
                            }
                        )
                    }
                    is VideoRecordEvent.Status -> {
                        val secs = event.recordingStats.recordedDurationNanos / 1_000_000_000L
                        _state.value = _state.value.copy(recordingDurationSeconds = secs)
                    }
                    is VideoRecordEvent.Finalize -> {
                        stopRecordingTicker()
                        activeRecording = null
                        if (!event.hasError()) {
                            val savedUri = event.outputResults.outputUri
                            _state.value = _state.value.copy(
                                isRecordingVideo = false,
                                recordingDurationSeconds = 0L,
                                lastCapturedUri = savedUri,
                                lastCapturedIsVideo = true,
                                lastSavedDisplayLabel = "$name.mp4 saved to Movies",
                                statusMessage = "Video saved: $name.mp4",
                                errorMessage = null
                            )
                        } else {
                            _state.value = _state.value.copy(
                                isRecordingVideo = false,
                                recordingDurationSeconds = 0L,
                                errorMessage = "Video recording error (${event.error}): ${event.cause?.localizedMessage ?: "Failed to save video"}"
                            )
                        }
                    }
                }
            }
            "Video recording started"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start video recording", e)
            val msg = "Unable to start video recording: ${e.localizedMessage ?: "Hardware error"}"
            _state.value = _state.value.copy(isRecordingVideo = false, errorMessage = msg)
            msg
        }
    }

    fun stopVideoRecording(): String {
        val recording = activeRecording
        if (recording == null) {
            _state.value = _state.value.copy(isRecordingVideo = false, recordingDurationSeconds = 0L)
            return "No active video recording running."
        }
        return try {
            recording.stop()
            stopRecordingTicker()
            _state.value = _state.value.copy(
                isRecordingVideo = false,
                statusMessage = "Stopping & saving video..."
            )
            "Stopped video recording and saving to gallery"
        } catch (e: Exception) {
            val msg = "Error stopping video recording: ${e.localizedMessage}"
            _state.value = _state.value.copy(isRecordingVideo = false, errorMessage = msg)
            msg
        }
    }

    fun setFlashEnabled(enabled: Boolean): String {
        if (_state.value.isCameraBound && !_state.value.hasFlashUnit) {
            val msg = if (_state.value.lensFacing == CameraLensFacing.FRONT) {
                "Front camera does not have a hardware flash unit."
            } else {
                "Camera flash unit is not available."
            }
            _state.value = _state.value.copy(isFlashEnabled = false, statusMessage = msg)
            return msg
        }
        boundCamera?.cameraControl?.enableTorch(enabled)
        imageCapture?.flashMode = if (enabled) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
        _state.value = _state.value.copy(
            isFlashEnabled = enabled,
            errorMessage = null,
            statusMessage = if (enabled) "Camera flash turned ON" else "Camera flash turned OFF"
        )
        return _state.value.statusMessage
    }

    fun adjustZoomBy(delta: Float): String {
        val target = _state.value.zoomRatio + delta
        return setZoomRatio(target)
    }

    fun setZoomRatio(level: Float): String {
        val clamped = level.coerceIn(_state.value.minZoomRatio, _state.value.maxZoomRatio)
        boundCamera?.cameraControl?.setZoomRatio(clamped)
        _state.value = _state.value.copy(
            zoomRatio = clamped,
            statusMessage = "Zoom set to ${String.format(Locale.US, "%.1fx", clamped)}"
        )
        return _state.value.statusMessage
    }

    fun bindToLifecycle(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        cameraProvider: ProcessCameraProvider,
        hasAudioPermission: Boolean
    ) {
        boundProvider = cameraProvider
        val hasBack = runCatching { cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) }.getOrDefault(false)
        val hasFront = runCatching { cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) }.getOrDefault(false)

        var activeLens = _state.value.lensFacing
        if (activeLens == CameraLensFacing.FRONT && !hasFront && hasBack) {
            activeLens = CameraLensFacing.BACK
            _state.value = _state.value.copy(errorMessage = "Front camera unavailable; switched to back camera.")
        } else if (activeLens == CameraLensFacing.BACK && !hasBack && hasFront) {
            activeLens = CameraLensFacing.FRONT
            _state.value = _state.value.copy(errorMessage = "Back camera unavailable; switched to front camera.")
        } else if (!hasBack && !hasFront) {
            _state.value = _state.value.copy(
                isCameraBound = false,
                isBackCameraAvailable = false,
                isFrontCameraAvailable = false,
                errorMessage = "No physical camera detected on this device. Use System Camera fallback if needed."
            )
            return
        }

        val selector = if (activeLens == CameraLensFacing.FRONT) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

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

        try {
            cameraProvider.unbindAll()
            val camera = try {
                cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, imgCap, vidCap)
            } catch (videoBindErr: Exception) {
                Log.w(TAG, "VideoCapture binding unsupported alongside ImageCapture; binding photo-only preview", videoBindErr)
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, imgCap)
            }

            boundCamera = camera
            imageCapture = imgCap
            videoCapture = vidCap

            val zoomState = camera.cameraInfo.zoomState.value
            val minZ = zoomState?.minZoomRatio ?: 1.0f
            val maxZ = (zoomState?.maxZoomRatio ?: 4.0f).coerceAtLeast(minZ)
            val hasFlash = camera.cameraInfo.hasFlashUnit()

            val pending = _state.value.pendingAction
            _state.value = _state.value.copy(
                lensFacing = activeLens,
                isCameraBound = true,
                isBackCameraAvailable = hasBack,
                isFrontCameraAvailable = hasFront,
                hasFlashUnit = hasFlash,
                isFlashEnabled = if (hasFlash) _state.value.isFlashEnabled else false,
                minZoomRatio = minZ,
                maxZoomRatio = maxZ,
                zoomRatio = _state.value.zoomRatio.coerceIn(minZ, maxZ),
                pendingAction = PendingCameraAction.NONE,
                errorMessage = null,
                statusMessage = "${activeLens.name.lowercase().replaceFirstChar { it.uppercase() }} camera active"
            )

            if (_state.value.isFlashEnabled && hasFlash) {
                camera.cameraControl.enableTorch(true)
            }

            when (pending) {
                PendingCameraAction.CAPTURE_PHOTO -> capturePhotoNow()
                PendingCameraAction.CAPTURE_SELFIE_COUNTDOWN -> startSelfieCountdown(3)
                PendingCameraAction.START_VIDEO -> startVideoRecordingNow(hasAudioPermission)
                PendingCameraAction.NONE -> Unit
            }
        } catch (e: Exception) {
            Log.e(TAG, "CameraX bindToLifecycle failed", e)
            _state.value = _state.value.copy(
                isCameraBound = false,
                pendingAction = PendingCameraAction.NONE,
                errorMessage = "Camera initialization failed: ${e.localizedMessage ?: "Use System Camera fallback."}"
            )
        }
    }

    fun releaseCamera(providerOverride: ProcessCameraProvider? = null) {
        selfieCountdownJob?.cancel()
        selfieCountdownJob = null
        if (_state.value.isRecordingVideo || activeRecording != null) {
            stopVideoRecording()
        }
        stopRecordingTicker()
        runCatching { (providerOverride ?: boundProvider)?.unbindAll() }
        boundCamera = null
        imageCapture = null
        videoCapture = null
        _state.value = _state.value.copy(
            isCameraBound = false,
            isFlashEnabled = false,
            selfieCountdownSeconds = 0
        )
    }

    fun buildCameraFallbackIntent(actionMode: String): Intent? {
        val action = when (actionMode.lowercase().trim()) {
            "image_capture", "photo" -> MediaStore.ACTION_IMAGE_CAPTURE
            "video_capture", "video" -> MediaStore.ACTION_VIDEO_CAPTURE
            "video_camera" -> MediaStore.INTENT_ACTION_VIDEO_CAMERA
            else -> MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA
        }
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (intent.resolveActivity(context.packageManager) != null) intent else null
    }

    fun launchSystemCameraFallback(actionMode: String): Boolean {
        val intent = buildCameraFallbackIntent(actionMode)
        if (intent == null) {
            _state.value = _state.value.copy(
                errorMessage = "No system camera app found on this device for $actionMode."
            )
            return false
        }
        return try {
            context.startActivity(intent)
            _state.value = _state.value.copy(
                statusMessage = "Opened system camera ($actionMode)",
                errorMessage = null
            )
            true
        } catch (e: Exception) {
            _state.value = _state.value.copy(
                errorMessage = "Unable to open system camera: ${e.localizedMessage}"
            )
            false
        }
    }

    fun buildShareMediaIntent(uri: Uri, isVideo: Boolean): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = if (isVideo) "video/*" else "image/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, if (isVideo) "Share Video" else "Share Photo")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun buildViewMediaIntent(uri: Uri, isVideo: Boolean): Intent {
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, if (isVideo) "video/*" else "image/*")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun startRecordingTicker() {
        recordingTickerJob?.cancel()
        recordingTickerJob = scope.launch {
            while (_state.value.isRecordingVideo) {
                delay(1000L)
                if (_state.value.isRecordingVideo) {
                    _state.value = _state.value.copy(
                        recordingDurationSeconds = _state.value.recordingDurationSeconds + 1L
                    )
                }
            }
        }
    }

    private fun stopRecordingTicker() {
        recordingTickerJob?.cancel()
        recordingTickerJob = null
    }

    private fun timestampTag(): String {
        return SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    }
}
