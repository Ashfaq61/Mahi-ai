package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.automation.MahiAccessibilityService
import com.example.automation.TestPlaygroundActivity
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.CyberEmerald
import com.example.ui.theme.NeonMagenta
import com.example.ui.theme.NeonViolet
import com.example.ui.theme.WarningAmber
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ScreenDoctorModal(
    onDismiss: () -> Unit,
    onOpenPlayground: () -> Unit = {}
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()

    val isServiceConnected by MahiAccessibilityService.isConnected.collectAsState()
    val gestureHistory by MahiAccessibilityService.gestureHistory.collectAsState()
    val isServiceEnabled = MahiAccessibilityService.isServiceEnabled(context)

    var countdownSec by remember { mutableIntStateOf(0) }
    var foregroundReadStatus by remember { mutableStateOf("Tap button to switch to another app (5s countdown) and inspect its UI tree.") }
    var screenshotTestStatus by remember { mutableStateOf(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) "Ready (API ${Build.VERSION.SDK_INT} supports takeScreenshot)" else "Requires Android 11+ (API 30)") }
    var copiedNotice by remember { mutableStateOf("") }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .heightIn(max = 680.dp)
                .clip(RoundedCornerShape(22.dp))
                .border(1.dp, CyberCyan.copy(alpha = 0.55f), RoundedCornerShape(22.dp))
                .testTag("screen_doctor_modal"),
            color = colors.surface
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Build, contentDescription = null, tint = CyberCyan, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text("SCREEN DOCTOR", color = CyberCyan, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                            Text("Live Diagnostics & Log", color = colors.onSurface, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("screen_doctor_close_button")
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close Screen Doctor", tint = colors.onSurface)
                    }
                }

                // 1. Accessibility Service Check
                DoctorStatusRow(
                    title = "1. Accessibility Service",
                    passed = isServiceEnabled && isServiceConnected,
                    detail = when {
                        isServiceEnabled && isServiceConnected -> "PASS — Active & bound (${Build.MODEL}, Android ${Build.VERSION.RELEASE})"
                        isServiceEnabled -> "PENDING — Enabled in Settings, waiting for Android service bind"
                        else -> "FAIL — Disabled. Turn on Mahi AI in Accessibility Settings."
                    }
                )

                // 2. Android 13+ Restricted Settings Guide
                if (!isServiceEnabled || Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(colors.surfaceVariant)
                            .border(1.dp, WarningAmber.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "2. Android 13–15 Sideloaded APK Guide (Restricted Setting)",
                            color = WarningAmber,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "If Android grays out the Mahi toggle with 'Restricted setting':\n" +
                                "1) Open App Info below\n" +
                                "2) Tap the top-right ⋮ menu\n" +
                                "3) Tap 'Allow restricted settings' & confirm PIN/biometric\n" +
                                "4) Return to Accessibility Settings and toggle Mahi AI ON.",
                            color = colors.onSurface,
                            fontSize = 11.sp
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    context.startActivity(
                                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                            data = Uri.parse("package:${context.packageName}")
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                    )
                                },
                                modifier = Modifier.weight(1f).minimumInteractiveComponentSize()
                            ) {
                                Text("Open App Info", fontSize = 11.sp)
                            }
                            OutlinedButton(
                                onClick = { MahiAccessibilityService.openAccessibilitySettings(context) },
                                modifier = Modifier.weight(1f).minimumInteractiveComponentSize()
                            ) {
                                Text("Accessibility", fontSize = 11.sp)
                            }
                        }
                    }
                }

                // 3. Foreground App 5s Countdown Check
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.surfaceVariant)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("3. Foreground App Reader (5s Countdown)", color = colors.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(
                        text = if (countdownSec > 0) "Switch to another app now: reading in ${countdownSec}s..." else foregroundReadStatus,
                        color = CyberCyan,
                        fontSize = 11.sp
                    )
                    OutlinedButton(
                        onClick = {
                            if (countdownSec > 0) return@OutlinedButton
                            scope.launch {
                                for (s in 5 downTo 1) {
                                    countdownSec = s
                                    delay(1000L)
                                }
                                countdownSec = 0
                                val res = MahiAccessibilityService.readScreenWithRetry(context)
                                foregroundReadStatus = res.fold(
                                    onSuccess = { snap ->
                                        "PASS — ${snap.packageName} (${snap.elements.size} elements)\n" +
                                            "${snap.summaryLine}\n" +
                                            snap.elements.take(5).joinToString(" | ") { "${it.index}. ${it.bestLabel}" }
                                    },
                                    onFailure = { err -> "FAIL — ${err.localizedMessage}" }
                                )
                            }
                        },
                        enabled = countdownSec == 0,
                        modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize().testTag("screen_doctor_countdown_button")
                    ) {
                        Text(if (countdownSec > 0) "Countdown: ${countdownSec}s" else "Start 5s Foreground Read Test", fontSize = 12.sp)
                    }
                }

                // 4. Screenshot Capture Check
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.surfaceVariant)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("4. Accessibility Screenshot Test", color = colors.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(screenshotTestStatus, color = colors.onSurfaceVariant, fontSize = 11.sp)
                    OutlinedButton(
                        onClick = {
                            MahiAccessibilityService.takeScreenshotBitmap { res ->
                                screenshotTestStatus = res.fold(
                                    onSuccess = { bmp -> "PASS — Captured ${bmp.width}x${bmp.height}px bitmap" },
                                    onFailure = { err -> "FAIL — ${err.localizedMessage}" }
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize().testTag("screen_doctor_screenshot_button")
                    ) {
                        Text("Run Screenshot Capture Test", fontSize = 12.sp)
                    }
                }

                // 5. Gesture & Self-Test Playground Launch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            onOpenPlayground()
                            context.startActivity(
                                Intent(context, TestPlaygroundActivity::class.java).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = NeonMagenta),
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .testTag("screen_doctor_open_playground_button")
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Open Test Playground", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = {
                            val report = buildScreenDoctorReport(
                                context = context,
                                isServiceEnabled = isServiceEnabled,
                                isConnected = isServiceConnected,
                                foregroundReadStatus = foregroundReadStatus,
                                screenshotTestStatus = screenshotTestStatus,
                                history = gestureHistory
                            )
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            cm?.setPrimaryClip(ClipData.newPlainText("Mahi Screen Doctor Report", report))
                            copiedNotice = "Copied full diagnostic report (${gestureHistory.size} events) to clipboard."
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = NeonViolet),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("screen_doctor_copy_report_button")
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy Report", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Copy Report", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                if (copiedNotice.isNotBlank()) {
                    Text(copiedNotice, color = CyberEmerald, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }

                // Last 40 actions log
                Text(
                    text = "Recent Screen Engine Log (${gestureHistory.size}/40)",
                    color = CyberCyan,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                if (gestureHistory.isEmpty()) {
                    Text("No screen actions recorded in this session yet.", color = colors.onSurfaceVariant, fontSize = 11.sp)
                } else {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                        modifier = Modifier.testTag("screen_doctor_history_list")
                    ) {
                        gestureHistory.take(40).forEach { rec ->
                            Text(
                                text = "${if (rec.success) "✓" else "✗"} [${rec.actionName}] ${rec.detail}",
                                color = if (rec.success) colors.onSurface else WarningAmber,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DoctorStatusRow(title: String, passed: Boolean, detail: String) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surfaceVariant)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (passed) Icons.Default.CheckCircle else Icons.Default.Warning,
            contentDescription = null,
            tint = if (passed) CyberEmerald else WarningAmber,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(title, color = colors.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(detail, color = if (passed) CyberEmerald else WarningAmber, fontSize = 11.sp)
        }
    }
}

fun buildScreenDoctorReport(
    context: Context,
    isServiceEnabled: Boolean,
    isConnected: Boolean,
    foregroundReadStatus: String,
    screenshotTestStatus: String,
    history: List<com.example.automation.GestureActionRecord>
): String = buildString {
    appendLine("=== MAHI AI SCREEN DOCTOR REPORT ===")
    appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT})")
    appendLine("Accessibility Enabled: $isServiceEnabled | Connected: $isConnected")
    appendLine("Last Target App: ${MahiAccessibilityService.getLastTargetPackage().ifBlank { "none" }}")
    appendLine("Playground Self-Test: ${TestPlaygroundActivity.lastSelfTestSummary}")
    appendLine("Foreground Read: $foregroundReadStatus")
    appendLine("Screenshot Check: $screenshotTestStatus")
    appendLine("--- Last ${history.size} Actions ---")
    history.take(40).forEach { rec ->
        appendLine("${if (rec.success) "PASS" else "FAIL"} [${rec.actionName}] ${rec.detail}")
    }
}
