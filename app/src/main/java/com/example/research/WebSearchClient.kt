package com.example.research

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

data class WebSearchSource(
    val index: Int,
    val title: String,
    val url: String,
    val domain: String,
    val snippet: String,
    val pageExcerpt: String = ""
)

class WebSearchClient(
    private val okHttpClient: OkHttpClient = defaultSearchClient()
) {
    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

        fun defaultSearchClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(18, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        fun extractDomain(url: String): String {
            return try {
                URI(url).host?.removePrefix("www.").orEmpty().ifBlank { "web" }
            } catch (_: Exception) {
                "web"
            }
        }

        fun stripHtmlTags(raw: String): String {
            return raw
                .replace(Regex("<script[^>]*>[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("<style[^>]*>[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("<[^>]+>"), " ")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&nbsp;", " ")
                .replace(Regex("\\s+"), " ")
                .trim()
        }

        fun parseDuckDuckGoLiteHtml(html: String, maxResults: Int = 6): List<WebSearchSource> {
            val results = mutableListOf<WebSearchSource>()
            // Match DuckDuckGo HTML result anchors & snippets
            val linkRegex = Pattern.compile(
                "<a[^>]*class=\"[^\"]*result__a[^\"]*\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>",
                Pattern.CASE_INSENSITIVE or Pattern.DOTALL
            )
            val snippetRegex = Pattern.compile(
                "<a[^>]*class=\"[^\"]*result__snippet[^\"]*\"[^>]*>(.*?)</a>|<div[^>]*class=\"[^\"]*result__snippet[^\"]*\"[^>]*>(.*?)</div>",
                Pattern.CASE_INSENSITIVE or Pattern.DOTALL
            )

            val linkMatcher = linkRegex.matcher(html)
            val snippetMatcher = snippetRegex.matcher(html)
            val snippets = mutableListOf<String>()
            while (snippetMatcher.find()) {
                val raw = snippetMatcher.group(1) ?: snippetMatcher.group(2) ?: ""
                snippets.add(stripHtmlTags(raw))
            }

            var idx = 0
            while (linkMatcher.find() && results.size < maxResults) {
                val rawHref = linkMatcher.group(1).orEmpty()
                val title = stripHtmlTags(linkMatcher.group(2).orEmpty())
                val resolvedUrl = resolveDuckDuckGoRedirect(rawHref)
                if (resolvedUrl.startsWith("http") && title.isNotBlank()) {
                    val snippet = snippets.getOrElse(idx) { "" }
                    results.add(
                        WebSearchSource(
                            index = results.size + 1,
                            title = title,
                            url = resolvedUrl,
                            domain = extractDomain(resolvedUrl),
                            snippet = snippet
                        )
                    )
                }
                idx++
            }
            return results
        }

        private fun resolveDuckDuckGoRedirect(rawHref: String): String {
            if (!rawHref.contains("uddg=")) {
                return if (rawHref.startsWith("//")) "https:$rawHref" else rawHref
            }
            return try {
                val encoded = rawHref.substringAfter("uddg=").substringBefore("&")
                java.net.URLDecoder.decode(encoded, "UTF-8")
            } catch (_: Exception) {
                rawHref
            }
        }
    }

    suspend fun searchWeb(query: String, maxResults: Int = 6): List<WebSearchSource> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext emptyList()

        val combined = mutableListOf<WebSearchSource>()
        val encoded = URLEncoder.encode(trimmed, "UTF-8")

        // 1. Primary: DuckDuckGo HTML Lite
        try {
            val ddgReq = Request.Builder()
                .url("https://html.duckduckgo.com/html/?q=$encoded")
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
            okHttpClient.newCall(ddgReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    combined.addAll(parseDuckDuckGoLiteHtml(body, maxResults))
                }
            }
        } catch (_: Exception) {
        }

        // 2. Secondary/Cross-source: Wikipedia Search API
        if (combined.size < maxResults) {
            try {
                val wikiReq = Request.Builder()
                    .url("https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=$encoded&format=json&srlimit=4")
                    .header("User-Agent", USER_AGENT)
                    .get()
                    .build()
                okHttpClient.newCall(wikiReq).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val json = JSONObject(resp.body?.string().orEmpty())
                        val arr = json.optJSONObject("query")?.optJSONArray("search")
                        if (arr != null) {
                            for (i in 0 until arr.length()) {
                                val item = arr.optJSONObject(i) ?: continue
                                val title = item.optString("title")
                                val snippet = stripHtmlTags(item.optString("snippet"))
                                val pageUrl = "https://en.wikipedia.org/wiki/" + URLEncoder.encode(title.replace(" ", "_"), "UTF-8")
                                if (combined.none { it.url.equals(pageUrl, ignoreCase = true) }) {
                                    combined.add(
                                        WebSearchSource(
                                            index = combined.size + 1,
                                            title = "$title — Wikipedia",
                                            url = pageUrl,
                                            domain = "en.wikipedia.org",
                                            snippet = snippet
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        combined.take(maxResults).mapIndexed { index, source ->
            source.copy(index = index + 1)
        }
    }

    suspend fun fetchPageExcerpt(url: String, maxChars: Int = 1800): String = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext ""
                val contentType = resp.header("Content-Type").orEmpty()
                if (!contentType.contains("text", ignoreCase = true) && contentType.isNotBlank()) {
                    return@withContext ""
                }
                val html = resp.body?.string().orEmpty().take(90_000)
                stripHtmlTags(html).take(maxChars)
            }
        } catch (_: Exception) {
            ""
        }
    }
}
