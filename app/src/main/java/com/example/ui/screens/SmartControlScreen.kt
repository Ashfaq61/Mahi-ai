package com.example.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.automation.MahiAccessibilityService
import com.example.automation.MahiNotificationService
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
import com.example.ui.theme.WarningAmber

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
    onToggleFlashlight: (Boolean) -> Unit,
    onSetVolumeStream: (VolumeStreamType, Int) -> Unit,
    onToggleMuteMedia: (Boolean) -> Unit,
    onSetBrightness: (Int) -> Unit,
    onOpenWriteSettings: () -> Unit,
    onOpenDndSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    var appQuery by remember { mutableStateOf("") }
    var permTick by remember { mutableIntStateOf(0) }

    val runtimePermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permTick++
        onRefreshAll()
    }

    val isAccessibilityOn = remember(permTick) { MahiAccessibilityService.isServiceEnabled(context) }
    val isNotificationsOn = remember(permTick) { MahiNotificationService.isListenerEnabled(context) }
    val hasMicPerm = remember(permTick) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }
    val hasCameraPerm = remember(permTick) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }
    val hasPhonePerm = remember(permTick) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("smart_control_screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("smart_control_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = CyberCyan
                    )
                }
                Column {
                    Text(
                        text = "SMARTPHONE CONTROL",
                        color = CyberCyan,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "Smart Smartphone Control",
                        color = colors.onBackground,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            IconButton(
                onClick = {
                    permTick++
                    onRefreshAll()
                },
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("smart_control_refresh_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Refresh Live Telemetry",
                    tint = NeonMagenta
                )
            }
        }

        if (lastControlStatus.isNotBlank()) {
            Text(
                text = lastControlStatus,
                color = CyberCyan,
                fontSize = 12.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.surfaceVariant)
                    .padding(10.dp)
            )
        }

        // 1. App Control Card
        ControlCard(
            title = "App Control",
            subtitle = "Launch installed Android apps cleanly",
            badgeText = "READY",
            badgeColor = CyberEmerald,
            icon = Icons.Default.PhoneAndroid
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = appQuery,
                    onValueChange = { appQuery = it },
                    placeholder = { Text("App name (WhatsApp, YouTube…)", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = {
                        if (appQuery.isNotBlank()) onLaunchApp(appQuery.trim())
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NeonMagenta)
                ) {
                    Text("Open", fontSize = 12.sp)
                }
            }
        }

        // 2. Screen Automation Card (links to existing Agent Automation screen without duplicating it)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(colors.surface)
                .border(1.dp, colors.outline, RoundedCornerShape(16.dp))
                .clickable { onNavigateToAgent() }
                .padding(14.dp)
                .testTag("smart_control_open_agent_card"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.SmartToy, contentDescription = null, tint = NeonViolet, modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text("Screen Automation", color = colors.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(
                        text = if (isAccessibilityOn) "Accessibility ON • Open Agent Screen" else "Accessibility OFF • Open Agent Setup",
                        color = if (isAccessibilityOn) CyberEmerald else WarningAmber,
                        fontSize = 12.sp
                    )
                }
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Go to Agent", tint = CyberCyan)
        }

        // 3. Quick Settings Card
        ControlCard(
            title = "Quick Settings & System Panels",
            subtitle = "Android 10+ blocks silent Wi-Fi/Data toggles, so Mahi opens official panels",
            badgeText = if (isAccessibilityOn) "SHADE ON" else "PANELS",
            badgeColor = CyberCyan,
            icon = Icons.Default.Tune
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onOpenQuickSettingsShade, modifier = Modifier.weight(1f)) {
                        Text("Quick Settings", fontSize = 11.sp)
                    }
                    OutlinedButton(onClick = onOpenNotificationShade, modifier = Modifier.weight(1f)) {
                        Text("Notifications", fontSize = 11.sp)
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onOpenWifiPanel, modifier = Modifier.weight(1f)) {
                        Text("Wi-Fi Panel", fontSize = 11.sp)
                    }
                    OutlinedButton(onClick = onOpenBluetoothPanel, modifier = Modifier.weight(1f)) {
                        Text("Bluetooth", fontSize = 11.sp)
                    }
                    OutlinedButton(onClick = onOpenMobileDataSettings, modifier = Modifier.weight(1f)) {
                        Text("Mobile Data", fontSize = 11.sp)
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onOpenAirplaneSettings, modifier = Modifier.weight(1f)) {
                        Text("Airplane Mode", fontSize = 11.sp)
                    }
                    OutlinedButton(onClick = onOpenNfcPanel, modifier = Modifier.weight(1f)) {
                        Text("NFC Panel", fontSize = 11.sp)
                    }
                }
            }
        }

        // 4. Flashlight Card
        ControlCard(
            title = "Flashlight",
            subtitle = if (isFlashlightOn) "Hardware torch active" else "Torch off",
            badgeText = if (isFlashlightOn) "ON" else "OFF",
            badgeColor = if (isFlashlightOn) CyberEmerald else colors.onSurfaceVariant,
            icon = Icons.Default.FlashlightOn
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Rear Camera Torch", color = colors.onSurface, fontSize = 13.sp)
                Switch(
                    checked = isFlashlightOn,
                    onCheckedChange = onToggleFlashlight,
                    colors = SwitchDefaults.colors(checkedTrackColor = NeonMagenta),
                    modifier = Modifier.testTag("smart_control_flashlight_switch")
                )
            }
        }

        // 5. Volume Card (All 5 streams + Mute + DND check)
        ControlCard(
            title = "Volume (All Streams)",
            subtitle = "Ringer: ${volumeLevels.ringerMode} • DND Access: ${if (volumeLevels.hasDndPolicyAccess) "Granted" else "Not Granted"}",
            badgeText = "${volumeLevels.mediaPercent}%",
            badgeColor = NeonMagenta,
            icon = Icons.Default.VolumeUp
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                VolumeSliderRow("Media", volumeLevels.mediaPercent) { onSetVolumeStream(VolumeStreamType.MEDIA, it) }
                VolumeSliderRow("Ringtone", volumeLevels.ringtonePercent) { onSetVolumeStream(VolumeStreamType.RINGTONE, it) }
                VolumeSliderRow("Notification", volumeLevels.notificationPercent) { onSetVolumeStream(VolumeStreamType.NOTIFICATION, it) }
                VolumeSliderRow("Alarm", volumeLevels.alarmPercent) { onSetVolumeStream(VolumeStreamType.ALARM, it) }
                VolumeSliderRow("Voice Call", volumeLevels.callPercent) { onSetVolumeStream(VolumeStreamType.CALL, it) }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onToggleMuteMedia(!volumeLevels.isMediaMuted) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (volumeLevels.isMediaMuted) "Unmute Media" else "Mute Media", fontSize = 12.sp)
                    }
                    if (!volumeLevels.hasDndPolicyAccess) {
                        OutlinedButton(onClick = onOpenDndSettings, modifier = Modifier.weight(1f)) {
                            Text("Grant DND Access", fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // 6. Brightness Card
        ControlCard(
            title = "Screen Brightness",
            subtitle = if (canWriteSettings) "Manual system brightness write active" else "Needs Modify System Settings permission",
            badgeText = if (canWriteSettings) "$brightnessPercent%" else "NEEDS PERM",
            badgeColor = if (canWriteSettings) CyberCyan else WarningAmber,
            icon = Icons.Default.Brightness6
        ) {
            Column {
                Slider(
                    value = brightnessPercent.toFloat(),
                    onValueChange = { onSetBrightness(it.toInt()) },
                    valueRange = 5f..100f,
                    colors = SliderDefaults.colors(thumbColor = CyberCyan, activeTrackColor = CyberCyan)
                )
                if (!canWriteSettings) {
                    OutlinedButton(onClick = onOpenWriteSettings) {
                        Text("Allow Modify System Settings", fontSize = 12.sp)
                    }
                }
            }
        }

        // 7. Battery Card
        ControlCard(
            title = "Battery Information",
            subtitle = "${batteryInfo.chargeSource} • ${String.format("%.1f", batteryInfo.temperatureCelsius)}°C • ${String.format("%.2f", batteryInfo.voltageVolts)}V • Health: ${batteryInfo.healthLabel}",
            badgeText = "${batteryInfo.levelPercent}%",
            badgeColor = if (batteryInfo.levelPercent > 20) CyberEmerald else WarningAmber,
            icon = Icons.Default.BatteryChargingFull
        ) {
            Text(batteryInfo.statusSummary, color = colors.onSurfaceVariant, fontSize = 12.sp)
        }

        // 8. Storage Card
        ControlCard(
            title = "Internal Storage",
            subtitle = String.format("%.1f GB free / %.1f GB total", storageInfo.freeGb, storageInfo.totalGb),
            badgeText = "${storageInfo.usedPercent}% USED",
            badgeColor = NeonViolet,
            icon = Icons.Default.Storage
        ) {
            Text(storageInfo.summary, color = colors.onSurfaceVariant, fontSize = 12.sp)
        }

        // 9. Network Card
        ControlCard(
            title = "Network & Connectivity",
            subtitle = networkInfo.wifiSsidOrNote.ifBlank { networkInfo.transportLabel },
            badgeText = if (networkInfo.isConnected) networkInfo.transportLabel.uppercase() else "OFFLINE",
            badgeColor = if (networkInfo.isConnected) CyberEmerald else WarningAmber,
            icon = Icons.Default.Wifi
        ) {
            Text(networkInfo.summary, color = colors.onSurfaceVariant, fontSize = 12.sp)
        }

        // 10. Device Info Card
        ControlCard(
            title = "Device Information",
            subtitle = "${deviceInfo.androidVersion} • Uptime ${deviceInfo.uptimeFormatted}",
            badgeText = "API ${deviceInfo.sdkInt}",
            badgeColor = CyberCyan,
            icon = Icons.Default.Settings
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Model: ${deviceInfo.manufacturerModel}", color = colors.onSurface, fontSize = 12.sp)
                Text(
                    text = String.format("RAM: %.1f GB free / %.1f GB total • Patch: %s", deviceInfo.availRamGb, deviceInfo.totalRamGb, deviceInfo.securityPatch),
                    color = colors.onSurfaceVariant,
                    fontSize = 12.sp
                )
            }
        }

        // 11. Permission Status Card
        ControlCard(
            title = "Permission Status",
            subtitle = "Every automation capability is strictly permission-gated",
            badgeText = "SECURITY",
            badgeColor = NeonMagenta,
            icon = Icons.Default.Security
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PermissionRow("Accessibility Service", isAccessibilityOn) {
                    MahiAccessibilityService.openAccessibilitySettings(context)
                }
                PermissionRow("Notification Listener", isNotificationsOn) {
                    MahiNotificationService.openNotificationListenerSettings(context)
                }
                PermissionRow("Modify System Settings", canWriteSettings) {
                    onOpenWriteSettings()
                }
                PermissionRow("DND Policy Access", volumeLevels.hasDndPolicyAccess) {
                    onOpenDndSettings()
                }
                PermissionRow("Microphone (RECORD_AUDIO)", hasMicPerm) {
                    runtimePermLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                }
                PermissionRow("Camera (CAMERA)", hasCameraPerm) {
                    runtimePermLauncher.launch(arrayOf(Manifest.permission.CAMERA))
                }
                PermissionRow("Phone (CALL / READ_STATE)", hasPhonePerm) {
                    runtimePermLauncher.launch(
                        arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.CALL_PHONE)
                    )
                }
                OutlinedButton(
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.parse("package:${context.packageName}")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Open Android App Info & Battery Settings", fontSize = 12.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun ControlCard(
    title: String,
    subtitle: String,
    badgeText: String,
    badgeColor: Color,
    icon: ImageVector,
    content: @Composable () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surface)
            .border(1.dp, colors.outline, RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Icon(icon, contentDescription = null, tint = badgeColor, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(title, color = colors.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(subtitle, color = colors.onSurfaceVariant, fontSize = 11.sp)
                }
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(badgeColor.copy(alpha = 0.16f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(badgeText, color = badgeColor, fontSize = 10.sp, fontWeight = FontWeight.Black)
            }
        }
        content()
    }
}

@Composable
private fun VolumeSliderRow(label: String, percent: Int, onChanged: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = colors.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text("$percent%", color = CyberCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = percent.toFloat(),
            onValueChange = { onChanged(it.toInt()) },
            valueRange = 0f..100f,
            colors = SliderDefaults.colors(thumbColor = NeonMagenta, activeTrackColor = NeonMagenta)
        )
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean, onConfigure: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = if (granted) CyberEmerald else WarningAmber,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(label, color = colors.onSurface, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    text = if (granted) "GRANTED / ON" else "OFF / REQUIRED",
                    color = if (granted) CyberEmerald else WarningAmber,
                    fontSize = 10.sp
                )
            }
        }
        OutlinedButton(onClick = onConfigure) {
            Text(if (granted) "Open" else "Enable", fontSize = 11.sp)
        }
    }
}
