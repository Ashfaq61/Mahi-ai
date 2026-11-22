package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.control.AllVolumeLevels
import com.example.control.BatteryInfoSnapshot
import com.example.control.DeviceInfoSnapshot
import com.example.control.NetworkInfoSnapshot
import com.example.control.StorageInfoSnapshot
import com.example.control.VolumeStreamType
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.CyberEmerald
import com.example.ui.theme.NeonMagenta
import com.example.ui.theme.NeonViolet

@Composable
fun SmartControlScreen(
    isFlashlightOn: Boolean,
    brightnessPercent: Int,
    canWriteSettings: Boolean,
    volumeLevels: AllVolumeLevels,
    batteryInfo: BatteryInfoSnapshot,
    storageInfo: StorageInfoSnapshot,
    networkInfo: NetworkInfoSnapshot,
    deviceInfo: DeviceInfoSnapshot,
    lastControlStatus: String,
    onBack: () -> Unit,
    onRefreshAll: () -> Unit,
    onLaunchApp: (String) -> Unit,
    onNavigateToAgent: () -> Unit,
    onOpenQuickSettingsShade: () -> Unit,
    onOpenNotificationShade: () -> Unit,
    onOpenWifiPanel: () -> Unit,
    onOpenBluetoothPanel: () -> Unit,
    onOpenMobileDataSettings: () -> Unit,
    onOpenAirplaneSettings: () -> Unit,
    onOpenNfcPanel: () -> Unit,
    onToggleFlashlight: () -> Unit,
    onSetVolumeStream: (VolumeStreamType, Int) -> Unit,
    onToggleMuteMedia: () -> Unit,
    onSetBrightness: (Int) -> Unit,
    onOpenWriteSettings: () -> Unit,
    onOpenDndSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("smart_control_screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.minimumInteractiveComponentSize()) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = colors.onBackground)
                }
                Column {
                    Text("SMART DEVICE CONTROL", color = CyberCyan, fontSize = 11.sp, fontWeight = FontWeight.Black)
                    Text("System Hardware & Panels", color = colors.onBackground, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
            }
            IconButton(
                onClick = onRefreshAll,
                modifier = Modifier.minimumInteractiveComponentSize().testTag("smart_control_refresh_button")
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh Telemetry", tint = CyberCyan)
            }
        }

        if (lastControlStatus.isNotBlank()) {
            Text(
                text = lastControlStatus,
                color = CyberEmerald,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(CyberEmerald.copy(alpha = 0.12f))
                    .padding(10.dp)
            )
        }

        // Live Telemetry Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(colors.surface)
                .border(1.dp, colors.outline, RoundedCornerShape(16.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("Live Device Telemetry", color = CyberCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text("Battery: ${batteryInfo.statusSummary.ifBlank { "${batteryInfo.levelPercent}%" }}", color = colors.onSurface, fontSize = 12.sp)
            Text("Storage: ${storageInfo.summary}", color = colors.onSurface, fontSize = 12.sp)
            Text("Network: ${networkInfo.summary}", color = colors.onSurface, fontSize = 12.sp)
            Text("Device: ${deviceInfo.manufacturerModel} • ${deviceInfo.androidVersion} (SDK ${deviceInfo.sdkInt}) • Up ${deviceInfo.uptimeFormatted}", color = colors.onSurfaceVariant, fontSize = 11.sp)
        }

        // Flashlight + System Shades
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(colors.surface)
                .border(1.dp, NeonViolet.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Hardware & Quick Panels", color = CyberCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = onToggleFlashlight,
                    colors = ButtonDefaults.buttonColors(containerColor = if (isFlashlightOn) NeonMagenta else colors.surfaceVariant),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(if (isFlashlightOn) Icons.Default.FlashlightOn else Icons.Default.FlashlightOff, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (isFlashlightOn) "Torch ON" else "Torch OFF", fontSize = 11.sp, color = if (isFlashlightOn) Color.White else colors.onSurface)
                }
                OutlinedButton(onClick = onOpenQuickSettingsShade, modifier = Modifier.weight(1f)) {
                    Text("Quick Shade", fontSize = 11.sp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onOpenWifiPanel, modifier = Modifier.weight(1f)) { Text("Wi-Fi", fontSize = 11.sp) }
                OutlinedButton(onClick = onOpenBluetoothPanel, modifier = Modifier.weight(1f)) { Text("Bluetooth", fontSize = 11.sp) }
                OutlinedButton(onClick = onOpenMobileDataSettings, modifier = Modifier.weight(1f)) { Text("Data", fontSize = 11.sp) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onOpenNotificationShade, modifier = Modifier.weight(1f)) { Text("Notifs", fontSize = 11.sp) }
                OutlinedButton(onClick = onOpenAirplaneSettings, modifier = Modifier.weight(1f)) { Text("Airplane", fontSize = 11.sp) }
                OutlinedButton(onClick = onOpenNfcPanel, modifier = Modifier.weight(1f)) { Text("NFC", fontSize = 11.sp) }
            }
        }

        // Volume & Brightness
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(colors.surface)
                .border(1.dp, colors.outline, RoundedCornerShape(16.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Media Volume: ${volumeLevels.mediaPercent}%", color = colors.onSurface, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                OutlinedButton(onClick = onToggleMuteMedia) { Text("Mute/Unmute", fontSize = 10.sp) }
            }
            Slider(
                value = volumeLevels.mediaPercent.toFloat(),
                onValueChange = { onSetVolumeStream(VolumeStreamType.MEDIA, it.toInt()) },
                valueRange = 0f..100f
            )
            Text("Ringtone Volume: ${volumeLevels.ringtonePercent}%", color = colors.onSurface, fontSize = 12.sp)
            Slider(
                value = volumeLevels.ringtonePercent.toFloat(),
                onValueChange = { onSetVolumeStream(VolumeStreamType.RINGTONE, it.toInt()) },
                valueRange = 0f..100f
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Brightness: $brightnessPercent%", color = colors.onSurface, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                if (!canWriteSettings) {
                    OutlinedButton(onClick = onOpenWriteSettings) { Text("Allow Brightness", fontSize = 10.sp) }
                }
            }
            Slider(
                value = brightnessPercent.toFloat(),
                onValueChange = { onSetBrightness(it.toInt()) },
                valueRange = 5f..100f
            )
            OutlinedButton(onClick = onOpenDndSettings, modifier = Modifier.fillMaxWidth()) {
                Text("Do Not Disturb Permission", fontSize = 11.sp)
            }
        }

        // Quick App Launchers
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(colors.surface)
                .border(1.dp, colors.outline, RoundedCornerShape(16.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Quick App Launcher", color = CyberCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { onLaunchApp("YouTube") }, modifier = Modifier.weight(1f)) { Text("YouTube", fontSize = 11.sp) }
                OutlinedButton(onClick = { onLaunchApp("WhatsApp") }, modifier = Modifier.weight(1f)) { Text("WhatsApp", fontSize = 11.sp) }
                OutlinedButton(onClick = { onLaunchApp("Chrome") }, modifier = Modifier.weight(1f)) { Text("Chrome", fontSize = 11.sp) }
            }
            Button(onClick = onNavigateToAgent, modifier = Modifier.fillMaxWidth()) {
                Text("Open Screen Autopilot Console")
            }
        }
    }
}
