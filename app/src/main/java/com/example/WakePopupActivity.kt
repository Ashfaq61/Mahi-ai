package com.example

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.audio.MahiVoiceForegroundService
import com.example.preferences.MahiPreferencesStore
import com.example.ui.components.MahiOrb
import com.example.ui.theme.CyberBorderGlow
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.CyberDarkSurface
import com.example.ui.theme.MahiAiTheme
import com.example.ui.theme.NeonMagenta
import com.example.viewmodel.AssistantVisualState
import kotlinx.coroutines.delay

/**
 * Translucent Cyber Glow Wake Popup shown when the user says "Hey Mahi" while Wake Up Detection is active.
 * Uses Android's standard activity + showWhenLocked/turnScreenOn (NO SYSTEM_ALERT_WINDOW needed),
 * displays MahiOrb + name cleanly, and hands off immediately to MainActivity -> Gemini Live.
 */
class WakePopupActivity : ComponentActivity() {

    companion object {
        const val EXTRA_START_LIVE_FROM_WAKE = "extra_start_live_from_wake"
        const val EXTRA_WAKE_TRANSCRIPT = "extra_wake_transcript"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        val prefs = MahiPreferencesStore(this).prefsFlow.value
        val transcript = intent?.getStringExtra(EXTRA_WAKE_TRANSCRIPT) ?: "Hey Mahi"

        setContent {
            MahiAiTheme(themeMode = prefs.themeMode) {
                LaunchedEffect(Unit) {
                    delay(1200)
                    handoffToMainLive()
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 32.dp)
                            .clip(RoundedCornerShape(28.dp))
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        CyberDarkSurface.copy(alpha = 0.96f),
                                        Color(0xFF0A0618).copy(alpha = 0.98f)
                                    )
                                )
                            )
                            .border(1.5.dp, CyberBorderGlow, RoundedCornerShape(28.dp))
                            .padding(horizontal = 24.dp, vertical = 22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        MahiOrb(
                            visualState = AssistantVisualState.LISTENING,
                            amplitude = 0.45f,
                            orbSize = 112.dp,
                            onClick = { handoffToMainLive() }
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Mahi AI",
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Heard \"$transcript\" — opening voice session…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = CyberCyan
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            OutlinedButton(
                                onClick = { dismissPopup() },
                                modifier = Modifier.testTag("wake_popup_dismiss_button")
                            ) {
                                Text("Dismiss", color = Color.White.copy(alpha = 0.8f))
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Button(
                                onClick = { handoffToMainLive() },
                                colors = ButtonDefaults.buttonColors(containerColor = NeonMagenta),
                                modifier = Modifier.testTag("wake_popup_talk_button")
                            ) {
                                Text("Talk Now", color = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun handoffToMainLive() {
        MahiVoiceForegroundService.pauseWakeForActiveSession(this)
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(EXTRA_START_LIVE_FROM_WAKE, true)
        }
        startActivity(mainIntent)
        finish()
    }

    private fun dismissPopup() {
        MahiVoiceForegroundService.resumeWakeIfEnabled(this)
        finish()
    }
}
