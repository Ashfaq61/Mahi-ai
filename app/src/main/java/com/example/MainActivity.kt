package com.example

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.screens.MahiScreen
import com.example.ui.theme.MahiAiTheme
import com.example.util.configureAppCheck
import com.example.viewmodel.MahiAssistantViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: MahiAssistantViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        applyWakeScreenFlagsIfNeeded(intent)

        // STEP 1: Mandatory App Check configuration with launch intent before any UI or Firebase operations
        configureAppCheck(this, intent)

        // STEP 2: Edge-to-Edge full bleed immersive screen
        enableEdgeToEdge()

        // Handle tel: / Dialer intent forwarding if launched via MahiDialerActivity
        viewModel.handleIncomingAppIntent(intent)

        // STEP 3: Render Compose UI
        setContent {
            val prefs by viewModel.userPrefs.collectAsStateWithLifecycle()
            MahiAiTheme(themeMode = prefs.themeMode) {
                MahiScreen(viewModel = viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyWakeScreenFlagsIfNeeded(intent)
        viewModel.handleIncomingAppIntent(intent)
    }

    private fun applyWakeScreenFlagsIfNeeded(incomingIntent: Intent?) {
        val fromWake = incomingIntent?.getBooleanExtra(WakePopupActivity.EXTRA_START_LIVE_FROM_WAKE, false) == true
        if (!fromWake) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
    }
}
