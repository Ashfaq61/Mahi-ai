package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.CyberEmerald
import com.example.ui.theme.NeonMagenta
import com.example.ui.theme.NeonViolet

data class HomeQuickControlItem(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val tint: Color,
    val tag: String
)

@Composable
fun HomeQuickControlsBar(
    onOpenFacebook: () -> Unit,
    onOpenTikTok: () -> Unit,
    onOpenYouTube: () -> Unit,
    onOpenGoogle: () -> Unit,
    onOpenCamera: () -> Unit,
    onTakeSelfie: () -> Unit,
    onStartVideo: () -> Unit,
    onOpenChrome: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val items = listOf(
        HomeQuickControlItem("facebook", "Facebook", Icons.Default.Public, Color(0xFF3B82F6), "quick_btn_facebook"),
        HomeQuickControlItem("tiktok", "TikTok", Icons.Default.Movie, NeonMagenta, "quick_btn_tiktok"),
        HomeQuickControlItem("youtube", "YouTube", Icons.Default.PlayCircleFilled, Color(0xFFEF4444), "quick_btn_youtube"),
        HomeQuickControlItem("google", "Google", Icons.Default.Search, CyberEmerald, "quick_btn_google"),
        HomeQuickControlItem("camera", "Camera", Icons.Default.CameraAlt, CyberCyan, "quick_btn_camera"),
        HomeQuickControlItem("selfie", "Selfie", Icons.Default.Face, NeonViolet, "quick_btn_selfie"),
        HomeQuickControlItem("video", "Video", Icons.Default.Videocam, Color(0xFFF59E0B), "quick_btn_video"),
        HomeQuickControlItem("chrome", "Chrome", Icons.Default.Language, CyberCyan, "quick_btn_chrome")
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .testTag("home_quick_controls_bar"),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items.forEach { item ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.surface.copy(alpha = 0.92f))
                    .border(1.dp, item.tint.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                    .clickable {
                        when (item.id) {
                            "facebook" -> onOpenFacebook()
                            "tiktok" -> onOpenTikTok()
                            "youtube" -> onOpenYouTube()
                            "google" -> onOpenGoogle()
                            "camera" -> onOpenCamera()
                            "selfie" -> onTakeSelfie()
                            "video" -> onStartVideo()
                            "chrome" -> onOpenChrome()
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .testTag(item.tag)
            ) {
                Icon(
                    imageVector = item.icon,
                    contentDescription = item.label,
                    tint = item.tint,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = item.label,
                    color = colors.onSurface,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
