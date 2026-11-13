package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.preferences.AppLanguage
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.CyberEmerald
import com.example.ui.theme.NeonMagenta
import com.example.ui.theme.NeonViolet
import com.example.vision.LiveVisionSnapshot
import com.example.vision.NeuralLensAnalyzer
import java.util.concurrent.Executors

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
    onAnalyzePhotoUri: (android.net.Uri) -> Unit,
    onSpeakAnswer: (String) -> Unit,
    onOpenApiSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            onAnalyzePhotoUri(uri)
        }
    }

    var questionText by remember { mutableStateOf("") }
    val colors = MaterialTheme.colorScheme

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("neural_lens_screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header & Language Switcher
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "NEURAL LENS",
                    color = CyberCyan,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp
                )
                Text(
                    text = "Live AI Camera Vision & OCR",
                    color = colors.onBackground,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                AppLanguage.entries.forEach { lang ->
                    val selected = lang == selectedLanguage
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) NeonMagenta else colors.surfaceVariant)
                            .clickable { onLanguageSelected(lang) }
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                            .testTag("lens_lang_${lang.id}")
                    ) {
                        Text(
                            text = lang.shortBadge,
                            color = if (selected) Color.White else colors.onSurfaceVariant,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Camera Feed Card or Permission / Photo Fallback
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(250.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(colors.surface)
                .border(1.5.dp, CyberCyan.copy(alpha = 0.55f), RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (hasCameraPermission) {
                val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
                DisposableEffect(Unit) {
                    onDispose { cameraExecutor.shutdown() }
                }

                AndroidView(
                    factory = { ctx ->
                        val previewView = PreviewView(ctx)
                        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                        cameraProviderFuture.addListener({
                            try {
                                val cameraProvider = cameraProviderFuture.get()
                                val preview = Preview.Builder().build().also {
                                    it.surfaceProvider = previewView.surfaceProvider
                                }
                                val imageAnalysis = ImageAnalysis.Builder()
                                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                    .build()
                                    .also {
                                        it.setAnalyzer(cameraExecutor, analyzer.createCameraXAnalyzer())
                                    }
                                cameraProvider.unbindAll()
                                cameraProvider.bindToLifecycle(
                                    lifecycleOwner,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview,
                                    imageAnalysis
                                )
                            } catch (_: Exception) {
                            }
                        }, ContextCompat.getMainExecutor(ctx))
                        previewView
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Live Detection HUD Badges
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.58f))
                        .padding(10.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (liveSnapshot.labels.isEmpty()) {
                            Text(
                                text = "Scanning surroundings...",
                                color = CyberCyan,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        } else {
                            liveSnapshot.labels.forEach { item ->
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(NeonViolet.copy(alpha = 0.85f))
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                ) {
                                    Text(
                                        text = "${item.label} ${item.confidencePercent}%",
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    if (liveSnapshot.ocrText.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "OCR: ${liveSnapshot.ocrText}",
                            color = CyberEmerald,
                            fontSize = 11.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
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
                        contentDescription = "Camera Permission",
                        tint = CyberCyan,
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Enable Camera for Real-Time Neural Lens",
                        color = colors.onSurface,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
                        colors = ButtonDefaults.buttonColors(containerColor = NeonMagenta),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("lens_grant_camera_button")
                    ) {
                        Text("Allow Camera")
                    }
                }
            }
        }

        // Action Controls: Scan Live Frame or Pick Photo
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    onAskAiVision(
                        questionText.ifBlank {
                            "Identify objects, products, devices, and readable text in front of the camera and explain clearly."
                        }
                    )
                },
                enabled = !isAnalyzingAi,
                colors = ButtonDefaults.buttonColors(containerColor = NeonMagenta),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .weight(1f)
                    .minimumInteractiveComponentSize()
                    .testTag("lens_analyze_scene_button")
            ) {
                if (isAnalyzingAi) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.CenterFocusStrong,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text("Explain Scene", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }

            OutlinedButton(
                onClick = {
                    photoPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("lens_pick_photo_button")
            ) {
                Icon(
                    imageVector = Icons.Default.PhotoLibrary,
                    contentDescription = "Pick Photo",
                    tint = CyberCyan,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Photo", fontSize = 13.sp)
            }
        }

        // Quick Question Chips
        val quickVisionPrompts = listOf(
            "What product or device is this?",
            "Read & translate all visible text",
            "How do I use or fix what you see?"
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            quickVisionPrompts.forEach { prompt ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.surfaceVariant)
                        .border(1.dp, colors.outline, RoundedCornerShape(12.dp))
                        .clickable {
                            questionText = prompt
                            onAskAiVision(prompt)
                        }
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Text(text = prompt, color = colors.onSurface, fontSize = 11.sp)
                }
            }
        }

        // Question Input Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = questionText,
                onValueChange = { questionText = it },
                placeholder = { Text("Ask Mahi about what she sees...", fontSize = 13.sp) },
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .testTag("lens_question_input")
            )
            IconButton(
                onClick = {
                    if (questionText.isNotBlank()) onAskAiVision(questionText)
                },
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("lens_send_question_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Ask Vision AI",
                    tint = NeonMagenta
                )
            }
        }

        // Vision Error Banner
        if (!visionError.isNullOrBlank()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF2B101A))
                    .border(1.dp, Color(0xFFEF4444), RoundedCornerShape(14.dp))
                    .padding(12.dp)
            ) {
                Text(text = visionError, color = Color(0xFFFEE2E2), fontSize = 12.sp)
                Spacer(modifier = Modifier.height(6.dp))
                Button(
                    onClick = onOpenApiSettings,
                    colors = ButtonDefaults.buttonColors(containerColor = NeonMagenta)
                ) {
                    Text("Configure API Key", fontSize = 12.sp)
                }
            }
        }

        // AI Vision Answer + OCR Panel
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(colors.surface)
                .border(1.dp, colors.outline, RoundedCornerShape(16.dp))
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Vision Analysis (${selectedLanguage.displayName})",
                    color = CyberCyan,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                if (aiVisionAnswer.isNotBlank()) {
                    IconButton(
                        onClick = { onSpeakAnswer(aiVisionAnswer) },
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = "Speak Vision Explanation",
                            tint = NeonMagenta
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = aiVisionAnswer.ifBlank { analyzer.formatOfflineMlKitSummary(selectedLanguage) },
                color = colors.onSurface,
                fontSize = 13.sp,
                modifier = Modifier.testTag("lens_result_text")
            )
        }
    }
}
