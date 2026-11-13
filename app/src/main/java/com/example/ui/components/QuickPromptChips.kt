package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun QuickPromptChips(
    onPromptSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val prompts = listOf(
        "Tease me 😏",
        "Open Spotify 🎵",
        "Open YouTube ▶️",
        "Tell me a secret 🤫",
        "Turn on flashlight 🔦",
        "Set a 5 minute timer ⏰",
        "Who is your favorite? 👑",
        "Roast me 🔥",
        "Search cosmic astronomy 🔭"
    )

    val scrollState = rememberScrollState()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        prompts.forEach { prompt ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(colors.surfaceVariant)
                    .border(1.dp, colors.outline, RoundedCornerShape(18.dp))
                    .clickable { onPromptSelected(prompt) }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
                    .testTag("prompt_chip_${prompt.take(6).trim()}")
            ) {
                Text(
                    text = prompt,
                    color = colors.onSurface,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
