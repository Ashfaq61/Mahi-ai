package com.example.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.control.PermissionAuditItem
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.CyberEmerald
import com.example.ui.theme.WarningAmber

data class HonestFeatureStatusEntry(
    val feature: String,
    val status: String,
    val notes: String
)

@Composable
fun FeatureAuditAndDiagnosticsScreen(
    permissions: List<PermissionAuditItem>,
    onRefreshPermissions: () -> Unit,
    onOpenSettingsSection: (String) -> Unit,
    onOpenRestrictedSettings: () -> Unit,
    onRequestBatteryOptimization: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val runtimePermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        onRefreshPermissions()
    }

    val honestMatrix = listOf(
        HonestFeatureStatusEntry("AI Brain + Multi-Slot Encrypted Keys + ModelRegistry", "IMPLEMENTED_NOT_DEVICE_VERIFIED", "Unit-tested encryption, capability parsing & Room caching; live network key test requires device + key."),
        HonestFeatureStatusEntry("Voice Orb + TTS Speed (0.5x–2.0x w/ Clarity Balance)", "ACTIVE_UNIT_TESTED", "Math, clarity curve & UI slider verified in Robolectric tests; Android hardware TTS runs on device."),
        HonestFeatureStatusEntry("Screen Reader V2 + Autopilot Loop + SoM Fallback", "ACTIVE_UNIT_TESTED", "Observed-Think-Act-Verify, password/OTP redaction & security walls pass unit tests; live A11y requires physical phone."),
        HonestFeatureStatusEntry("CameraX Live Preview, Photo/Video Capture & Vision Q&A", "IMPLEMENTED_NOT_DEVICE_VERIFIED", "Real CameraX Preview/MediaStore + Gemini Vision wiring; physical camera requires device hardware."),
        HonestFeatureStatusEntry("Telecom Call Screening & Speakerphone AI Bridge", "PARTIAL_OS_RESTRICTED", "Android blocks direct cellular voice stream injection on unprivileged apps; uses explicit Speakerphone Bridge Consent."),
        HonestFeatureStatusEntry("Offline GGUF / LiteRT Local LLM Execution", "BLOCKED_NO_MODEL_WEIGHTS", "Hardware RAM/Storage readiness checker active; local inference stays NOT_INSTALLED until GGUF weights are downloaded.")
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("feature_audit_screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("HONEST FEATURE AUDIT & PERMISSION ONBOARDING", color = CyberCyan, fontSize = 11.sp, fontWeight = FontWeight.Black)
        Text("System Readiness & Permissions", color = colors.onBackground, fontSize = 17.sp, fontWeight = FontWeight.Bold)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    val reqs = buildList {
                        add(Manifest.permission.RECORD_AUDIO)
                        add(Manifest.permission.CAMERA)
                        add(Manifest.permission.ACCESS_FINE_LOCATION)
                        add(Manifest.permission.READ_PHONE_STATE)
                        add(Manifest.permission.CALL_PHONE)
                        add(Manifest.permission.READ_CONTACTS)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) add(Manifest.permission.ANSWER_PHONE_CALLS)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
                    }.toTypedArray()
                    runtimePermLauncher.launch(reqs)
                },
                modifier = Modifier.weight(1f)
            ) { Text("Grant Runtime Perms", fontSize = 11.sp) }
            OutlinedButton(onClick = onRefreshPermissions, modifier = Modifier.weight(1f)) {
                Text("Refresh Checks", fontSize = 11.sp)
            }
        }

        permissions.forEach { item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.surface)
                    .border(1.dp, if (item.granted) CyberEmerald.copy(alpha = 0.5f) else WarningAmber.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text("${item.title}: ${if (item.granted) "GRANTED" else "MISSING"}", color = if (item.granted) CyberEmerald else WarningAmber, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(item.detail, color = colors.onSurfaceVariant, fontSize = 10.sp)
                }
                if (!item.granted) {
                    OutlinedButton(onClick = {
                        when (item.id) {
                            "accessibility" -> onOpenSettingsSection("accessibility")
                            "notif_listener" -> onOpenSettingsSection("notification_listener")
                            "overlay" -> onOpenSettingsSection("overlay")
                            "write_settings" -> onOpenSettingsSection("write_settings")
                            "battery" -> onRequestBatteryOptimization()
                            else -> onOpenRestrictedSettings()
                        }
                    }) { Text(item.actionLabel, fontSize = 10.sp) }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onOpenRestrictedSettings, modifier = Modifier.weight(1f)) {
                Text("Android 13–16 Restricted Settings", fontSize = 10.sp)
            }
            OutlinedButton(onClick = onRequestBatteryOptimization, modifier = Modifier.weight(1f)) {
                Text("Battery Whitelist", fontSize = 10.sp)
            }
        }

        Text("Verified Feature Status Ledger", color = CyberCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        honestMatrix.forEach { entry ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.surfaceVariant)
                    .padding(10.dp)
            ) {
                Text("${entry.feature} — [${entry.status}]", color = colors.onSurface, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text(entry.notes, color = colors.onSurfaceVariant, fontSize = 10.sp)
            }
        }
    }
}
