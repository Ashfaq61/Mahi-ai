package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.camera.CameraLensFacing
import com.example.camera.CameraStudioState
import com.example.camera.MahiCameraController
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.CyberEmerald
import com.example.ui.theme.NeonMagenta
import com.example.ui.theme.NeonViolet
import java.util.Locale

@Composable
fun CameraStudioScreen(
    cameraController: MahiCameraController,
    cameraState: CameraStudioState,
    onSwitchLens: (CameraLensFacing) -> Unit,
    onCapturePhoto: () -> Unit,
    onCaptureSelfie: () -> Unit,
    onToggleVideoRecording: () -> Unit,
    onToggleFlash: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onSystemCameraFallback: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val colors = MaterialTheme.colorScheme

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasCameraPermission = results[Manifest.permission.CAMERA] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        hasAudioPermission = results[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    // Safely release camera resources when app pauses or screen is left
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                cameraController.releaseCamera()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            cameraController.releaseCamera()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("camera_studio_screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "MAHI CAMERA STUDIO",
                    color = CyberCyan,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp
                )
                Text(
                    text = "Voice Photo, Selfie & HD Video",
                    color = colors.onBackground,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (cameraState.lensFacing == CameraLensFacing.FRONT) NeonMagenta.copy(alpha = 0.2f)
                        else CyberCyan.copy(alpha = 0.16f)
                    )
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .testTag("camera_lens_badge")
            ) {
                Text(
                    text = if (cameraState.lensFacing == CameraLensFacing.FRONT) "FRONT" else "BACK",
                    color = if (cameraState.lensFacing == CameraLensFacing.FRONT) NeonMagenta else CyberCyan,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black
                )
            }
        }

        // CameraX Preview Box or Permission Gate
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color.Black)
                .border(
                    width = 2.dp,
                    color = if (cameraState.isRecordingVideo) Color(0xFFEF4444) else CyberCyan.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(20.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            if (hasCameraPermission) {
                val previewView = remember(context) { PreviewView(context) }
                AndroidView(
                    factory = { previewView },
                    update = { pv ->
                        val future = ProcessCameraProvider.getInstance(context)
                        future.addListener({
                            try {
                                val provider = future.get()
                                cameraController.bindToLifecycle(
                                    lifecycleOwner = lifecycleOwner,
                                    previewView = pv,
                                    cameraProvider = provider,
                                    hasAudioPermission = hasAudioPermission
                                )
                            } catch (_: Exception) {
                            }
                        }, ContextCompat.getMainExecutor(context))
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("camerax_preview_view")
                )

                // Visible Recording Indicator + Elapsed Timer
                if (cameraState.isRecordingVideo) {
                    val mins = cameraState.recordingDurationSeconds / 60
                    val secs = cameraState.recordingDurationSeconds % 60
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(12.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFFDC2626))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                            .testTag("camera_recording_indicator"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Color.White)
                        )
                        Text(
                            text = String.format(Locale.US, "REC %02d:%02d", mins, secs),
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                }

                // Zoom Badge
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black.copy(alpha = 0.65f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = String.format(Locale.US, "%.1fx", cameraState.zoomRatio),
                        color = CyberCyan,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // 3-2-1 Selfie Countdown Overlay
                if (cameraState.selfieCountdownSeconds > 0) {
                    Box(
                        modifier = Modifier
                            .size(92.dp)
                            .clip(CircleShape)
                            .background(NeonMagenta.copy(alpha = 0.86f))
                            .testTag("selfie_countdown_overlay"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = cameraState.selfieCountdownSeconds.toString(),
                            color = Color.White,
                            fontSize = 42.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                }
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CameraAlt,
                        contentDescription = "Camera Permission Needed",
                        tint = CyberCyan,
                        modifier = Modifier.size(44.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Camera & Mic permissions required for Camera Studio",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = {
                            permissionsLauncher.launch(
                                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = NeonMagenta),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("camera_grant_permission_button")
                    ) {
                        Text("Allow Camera & Mic", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Status / Error Banner
        Text(
            text = cameraState.errorMessage ?: cameraState.statusMessage,
            color = if (cameraState.errorMessage != null) Color(0xFFFCA5A5) else CyberEmerald,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.testTag("camera_status_text")
        )

        // Primary Camera Controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onCapturePhoto,
                colors = ButtonDefaults.buttonColors(containerColor = NeonMagenta),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .weight(1f)
                    .minimumInteractiveComponentSize()
                    .testTag("camera_capture_photo_button")
            ) {
                Icon(Icons.Default.PhotoCamera, contentDescription = "Take Photo", modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Photo", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }

            Button(
                onClick = onCaptureSelfie,
                colors = ButtonDefaults.buttonColors(containerColor = NeonViolet),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .weight(1f)
                    .minimumInteractiveComponentSize()
                    .testTag("camera_capture_selfie_button")
            ) {
                Icon(Icons.Default.CameraAlt, contentDescription = "Take Selfie", modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Selfie 3s", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }

            Button(
                onClick = onToggleVideoRecording,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (cameraState.isRecordingVideo) Color(0xFFDC2626) else CyberCyan
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .weight(1f)
                    .minimumInteractiveComponentSize()
                    .testTag("camera_video_record_button")
            ) {
                Icon(
                    imageVector = if (cameraState.isRecordingVideo) Icons.Default.Stop else Icons.Default.Videocam,
                    contentDescription = if (cameraState.isRecordingVideo) "Stop Video" else "Record Video",
                    tint = Color.Black,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (cameraState.isRecordingVideo) "Stop" else "Video",
                    color = Color.Black,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black
                )
            }
        }

        // Secondary Controls: Switch Lens, Flash, Zoom In/Out
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    val nextLens = if (cameraState.lensFacing == CameraLensFacing.BACK) {
                        CameraLensFacing.FRONT
                    } else {
                        CameraLensFacing.BACK
                    }
                    onSwitchLens(nextLens)
                },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("camera_switch_lens_button")
            ) {
                Icon(Icons.Default.Cameraswitch, contentDescription = "Switch Camera", modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Switch Lens", fontSize = 12.sp)
            }

            OutlinedButton(
                onClick = onToggleFlash,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("camera_toggle_flash_button")
            ) {
                Icon(
                    imageVector = if (cameraState.isFlashEnabled) Icons.Default.FlashOn else Icons.Default.FlashOff,
                    contentDescription = "Toggle Flash",
                    tint = if (cameraState.isFlashEnabled) CyberEmerald else colors.onSurface,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(if (cameraState.isFlashEnabled) "Flash ON" else "Flash OFF", fontSize = 12.sp)
            }

            OutlinedButton(
                onClick = onZoomIn,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("camera_zoom_in_button")
            ) {
                Icon(Icons.Default.ZoomIn, contentDescription = "Zoom In", modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Zoom +", fontSize = 12.sp)
            }

            OutlinedButton(
                onClick = onZoomOut,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("camera_zoom_out_button")
            ) {
                Icon(Icons.Default.ZoomOut, contentDescription = "Zoom Out", modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Zoom -", fontSize = 12.sp)
            }
        }

        // Last Captured Media Preview / Open / Share
        if (cameraState.lastCapturedUri != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.surface)
                    .border(1.dp, CyberEmerald.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
                    .padding(12.dp)
                    .testTag("camera_last_media_card"),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (cameraState.lastCapturedIsVideo) "Latest Video Saved" else "Latest Photo Saved",
                        color = CyberEmerald,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = cameraState.lastSavedDisplayLabel,
                        color = colors.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        onClick = {
                            context.startActivity(
                                cameraController.buildViewMediaIntent(
                                    cameraState.lastCapturedUri,
                                    cameraState.lastCapturedIsVideo
                                )
                            )
                        },
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Open Captured Media", modifier = Modifier.size(16.dp))
                    }
                    OutlinedButton(
                        onClick = {
                            context.startActivity(
                                cameraController.buildShareMediaIntent(
                                    cameraState.lastCapturedUri,
                                    cameraState.lastCapturedIsVideo
                                )
                            )
                        },
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "Share Captured Media", modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        // Native System Camera Intent Fallbacks
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(colors.surfaceVariant)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "System Camera Intent Fallback",
                color = colors.onSurface,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.surface)
                        .clickable { onSystemCameraFallback("image_capture") }
                        .padding(vertical = 10.dp)
                        .testTag("camera_fallback_photo_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Text("System Photo App", color = CyberCyan, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.surface)
                        .clickable { onSystemCameraFallback("video_capture") }
                        .padding(vertical = 10.dp)
                        .testTag("camera_fallback_video_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Text("System Video App", color = NeonMagenta, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
