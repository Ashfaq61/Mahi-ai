package com.example.ui.screens

import android.Manifest
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.camera.CameraUiState
import com.example.camera.MahiCameraController
import com.example.preferences.AppLanguage
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.CyberEmerald
import com.example.ui.theme.NeonMagenta
import com.example.ui.theme.NeonViolet
import com.example.vision.LiveVisionSnapshot
import com.example.vision.NeuralLensAnalyzer

@Composable
fun NeuralLensScreen(
    analyzer: NeuralLensAnalyzer,
    liveSnapshot: LiveVisionSnapshot,
    selectedLanguage: AppLanguage,
    aiVisionAnswer: String,
    isAnalyzingAi: Boolean,
    visionError: String?,
    onLanguageSelected: (AppLanguage) -> Unit,
    onAskAiVision: (String) -> Unit,
    onAnalyzePhotoUri: (Uri) -> Unit,
    onSpeakAnswer: (String) -> Unit,
    onOpenApiSettings: () -> Unit,
    cameraController: MahiCameraController? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val colors = MaterialTheme.colorScheme
    var selectedSubmode by remember { mutableIntStateOf(0) }
    var promptInput by remember { mutableStateOf("Describe what is in this view and read any visible text.") }
    val camState by (cameraController?.state?.collectAsState() ?: remember { mutableStateOf(CameraUiState()) })
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            previewViewRef?.let { pv -> cameraController?.bindToPreview(lifecycleOwner, pv) }
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) onAnalyzePhotoUri(uri)
    }

    DisposableEffect(Unit) {
        onDispose { cameraController?.release() }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("neural_lens_screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("NEURAL LENS • CAMERAX & MULTIMODAL VISION", color = CyberCyan, fontSize = 11.sp, fontWeight = FontWeight.Black)
        Text("Visual Understanding & Gallery Inspector", color = colors.onBackground, fontSize = 17.sp, fontWeight = FontWeight.Bold)

        // Submode tabs (0=Camera, 1=Screen & Notifications, 2=Gallery)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("CameraX Live", "Screen & OCR", "Gallery Photo").forEachIndexed { idx, label ->
                val active = selectedSubmode == idx
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (active) NeonViolet else colors.surfaceVariant)
                        .border(1.dp, if (active) NeonViolet else colors.outline, RoundedCornerShape(12.dp))
                        .clickable { selectedSubmode = idx }
                        .padding(horizontal = 14.dp, vertical = 9.dp)
                        .testTag("lens_submode_$idx")
                ) {
                    Text(label, color = if (active) Color.White else colors.onSurface, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        if (selectedSubmode == 0) {
            if (cameraController != null && cameraController.hasCameraPermission()) {
                AndroidView(
                    factory = { ctx ->
                        PreviewView(ctx).also { pv ->
                            previewViewRef = pv
                            cameraController.bindToPreview(lifecycleOwner, pv)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(230.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .border(1.dp, CyberCyan.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            cameraController.capturePhoto { res ->
                                res.onSuccess { uri -> onAnalyzePhotoUri(uri) }
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Capture & Inspect", fontSize = 11.sp) }
                    OutlinedButton(
                        onClick = { previewViewRef?.let { cameraController.toggleLens(lifecycleOwner, it) } },
                        modifier = Modifier.weight(1f)
                    ) { Text("Flip Lens", fontSize = 11.sp) }
                    OutlinedButton(
                        onClick = { cameraController.toggleTorch() },
                        modifier = Modifier.weight(1f)
                    ) { Text(if (camState.torchEnabled) "Flash ON" else "Flash OFF", fontSize = 11.sp) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { cameraController.toggleVideoRecording() }, modifier = Modifier.weight(1f)) {
                        Text(if (camState.isRecordingVideo) "Stop Video" else "Record Video", fontSize = 11.sp)
                    }
                    OutlinedButton(
                        onClick = { cameraController.setZoomRatio(if (camState.zoomRatio < 2f) 2f else 1f) },
                        modifier = Modifier.weight(1f)
                    ) { Text("Zoom ${camState.zoomRatio}x", fontSize = 11.sp) }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.outline, RoundedCornerShape(14.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("CameraX Preview requires Camera permission.", color = colors.onSurface, fontSize = 12.sp)
                    Button(onClick = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text("Grant Camera Permission")
                    }
                }
            }
        }

        if (selectedSubmode == 1) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.surface)
                    .border(1.dp, CyberCyan.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Screen Reader & Multimodal OCR", color = CyberCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Button(
                    onClick = { onAskAiVision("Read and summarize everything visible on the active screen.") },
                    modifier = Modifier.fillMaxWidth().testTag("lens_read_screen_button")
                ) {
                    Text("Read & Explain Current Screen")
                }
            }
        }

        if (selectedSubmode == 2) {
            Button(
                onClick = {
                    photoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                colors = ButtonDefaults.buttonColors(containerColor = NeonMagenta),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Pick Photo via Zero-Permission Photo Picker")
            }
        }

        // Frame Stats & AI Question Box
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(colors.surface)
                .border(1.dp, colors.outline, RoundedCornerShape(14.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Local Frame Signal: ${liveSnapshot.sceneHint}", color = CyberCyan, fontSize = 11.sp)
            OutlinedTextField(
                value = promptInput,
                onValueChange = { promptInput = it },
                label = { Text("Ask Vision Question") },
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { onAskAiVision(promptInput) },
                    enabled = !isAnalyzingAi,
                    modifier = Modifier.weight(1f)
                ) {
                    if (isAnalyzingAi) CircularProgressIndicator(modifier = Modifier.height(16.dp).width(16.dp), color = Color.White)
                    else Text("Analyze with AI Vision", fontSize = 11.sp)
                }
                if (aiVisionAnswer.isNotBlank()) {
                    OutlinedButton(onClick = { onSpeakAnswer(aiVisionAnswer) }) {
                        Text("Speak", fontSize = 11.sp)
                    }
                }
            }
            if (!visionError.isNullOrBlank()) {
                Text(visionError, color = Color(0xFFEF4444), fontSize = 11.sp)
                Text("Open API Providers", color = CyberCyan, fontSize = 11.sp, modifier = Modifier.clickable { onOpenApiSettings() })
            }
            if (aiVisionAnswer.isNotBlank()) {
                Text(aiVisionAnswer, color = CyberEmerald, fontSize = 12.sp)
            }
        }
    }
}
