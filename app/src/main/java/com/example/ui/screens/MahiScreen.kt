package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.components.ActionExecutionCard
import com.example.ui.components.LiveWaveformVisualizer
import com.example.ui.components.MahiOrb
import com.example.ui.components.MahiStatusBanner
import com.example.ui.components.QuickPromptChips
import com.example.ui.components.SettingsModal
import com.example.ui.components.SubtitleOverlay
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.CyberDarkBackground
import com.example.ui.theme.CyberDarkSurface
import com.example.ui.theme.NeonMagenta
import com.example.ui.theme.NeonViolet
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.viewmodel.AssistantVisualState
import com.example.viewmodel.MahiAssistantViewModel
import kotlin.random.Random

@Composable
fun MahiScreen(
    viewModel: MahiAssistantViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasMicPermission = isGranted
        if (isGranted) {
            viewModel.startSession()
        }
    }

    var showSettingsModal by remember { mutableStateOf(false) }
    var showTextInput by remember { mutableStateOf(false) }
    var typedQuery by remember { mutableStateOf("") }

    // Auto-request mic permission on first launch if not granted
    LaunchedEffect(Unit) {
        if (!hasMicPermission) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = CyberDarkBackground,
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Cosmic starfield & nebula background canvas
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height * 0.45f)

                // Ambient Radial Glow
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            NeonViolet.copy(alpha = 0.16f),
                            NeonMagenta.copy(alpha = 0.08f),
                            Color.Transparent
                        ),
                        center = center,
                        radius = size.width * 0.85f
                    ),
                    radius = size.width * 0.85f,
                    center = center
                )

                // Background stars
                val rand = Random(42)
                for (i in 0..45) {
                    val x = rand.nextFloat() * size.width
                    val y = rand.nextFloat() * size.height
                    val starAlpha = 0.15f + rand.nextFloat() * 0.5f
                    val starRadius = 1f + rand.nextFloat() * 2f
                    drawCircle(
                        color = Color.White.copy(alpha = starAlpha),
                        radius = starRadius,
                        center = Offset(x, y)
                    )
                }
            }

            // Main Content Layout
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // 1. Top Section: Status Banner & Executed Tool Card
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    MahiStatusBanner(
                        visualState = uiState.visualState,
                        currentMood = uiState.currentMood,
                        isContinuousMode = uiState.isContinuousMode,
                        isMuted = uiState.isMuted,
                        onMoodClick = { showSettingsModal = true },
                        onContinuousToggle = { viewModel.toggleContinuousMode() },
                        onMuteToggle = { viewModel.toggleMute() },
                        onSettingsClick = { showSettingsModal = true }
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    ActionExecutionCard(
                        result = uiState.lastExecutedTool,
                        onDismiss = { viewModel.dismissToolCard() }
                    )
                }

                // 2. Middle Section: Central Interactive Mahi Orb & Status Prompt
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(vertical = 12.dp)
                ) {
                    val averageAmplitude = remember(uiState.waveformBars) {
                        uiState.waveformBars.average().toFloat()
                    }

                    MahiOrb(
                        visualState = uiState.visualState,
                        amplitude = averageAmplitude,
                        onClick = {
                            if (!hasMicPermission) {
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            } else {
                                viewModel.toggleSession()
                            }
                        },
                        orbSize = 210.dp
                    )

                    Spacer(modifier = Modifier.height(18.dp))

                    // Dynamic instruction text under Orb
                    val (statusLabel, statusDetail) = when (uiState.visualState) {
                        AssistantVisualState.DISCONNECTED -> "Tap Orb to Start" to "Continuous real-time voice mode"
                        AssistantVisualState.CONNECTING -> "Connecting..." to "Linking with Gemini Live Brain"
                        AssistantVisualState.LISTENING -> "Listening to you..." to "Speak casually, I'm all ears"
                        AssistantVisualState.THINKING -> "Thinking..." to "Cooking up a witty answer"
                        AssistantVisualState.SPEAKING -> "Mahi is speaking" to "Tap orb anytime to interrupt"
                        AssistantVisualState.ERROR -> "Offline Mode" to (uiState.errorMessage ?: "Tap to reconnect")
                    }

                    Text(
                        text = statusLabel,
                        color = when (uiState.visualState) {
                            AssistantVisualState.LISTENING -> CyberCyan
                            AssistantVisualState.SPEAKING -> NeonMagenta
                            AssistantVisualState.THINKING -> NeonViolet
                            else -> TextPrimary
                        },
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )

                    Text(
                        text = statusDetail,
                        color = TextSecondary,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                }

                // 3. Bottom Section: Visualizer, Subtitles, Prompt Chips, and Input Bar
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Live Waveform Visualizer
                    LiveWaveformVisualizer(
                        bars = uiState.waveformBars,
                        visualState = uiState.visualState,
                        height = 56.dp,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Subtitle / Caption Overlay
                    SubtitleOverlay(
                        visualState = uiState.visualState,
                        userText = uiState.liveUserText,
                        assistantText = uiState.liveAssistantText,
                        showSubtitles = uiState.showSubtitles,
                        modifier = Modifier.padding(bottom = 10.dp)
                    )

                    // Quick Prompt Chips
                    QuickPromptChips(
                        onPromptSelected = { prompt ->
                            if (!hasMicPermission) {
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            } else {
                                viewModel.processUserQuery(prompt)
                            }
                        },
                        modifier = Modifier.padding(bottom = 10.dp)
                    )

                    // Bottom Control / Text Fallback Input Bar
                    if (showTextInput) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(24.dp))
                                .background(CyberDarkSurface)
                                .border(1.dp, NeonViolet.copy(alpha = 0.6f), RoundedCornerShape(24.dp))
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = typedQuery,
                                onValueChange = { typedQuery = it },
                                placeholder = { Text("Say or type anything to Mahi...", color = TextSecondary, fontSize = 13.sp) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                keyboardActions = KeyboardActions(
                                    onSend = {
                                        if (typedQuery.isNotBlank()) {
                                            viewModel.processUserQuery(typedQuery)
                                            typedQuery = ""
                                            showTextInput = false
                                        }
                                    }
                                ),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color.Transparent,
                                    unfocusedBorderColor = Color.Transparent,
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary
                                ),
                                modifier = Modifier.weight(1f)
                            )

                            IconButton(
                                onClick = {
                                    if (typedQuery.isNotBlank()) {
                                        viewModel.processUserQuery(typedQuery)
                                        typedQuery = ""
                                        showTextInput = false
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "Send",
                                    tint = NeonMagenta
                                )
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Subtitle Toggle Pill
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Color(0xFF140F28))
                                    .clickable { viewModel.toggleSubtitles() }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = if (uiState.showSubtitles) "Subtitles: ON" else "Subtitles: OFF",
                                    color = if (uiState.showSubtitles) CyberCyan else TextSecondary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            // Optional Keyboard Input Toggle
                            IconButton(
                                onClick = { showTextInput = true },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Keyboard,
                                    contentDescription = "Text Input",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Settings Bottom Sheet Modal
            if (showSettingsModal) {
                SettingsModal(
                    currentMood = uiState.currentMood,
                    isContinuousMode = uiState.isContinuousMode,
                    showSubtitles = uiState.showSubtitles,
                    voicePitch = uiState.voicePitch,
                    voiceSpeed = uiState.voiceSpeed,
                    onMoodSelected = { mood ->
                        viewModel.setMood(mood)
                    },
                    onContinuousToggle = { viewModel.toggleContinuousMode() },
                    onSubtitlesToggle = { viewModel.toggleSubtitles() },
                    onPitchChanged = { viewModel.setVoicePitch(it) },
                    onSpeedChanged = { viewModel.setVoiceSpeed(it) },
                    onTestVoice = {
                        viewModel.processUserQuery("Say something confident and playful in your current voice!")
                    },
                    onDismiss = { showSettingsModal = false }
                )
            }
        }
    }
}
