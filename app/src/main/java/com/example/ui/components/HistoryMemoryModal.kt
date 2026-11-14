package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.memory.ConversationThread
import com.example.memory.UserMemoryProfile
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.NeonMagenta
import com.example.ui.theme.NeonViolet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryMemoryModal(
    threads: List<ConversationThread>,
    activeThreadId: String?,
    memoryConsentGranted: Boolean,
    memoryProfile: UserMemoryProfile,
    onSelectThread: (String) -> Unit,
    onStartNewThread: () -> Unit,
    onDeleteThread: (String) -> Unit,
    onClearAllHistory: () -> Unit,
    onSetMemoryConsent: (Boolean) -> Unit,
    onUpdateProfileFields: (String, String, String) -> Unit,
    onAddOrEditFact: (String, String, String?) -> Unit,
    onDeleteFact: (String) -> Unit,
    onForgetEverything: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val colors = MaterialTheme.colorScheme
    var selectedSection by remember { mutableIntStateOf(0) } // 0 = History, 1 = Memory

    var nameInput by remember(memoryProfile.userName) { mutableStateOf(memoryProfile.userName) }
    var langNoteInput by remember(memoryProfile.preferredLanguageNote) { mutableStateOf(memoryProfile.preferredLanguageNote) }
    var toneInput by remember(memoryProfile.tonePreference) { mutableStateOf(memoryProfile.tonePreference) }
    var factCategory by remember { mutableStateOf("Preference") }
    var factContent by remember { mutableStateOf("") }
    var editingFactId by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surface,
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("BRAIN & MEMORY", color = CyberCyan, fontSize = 11.sp, fontWeight = FontWeight.Black)
                    Text("History & Personal Memory", color = colors.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = colors.onSurfaceVariant)
                }
            }

            // Section tabs
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.surfaceVariant)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf("Conversations (${threads.size})", "User Memory (${memoryProfile.facts.size})").forEachIndexed { idx, label ->
                    val sel = selectedSection == idx
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (sel) NeonMagenta else Color.Transparent)
                            .clickable { selectedSection = idx }
                            .padding(vertical = 8.dp)
                            .testTag("history_memory_tab_$idx"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(label, color = if (sel) Color.White else colors.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            if (selectedSection == 0) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onStartNewThread,
                        colors = ButtonDefaults.buttonColors(containerColor = NeonMagenta),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("New Chat", fontSize = 12.sp)
                    }
                    OutlinedButton(onClick = onClearAllHistory, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Clear All", fontSize = 12.sp)
                    }
                }

                if (threads.isEmpty()) {
                    Text(
                        text = "No saved conversations yet. Talk or chat with Mahi and threads will appear here.",
                        color = colors.onSurfaceVariant,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                } else {
                    threads.forEach { thread ->
                        val isActive = thread.id == activeThreadId
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(colors.surfaceVariant)
                                .border(1.dp, if (isActive) CyberCyan else colors.outline, RoundedCornerShape(14.dp))
                                .clickable {
                                    onSelectThread(thread.id)
                                    onDismiss()
                                }
                                .padding(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                    Icon(Icons.Default.History, contentDescription = null, tint = CyberCyan, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(thread.title, color = colors.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                }
                                IconButton(onClick = { onDeleteThread(thread.id) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete thread", tint = Color(0xFFEF4444), modifier = Modifier.size(18.dp))
                                }
                            }
                            thread.turns.takeLast(2).forEach { turn ->
                                Text(
                                    text = "${if (turn.role == "user") "You" else "Mahi"}: ${turn.text.take(90)}",
                                    color = colors.onSurfaceVariant,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
            } else {
                // Memory Consent Gate
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.surfaceVariant)
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Consent-Gated Long-Term Memory", color = colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text(
                            text = if (memoryConsentGranted) "Enabled: Mahi remembers your profile & preferences locally." else "OFF by default: Turn on to allow personal memory.",
                            color = colors.onSurfaceVariant,
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = memoryConsentGranted,
                        onCheckedChange = onSetMemoryConsent,
                        colors = SwitchDefaults.colors(checkedTrackColor = CyberCyan),
                        modifier = Modifier.testTag("memory_consent_switch")
                    )
                }

                if (memoryConsentGranted) {
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        label = { Text("Your Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = langNoteInput,
                        onValueChange = { langNoteInput = it },
                        label = { Text("Preferred Language Note (e.g. Roman Urdu)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = toneInput,
                        onValueChange = { toneInput = it },
                        label = { Text("Tone / Preferences") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = { onUpdateProfileFields(nameInput, langNoteInput, toneInput) },
                        colors = ButtonDefaults.buttonColors(containerColor = NeonViolet),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Save Profile Memory", fontSize = 12.sp)
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = factContent,
                            onValueChange = { factContent = it },
                            placeholder = { Text(if (editingFactId != null) "Edit memory note…" else "Add memory fact (e.g. Works night shift)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                if (factContent.isNotBlank()) {
                                    onAddOrEditFact(factCategory, factContent, editingFactId)
                                    factContent = ""
                                    editingFactId = null
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = NeonMagenta)
                        ) {
                            Text(if (editingFactId != null) "Save" else "Add", fontSize = 12.sp)
                        }
                    }

                    memoryProfile.facts.forEach { fact ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(colors.surfaceVariant)
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.Psychology, contentDescription = null, tint = CyberCyan, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("${fact.category}: ${fact.content}", color = colors.onSurface, fontSize = 12.sp)
                            }
                            Row {
                                IconButton(onClick = {
                                    editingFactId = fact.id
                                    factCategory = fact.category
                                    factContent = fact.content
                                }) {
                                    Icon(Icons.Default.Edit, contentDescription = "Edit fact", tint = CyberCyan, modifier = Modifier.size(16.dp))
                                }
                                IconButton(onClick = { onDeleteFact(fact.id) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete fact", tint = Color(0xFFEF4444), modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }

                Button(
                    onClick = onForgetEverything,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("memory_forget_everything_button")
                ) {
                    Text("Forget Everything", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
