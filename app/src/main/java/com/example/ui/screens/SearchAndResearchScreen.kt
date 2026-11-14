package com.example.ui.screens

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Biotech
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.preferences.AppLanguage
import com.example.research.AiSearchResult
import com.example.research.DeepResearchReport
import com.example.research.ExportedPdfResult
import com.example.research.ResearchPdfExporter
import com.example.research.WebSearchSource
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.CyberEmerald
import com.example.ui.theme.NeonMagenta
import com.example.ui.theme.NeonViolet
import com.example.ui.theme.WarningAmber

@Composable
fun SearchAndResearchScreen(
    selectedLanguage: AppLanguage,
    isLoading: Boolean,
    progressMessage: String,
    searchResult: AiSearchResult?,
    deepReport: DeepResearchReport?,
    exportedPdf: ExportedPdfResult?,
    onLanguageSelected: (AppLanguage) -> Unit,
    onRunAiSearch: (String) -> Unit,
    onRunDeepResearch: (String) -> Unit,
    onExportPdf: (DeepResearchReport) -> Unit,
    onSpeakSummary: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    var isDeepMode by remember { mutableStateOf(false) }
    var queryInput by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("search_research_screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header + Language Chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = if (isDeepMode) "DEEP RESEARCH AGENT" else "AI SEARCH ENGINE",
                    color = CyberCyan,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp
                )
                Text(
                    text = if (isDeepMode) "Multi-Step Synthesis & PDF" else "Real-Time Cited Search",
                    color = colors.onBackground,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                AppLanguage.entries.forEach { lang ->
                    val selected = lang == selectedLanguage
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) NeonMagenta else colors.surfaceVariant)
                            .clickable { onLanguageSelected(lang) }
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                            .testTag("research_lang_${lang.id}")
                    ) {
                        Text(
                            text = lang.shortBadge,
                            color = if (selected) Color.White else colors.onSurfaceVariant,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Mode Switcher: AI Search vs Deep Research
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(colors.surfaceVariant)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (!isDeepMode) NeonMagenta else Color.Transparent)
                    .clickable { isDeepMode = false }
                    .padding(vertical = 10.dp)
                    .testTag("mode_ai_search"),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = if (!isDeepMode) Color.White else colors.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "AI Search",
                        color = if (!isDeepMode) Color.White else colors.onSurfaceVariant,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isDeepMode) NeonViolet else Color.Transparent)
                    .clickable { isDeepMode = true }
                    .padding(vertical = 10.dp)
                    .testTag("mode_deep_research"),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Biotech,
                        contentDescription = null,
                        tint = if (isDeepMode) Color.White else colors.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Deep Research",
                        color = if (isDeepMode) Color.White else colors.onSurfaceVariant,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Input + Launch Action
        OutlinedTextField(
            value = queryInput,
            onValueChange = { queryInput = it },
            label = {
                Text(
                    if (isDeepMode) "Research Topic" else "Search Anything in Real Time",
                    fontSize = 12.sp
                )
            },
            placeholder = {
                Text(
                    if (isDeepMode) "e.g., Solid-state battery breakthroughs 2026"
                    else "e.g., Best budget smartphones under 50k PKR",
                    fontSize = 12.sp
                )
            },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("research_query_input")
        )

        Button(
            onClick = {
                val q = queryInput.trim()
                if (q.isNotEmpty()) {
                    if (isDeepMode) onRunDeepResearch(q) else onRunAiSearch(q)
                }
            },
            enabled = !isLoading && queryInput.isNotBlank(),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isDeepMode) NeonViolet else NeonMagenta
            ),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .minimumInteractiveComponentSize()
                .testTag("run_search_research_button")
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(progressMessage.ifBlank { "Working..." }, fontSize = 13.sp)
            } else {
                Text(
                    text = if (isDeepMode) "Launch Deep Research Agent" else "Run Live AI Search",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // AI Search Output
        if (!isDeepMode && searchResult != null) {
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
                    Text(
                        text = "AI Search Summary (${searchResult.language.displayName})",
                        color = CyberCyan,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(
                        onClick = { onSpeakSummary(searchResult.summary) },
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = "Speak Search Summary",
                            tint = NeonMagenta
                        )
                    }
                }
                Text(
                    text = searchResult.summary,
                    color = colors.onSurface,
                    fontSize = 13.sp,
                    modifier = Modifier.testTag("ai_search_summary_text")
                )
                if (searchResult.sourceComparison.isNotBlank()) {
                    Text(
                        text = "Cross-Source Check: ${searchResult.sourceComparison}",
                        color = CyberEmerald,
                        fontSize = 12.sp
                    )
                }
                if (!searchResult.aiErrorNote.isNullOrBlank()) {
                    Text(
                        text = searchResult.aiErrorNote,
                        color = WarningAmber,
                        fontSize = 11.sp
                    )
                }
                SourcesListSection(searchResult.sources) { url ->
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: Exception) {
                    }
                }
            }
        }

        // Deep Research Report Output + PDF Export
        if (isDeepMode && deepReport != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.surface)
                    .border(1.dp, NeonViolet.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Research Report: ${deepReport.topic}",
                        color = colors.onSurface,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { onSpeakSummary(deepReport.executiveSummary) },
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = "Speak Report Summary",
                            tint = NeonMagenta
                        )
                    }
                }

                // PDF Export Controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { onExportPdf(deepReport) },
                        colors = ButtonDefaults.buttonColors(containerColor = CyberEmerald),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .testTag("export_research_pdf_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.PictureAsPdf,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Export PDF", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    if (exportedPdf != null) {
                        OutlinedButton(
                            onClick = { ResearchPdfExporter.openOrSharePdf(context, exportedPdf.contentUri) },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("open_exported_pdf_button")
                        ) {
                            Text("Open / Share PDF", fontSize = 12.sp)
                        }
                    }
                }

                if (exportedPdf != null) {
                    Text(
                        text = "PDF saved: ${exportedPdf.file.name} (${exportedPdf.byteSize} bytes)",
                        color = CyberEmerald,
                        fontSize = 11.sp
                    )
                }

                Text(
                    text = "Executive Summary",
                    color = CyberCyan,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(text = deepReport.executiveSummary, color = colors.onSurface, fontSize = 13.sp)

                Text(
                    text = "Key Findings",
                    color = CyberCyan,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                deepReport.keyFindings.forEach { finding ->
                    Text(text = "• $finding", color = colors.onSurface, fontSize = 12.sp)
                }

                // Conflicting Evidence Box
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(WarningAmber.copy(alpha = 0.12f))
                        .border(1.dp, WarningAmber.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                        .padding(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.WarningAmber,
                            contentDescription = null,
                            tint = WarningAmber,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Conflicting Information & Verification",
                            color = WarningAmber,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = deepReport.conflictingInformation, color = colors.onSurface, fontSize = 12.sp)
                }

                SourcesListSection(deepReport.sources) { url ->
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }
}

@Composable
private fun SourcesListSection(
    sources: List<WebSearchSource>,
    onOpenUrl: (String) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    if (sources.isEmpty()) return

    Text(
        text = "Verified Sources (${sources.size})",
        color = CyberCyan,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold
    )

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        sources.forEach { src ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.surfaceVariant)
                    .clickable { onOpenUrl(src.url) }
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "[${src.index}] ${src.title}",
                        color = colors.onSurface,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = src.domain,
                        color = CyberCyan,
                        fontSize = 10.sp
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = "Open Source",
                    tint = NeonMagenta,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
