package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.KnowledgeNoteEntity
import com.example.data.ReleaseRecordEntity
import com.example.marketplace.MarketplacePlugin
import com.example.pipeline.SandboxPatchProposal
import com.example.research.GitHubRepoCandidate
import com.example.research.HuggingFaceModelCandidate
import com.example.research.NearbyPlaceResult
import com.example.research.OfflineLlmInspection
import com.example.research.WebResearchResult
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.CyberEmerald
import com.example.ui.theme.WarningAmber

@Composable
fun DiscoveryAndResearchScreen(
    webResults: List<WebResearchResult>,
    gitHubRepos: List<GitHubRepoCandidate>,
    hfModels: List<HuggingFaceModelCandidate>,
    nearbyPlaces: List<NearbyPlaceResult>,
    offlineInspection: OfflineLlmInspection,
    knowledgeNotes: List<KnowledgeNoteEntity>,
    plugins: List<MarketplacePlugin>,
    patchProposals: List<SandboxPatchProposal>,
    releaseRecords: List<ReleaseRecordEntity>,
    statusMessage: String,
    onSearchWeb: (String) -> Unit,
    onSearchGitHub: (String) -> Unit,
    onSearchHuggingFace: (String) -> Unit,
    onSearchNearby: (String) -> Unit,
    onSaveNote: (String, String) -> Unit,
    onDeleteNote: (String) -> Unit,
    onExportKnowledge: () -> Unit,
    onTogglePlugin: (String) -> Unit,
    onTestPlugin: (String) -> Unit,
    onStageSandboxPatch: (String, String, String) -> Unit,
    onApproveSandboxPatch: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    var searchQuery by remember { mutableStateOf("") }
    var noteTitle by remember { mutableStateOf("") }
    var noteBody by remember { mutableStateOf("") }
    var patchTitle by remember { mutableStateOf("") }
    var patchFile by remember { mutableStateOf("helper_patch.kt") }
    var patchCode by remember { mutableStateOf("fun sampleCheck() = true") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("discovery_research_screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("RESEARCH • GITHUB • HUGGING FACE • SANDBOX", color = CyberCyan, fontSize = 11.sp, fontWeight = FontWeight.Black)
        Text("Knowledge, Discovery & Lab", color = colors.onBackground, fontSize = 17.sp, fontWeight = FontWeight.Bold)

        if (statusMessage.isNotBlank()) {
            Text(statusMessage, color = CyberEmerald, fontSize = 12.sp)
        }

        // Unified Web, GitHub, HuggingFace, Nearby Places Search
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(colors.surface)
                .border(1.dp, colors.outline, RoundedCornerShape(14.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Topic, GitHub query, HF model, or 'cafe'") },
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { onSearchWeb(searchQuery) }, modifier = Modifier.weight(1f)) { Text("Web/Wiki", fontSize = 10.sp) }
                OutlinedButton(onClick = { onSearchGitHub(searchQuery) }, modifier = Modifier.weight(1f)) { Text("GitHub", fontSize = 10.sp) }
                OutlinedButton(onClick = { onSearchHuggingFace(searchQuery) }, modifier = Modifier.weight(1f)) { Text("HF Models", fontSize = 10.sp) }
                OutlinedButton(onClick = { onSearchNearby(searchQuery) }, modifier = Modifier.weight(1f)) { Text("Nearby", fontSize = 10.sp) }
            }

            webResults.forEach { r ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.surfaceVariant)
                        .clickable {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.sourceUrl))) }
                        }
                        .padding(10.dp)
                ) {
                    Text("${r.title} (${r.sourceName})", color = CyberCyan, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text(r.summary.take(240), color = colors.onSurface, fontSize = 11.sp)
                    Text(r.sourceUrl, color = colors.onSurfaceVariant, fontSize = 10.sp)
                }
            }
            gitHubRepos.forEach { repo ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.surfaceVariant)
                        .clickable {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(repo.htmlUrl))) }
                        }
                        .padding(10.dp)
                ) {
                    Text("${repo.fullName} (★${repo.stars} • ${repo.licenseName})", color = CyberCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(repo.description.take(150), color = colors.onSurface, fontSize = 11.sp)
                }
            }
            hfModels.forEach { m ->
                Text("HF: ${m.modelId} • ${m.compatibilityNote}", color = colors.onSurface, fontSize = 11.sp)
            }
            nearbyPlaces.forEach { p ->
                Text("Place: ${p.name} (${p.category}) @ ${p.latitude},${p.longitude}", color = colors.onSurface, fontSize = 11.sp)
            }
        }

        // Offline LLM Device Readiness Inspector
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(colors.surface)
                .border(1.dp, WarningAmber.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("Offline LLM Hardware Audit", color = CyberCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text("RAM: ${String.format("%.1f", offlineInspection.totalRamGb)} GB • Free Storage: ${String.format("%.1f", offlineInspection.availableStorageGb)} GB", color = colors.onSurface, fontSize = 11.sp)
            Text("Recommended Tier: ${offlineInspection.recommendedTier}", color = CyberCyan, fontSize = 11.sp)
            Text("Runtime Status: ${offlineInspection.runtimeStatus}", color = WarningAmber, fontSize = 11.sp)
        }

        // Knowledge Base & Encrypted Backup
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(colors.surface)
                .border(1.dp, colors.outline, RoundedCornerShape(14.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Personal Knowledge Base (${knowledgeNotes.size})", color = CyberCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                OutlinedButton(onClick = onExportKnowledge) { Text("Export JSON", fontSize = 10.sp) }
            }
            OutlinedTextField(value = noteTitle, onValueChange = { noteTitle = it }, placeholder = { Text("Note title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = noteBody, onValueChange = { noteBody = it }, placeholder = { Text("Note content") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = {
                if (noteBody.isNotBlank()) {
                    onSaveNote(noteTitle, noteBody)
                    noteTitle = ""
                    noteBody = ""
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("Save Knowledge Note") }

            knowledgeNotes.take(5).forEach { note ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("• ${note.title}: ${note.content.take(80)}", color = colors.onSurface, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { onDeleteNote(note.id) }) { Text("Del", fontSize = 10.sp) }
                }
            }
        }

        // Verified Internal Plugin Marketplace (Zero DCL)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(colors.surface)
                .border(1.dp, colors.outline, RoundedCornerShape(14.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Verified Tool Marketplace (${plugins.size})", color = CyberCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            plugins.forEach { p ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.surfaceVariant)
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("${p.name} v${p.version} (${p.license})", color = colors.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(p.lastTestStatus, color = CyberEmerald, fontSize = 10.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onTogglePlugin(p.id) }) { Text(if (p.isEnabled) "Disable" else "Enable", fontSize = 10.sp) }
                        OutlinedButton(onClick = { onTestPlugin(p.id) }) { Text("Run Security Self-Test", fontSize = 10.sp) }
                    }
                }
            }
        }

        // Isolated Self-Coding Sandbox & Release Ledger
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(colors.surface)
                .border(1.dp, colors.outline, RoundedCornerShape(14.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Sandbox Patch & Release Pipeline", color = CyberCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            OutlinedTextField(value = patchTitle, onValueChange = { patchTitle = it }, placeholder = { Text("Proposal title") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = patchFile, onValueChange = { patchFile = it }, placeholder = { Text("Sandbox filename") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = patchCode, onValueChange = { patchCode = it }, placeholder = { Text("Snippet to scan in filesDir/sandbox_patches") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { onStageSandboxPatch(patchTitle, patchFile, patchCode) }, modifier = Modifier.fillMaxWidth()) {
                Text("Stage & Security Scan in Sandbox")
            }
            patchProposals.take(4).forEach { prop ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("${prop.title} [${prop.status}]", color = colors.onSurface, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        Text("SHA-256: ${prop.sha256.take(16)}… Findings: ${prop.securityFindings.size}", color = colors.onSurfaceVariant, fontSize = 10.sp)
                    }
                    if (prop.status == "STAGED_AWAITING_APPROVAL") {
                        OutlinedButton(onClick = { onApproveSandboxPatch(prop.id) }) { Text("Approve", fontSize = 10.sp) }
                    }
                }
            }
            releaseRecords.take(3).forEach { rel ->
                Text("Release ${rel.versionName} (${rel.branchName}) • ${rel.sha256Checksum.take(14)}…", color = CyberCyan, fontSize = 10.sp)
            }
        }
    }
}
