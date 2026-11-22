package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.BuildConfig
import com.example.automation.MahiAccessibilityService
import com.example.automation.MahiNotificationService
import com.example.automation.ScreenSnapshot
import com.example.automation.TestPlaygroundActivity
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.CyberEmerald

@Composable
fun ScreenDoctorModal(
    screenSnapshot: ScreenSnapshot,
    learnedLabelsCount: Int,
    lastExternalTargetPackage: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val a11yOk = MahiAccessibilityService.isAccessibilityServiceEnabledInSettings(context)
    val notifOk = MahiNotificationService.isListenerEnabled(context)
    var copiedFeedback by remember { mutableStateOf("") }

    val report = remember(screenSnapshot, learnedLabelsCount, a11yOk, notifOk, lastExternalTargetPackage) {
        buildString {
            appendLine("Mahi AI Diagnostics v${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})")
            appendLine("AppId: ${BuildConfig.APPLICATION_ID}")
            appendLine("Accessibility Service: ${if (a11yOk) "CONNECTED" else "DISABLED"}")
            appendLine("Notification Listener: ${if (notifOk) "CONNECTED" else "DISABLED"}")
            appendLine("Last External Package: ${lastExternalTargetPackage.ifBlank { "None yet" }}")
            appendLine("Learned Button Labels: $learnedLabelsCount")
            appendLine("Current Screen Elements: ${screenSnapshot.elements.size} (${screenSnapshot.packageName})")
            appendLine("Debug Signing SHA-256: D2:F3:5C:D8:77:C6:2D:A6:06:DB:7A:C2:1A:C4:A6:E3:54:9E:BE:75:64:1A:73:A7:1C:A1:67:0E:DC:AC:55:66")
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = colors.surface,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, CyberCyan.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                .testTag("screen_doctor_modal")
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Mahi Doctor & Self-Test", color = CyberCyan, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    TextButton(onClick = onDismiss) { Text("Close") }
                }

                Text(
                    text = report,
                    color = colors.onSurface,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.surfaceVariant)
                        .padding(10.dp)
                )

                Button(
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(context, TestPlaygroundActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Open Automation Test Playground")
                }

                OutlinedButton(
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cm?.setPrimaryClip(ClipData.newPlainText("Mahi Doctor Report", report))
                        copiedFeedback = "Diagnostics report copied to clipboard."
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Copy Diagnostics Report")
                }

                if (copiedFeedback.isNotBlank()) {
                    Text(copiedFeedback, color = CyberEmerald, fontSize = 11.sp)
                }
            }
        }
    }
}
