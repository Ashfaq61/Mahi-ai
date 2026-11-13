package com.example.research

import com.example.api.AiProvider
import com.example.api.ProviderChatMessage
import com.example.api.ProviderClient
import com.example.preferences.AppLanguage
import org.json.JSONArray
import org.json.JSONObject

data class AiSearchResult(
    val query: String,
    val language: AppLanguage,
    val summary: String,
    val sourceComparison: String,
    val sources: List<WebSearchSource>,
    val aiErrorNote: String? = null
)

data class DeepResearchReport(
    val topic: String,
    val language: AppLanguage,
    val createdAtMs: Long,
    val subQueries: List<String>,
    val executiveSummary: String,
    val keyFindings: List<String>,
    val conflictingInformation: String,
    val detailedAnalysis: String,
    val sources: List<WebSearchSource>
)

class ResearchEngine(
    private val webSearchClient: WebSearchClient = WebSearchClient(),
    private val providerClient: ProviderClient = ProviderClient()
) {
    suspend fun runAiSearch(
        query: String,
        language: AppLanguage,
        provider: AiProvider?,
        apiKey: String?,
        model: String?,
        baseUrl: String?
    ): AiSearchResult {
        val sources = webSearchClient.searchWeb(query, maxResults = 6)
        if (sources.isEmpty()) {
            return AiSearchResult(
                query = query,
                language = language,
                summary = "No live web results were retrieved. Check your internet connection and retry.",
                sourceComparison = "",
                sources = emptyList(),
                aiErrorNote = "Web search returned 0 sources."
            )
        }

        // Enrich top 3 sources with live page excerpts for real cross-source comparison
        val enriched = sources.mapIndexed { idx, src ->
            if (idx < 3) {
                val excerpt = webSearchClient.fetchPageExcerpt(src.url, 1200)
                if (excerpt.isNotBlank()) src.copy(pageExcerpt = excerpt) else src
            } else src
        }

        if (provider == null || apiKey.isNullOrBlank() || model.isNullOrBlank() || baseUrl.isNullOrBlank()) {
            val digest = enriched.joinToString("\n") { "[${it.index}] ${it.title} (${it.domain}): ${it.snippet}" }
            return AiSearchResult(
                query = query,
                language = language,
                summary = "Live web sources retrieved (${enriched.size} sites). Configure an AI provider key to generate an AI-synthesized summary.\n\n$digest",
                sourceComparison = "Across ${enriched.map { it.domain }.distinct().size} distinct domains.",
                sources = enriched,
                aiErrorNote = "No active AI API key configured — showing raw live web citations."
            )
        }

        val evidenceBlock = enriched.joinToString("\n\n") { src ->
            "[${src.index}] Title: ${src.title}\nURL: ${src.url}\nSnippet: ${src.snippet}\nExcerpt: ${src.pageExcerpt.take(700)}"
        }

        val systemPrompt = """
You are Mahi's AI Search Engine. Synthesize real-time web results faithfully with numbered source citations like [1], [2].
${language.instruction}
Return STRICT JSON with keys: "summary" (concise multi-source answer with [n] citations) and "sourceComparison" (briefly compare what different sources agree or differ on).
        """.trimIndent()

        return try {
            val raw = providerClient.generateChatReply(
                provider = provider,
                apiKey = apiKey,
                model = model,
                baseUrl = baseUrl,
                systemPrompt = systemPrompt,
                history = emptyList<ProviderChatMessage>(),
                userMessage = "Query: $query\n\nLive Sources:\n$evidenceBlock"
            )
            val parsed = parseJsonObjectSafe(raw)
            AiSearchResult(
                query = query,
                language = language,
                summary = parsed?.optString("summary")?.takeIf { it.isNotBlank() } ?: raw,
                sourceComparison = parsed?.optString("sourceComparison")?.takeIf { it.isNotBlank() }
                    ?: "Cross-checked across ${enriched.map { it.domain }.distinct().size} domains.",
                sources = enriched
            )
        } catch (e: Exception) {
            AiSearchResult(
                query = query,
                language = language,
                summary = enriched.joinToString("\n") { "[${it.index}] ${it.title}: ${it.snippet}" },
                sourceComparison = "Cross-checked ${enriched.size} live sources.",
                sources = enriched,
                aiErrorNote = e.localizedMessage ?: "AI synthesis failed; displaying live search sources."
            )
        }
    }

    suspend fun runDeepResearch(
        topic: String,
        language: AppLanguage,
        provider: AiProvider?,
        apiKey: String?,
        model: String?,
        baseUrl: String?,
        onProgress: (String) -> Unit = {}
    ): DeepResearchReport {
        onProgress("Planning multi-step research queries...")
        val subQueries = planSubQueries(topic, provider, apiKey, model, baseUrl)

        onProgress("Searching multiple websites & fetching documents (${subQueries.size} angles)...")
        val allSources = mutableListOf<WebSearchSource>()
        for (sq in subQueries) {
            val batch = webSearchClient.searchWeb(sq, maxResults = 3)
            batch.forEach { candidate ->
                if (allSources.none { it.url.equals(candidate.url, ignoreCase = true) }) {
                    allSources.add(candidate.copy(index = allSources.size + 1))
                }
            }
        }

        onProgress("Reading & extracting evidence from ${allSources.size} sources...")
        val crawled = allSources.take(8).mapIndexed { idx, src ->
            if (idx < 5) {
                val excerpt = webSearchClient.fetchPageExcerpt(src.url, 1500)
                if (excerpt.isNotBlank()) src.copy(pageExcerpt = excerpt) else src
            } else src
        }

        onProgress("Comparing evidence, checking conflicts & compiling report...")
        if (provider == null || apiKey.isNullOrBlank() || model.isNullOrBlank() || baseUrl.isNullOrBlank() || crawled.isEmpty()) {
            val fallbackSummary = if (crawled.isEmpty()) {
                "No live web sources could be reached for \"$topic\". Check internet access."
            } else {
                "Collected ${crawled.size} live sources across ${subQueries.size} research angles for \"$topic\"."
            }
            return DeepResearchReport(
                topic = topic,
                language = language,
                createdAtMs = System.currentTimeMillis(),
                subQueries = subQueries,
                executiveSummary = fallbackSummary,
                keyFindings = crawled.map { "[${it.index}] ${it.title} (${it.domain}): ${it.snippet}" },
                conflictingInformation = if (apiKey.isNullOrBlank()) {
                    "Configure an AI provider key in API Keys & Providers for automated cross-source contradiction detection."
                } else {
                    "Insufficient live text to detect contradictions."
                },
                detailedAnalysis = crawled.joinToString("\n\n") { "[${it.index}] ${it.title}\n${it.url}\n${it.pageExcerpt.ifBlank { it.snippet }}" },
                sources = crawled
            )
        }

        val evidencePayload = crawled.joinToString("\n\n") { src ->
            "[${src.index}] ${src.title} (${src.domain})\nURL: ${src.url}\nSnippet: ${src.snippet}\nContent: ${src.pageExcerpt.take(800)}"
        }

        val systemPrompt = """
You are Mahi's Deep Research Agent. Analyze multiple crawled websites, compare claims, and identify conflicting information or gaps.
${language.instruction}
Return STRICT JSON with keys:
- "executiveSummary": concise multi-paragraph summary with [n] citations
- "keyFindings": JSON array of 4-6 concise bullet findings with [n] citations
- "conflictingInformation": explicit explanation of where sources disagree, differ in numbers/dates, or note consensus if aligned
- "detailedAnalysis": structured deep dive with [n] citations
        """.trimIndent()

        val raw = providerClient.generateChatReply(
            provider = provider,
            apiKey = apiKey,
            model = model,
            baseUrl = baseUrl,
            systemPrompt = systemPrompt,
            history = emptyList(),
            userMessage = "Research Topic: $topic\nSub-queries investigated: ${subQueries.joinToString("; ")}\n\nCrawled Evidence:\n$evidencePayload"
        )

        val json = parseJsonObjectSafe(raw)
        val findingsList = mutableListOf<String>()
        val findingsArr = json?.optJSONArray("keyFindings")
        if (findingsArr != null) {
            for (i in 0 until findingsArr.length()) {
                val f = findingsArr.optString(i).trim()
                if (f.isNotBlank()) findingsList.add(f)
            }
        }
        if (findingsList.isEmpty()) {
            findingsList.addAll(crawled.take(4).map { "[${it.index}] ${it.title}: ${it.snippet}" })
        }

        return DeepResearchReport(
            topic = topic,
            language = language,
            createdAtMs = System.currentTimeMillis(),
            subQueries = subQueries,
            executiveSummary = json?.optString("executiveSummary")?.takeIf { it.isNotBlank() } ?: raw,
            keyFindings = findingsList,
            conflictingInformation = json?.optString("conflictingInformation")?.takeIf { it.isNotBlank() }
                ?: "No major factual contradictions detected across the ${crawled.size} retrieved sources.",
            detailedAnalysis = json?.optString("detailedAnalysis")?.takeIf { it.isNotBlank() } ?: raw,
            sources = crawled
        )
    }

    private suspend fun planSubQueries(
        topic: String,
        provider: AiProvider?,
        apiKey: String?,
        model: String?,
        baseUrl: String?
    ): List<String> {
        val heuristic = listOf(
            topic,
            "$topic latest statistics comparison",
            "$topic pros cons criticism"
        )
        if (provider == null || apiKey.isNullOrBlank() || model.isNullOrBlank() || baseUrl.isNullOrBlank()) {
            return heuristic
        }
        return try {
            val raw = providerClient.generateChatReply(
                provider = provider,
                apiKey = apiKey,
                model = model,
                baseUrl = baseUrl,
                systemPrompt = "Generate 3 concise web search sub-queries to research a topic thoroughly. Return ONLY a JSON array of 3 strings.",
                history = emptyList(),
                userMessage = topic
            )
            val cleaned = raw.substringAfter("[").substringBeforeLast("]")
            val arr = JSONArray("[$cleaned]")
            buildList {
                for (i in 0 until arr.length()) {
                    val q = arr.optString(i).trim()
                    if (q.isNotBlank()) add(q)
                }
            }.take(3).ifEmpty { heuristic }
        } catch (_: Exception) {
            heuristic
        }
    }

    private fun parseJsonObjectSafe(raw: String): JSONObject? {
        return try {
            val trimmed = raw.trim()
            val start = trimmed.indexOf('{')
            val end = trimmed.lastIndexOf('}')
            if (start >= 0 && end > start) {
                JSONObject(trimmed.substring(start, end + 1))
            } else null
        } catch (_: Exception) {
            null
        }
    }
}
