package com.example.research

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Environment
import android.os.StatFs
import androidx.core.content.ContextCompat
import com.example.data.KnowledgeNoteEntity
import com.example.data.MahiDataRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit

data class WebResearchResult(
    val title: String,
    val summary: String,
    val sourceUrl: String,
    val sourceName: String
)

data class GitHubRepoCandidate(
    val fullName: String,
    val description: String,
    val stars: Int,
    val licenseName: String,
    val htmlUrl: String
)

data class HuggingFaceModelCandidate(
    val modelId: String,
    val downloads: Long,
    val likes: Int,
    val pipelineTag: String,
    val compatibilityNote: String
)

data class NearbyPlaceResult(
    val name: String,
    val category: String,
    val latitude: Double,
    val longitude: Double
)

data class OfflineLlmInspection(
    val totalRamGb: Float,
    val availableStorageGb: Float,
    val recommendedTier: String,
    val runtimeStatus: String,
    val ggufModelCount: Int
)

class ResearchAndDiscoveryEngine(
    private val context: Context,
    private val repository: MahiDataRepository? = null,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
) {
    suspend fun searchWebAndWikipedia(query: String): Result<List<WebResearchResult>> = withContext(Dispatchers.IO) {
        val clean = query.trim()
        if (clean.isBlank()) return@withContext Result.failure(IllegalArgumentException("Enter a research topic."))
        val results = mutableListOf<WebResearchResult>()

        runCatching {
            val encoded = URLEncoder.encode(clean, "UTF-8")
            val wikiReq = Request.Builder()
                .url("https://en.wikipedia.org/api/rest_v1/page/summary/${encoded.replace("+", "_")}")
                .header("User-Agent", "MahiAI-Android/3.3.0")
                .build()
            httpClient.newCall(wikiReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val obj = JSONObject(resp.body?.string().orEmpty())
                    val extract = obj.optString("extract")
                    val pageUrl = obj.optJSONObject("content_urls")?.optJSONObject("desktop")?.optString("page")
                        ?: "https://en.wikipedia.org/wiki/$encoded"
                    if (extract.isNotBlank()) {
                        results.add(WebResearchResult(obj.optString("title", clean), extract, pageUrl, "Wikipedia"))
                    }
                }
            }
        }

        runCatching {
            val encoded = URLEncoder.encode(clean, "UTF-8")
            val ddgReq = Request.Builder()
                .url("https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1")
                .header("User-Agent", "MahiAI-Android/3.3.0")
                .build()
            httpClient.newCall(ddgReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val obj = JSONObject(resp.body?.string().orEmpty())
                    val abs = obj.optString("AbstractText")
                    val url = obj.optString("AbstractURL")
                    if (abs.isNotBlank()) {
                        results.add(WebResearchResult(obj.optString("Heading", clean), abs, url, "DuckDuckGo Instant"))
                    }
                }
            }
        }

        if (results.isEmpty()) {
            Result.failure(IllegalStateException("No verified summary returned. Check internet connectivity or try another keyword."))
        } else {
            Result.success(results)
        }
    }

    suspend fun searchGitHubRepos(query: String): Result<List<GitHubRepoCandidate>> = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val req = Request.Builder()
            .url("https://api.github.com/search/repositories?q=$encoded&sort=stars&order=desc&per_page=8")
            .header("Accept", "application/vnd.github+json")
            .build()
        runCatching {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("GitHub API HTTP ${resp.code}")
                val items = JSONObject(resp.body?.string().orEmpty()).optJSONArray("items") ?: JSONArray()
                buildList {
                    for (i in 0 until items.length()) {
                        val o = items.optJSONObject(i) ?: continue
                        add(
                            GitHubRepoCandidate(
                                fullName = o.optString("full_name"),
                                description = o.optString("description", "No description"),
                                stars = o.optInt("stargazers_count"),
                                licenseName = o.optJSONObject("license")?.optString("spdx_id") ?: "Unspecified",
                                htmlUrl = o.optString("html_url")
                            )
                        )
                    }
                }
            }
        }
    }

    suspend fun searchHuggingFaceModels(query: String): Result<List<HuggingFaceModelCandidate>> = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val req = Request.Builder()
            .url("https://huggingface.co/api/models?search=$encoded&limit=8")
            .build()
        val offlineCheck = inspectOfflineLlmReadiness()
        runCatching {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("Hugging Face HTTP ${resp.code}")
                val arr = JSONArray(resp.body?.string().orEmpty())
                buildList {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val id = o.optString("id")
                        val note = when {
                            offlineCheck.totalRamGb < 5.5f && (id.contains("7b", true) || id.contains("8b", true)) ->
                                "Heavy for ${String.format("%.1f", offlineCheck.totalRamGb)} GB RAM — recommend <=3B quantized"
                            else -> "Compatible candidate (${offlineCheck.recommendedTier})"
                        }
                        add(
                            HuggingFaceModelCandidate(
                                modelId = id,
                                downloads = o.optLong("downloads"),
                                likes = o.optInt("likes"),
                                pipelineTag = o.optString("pipeline_tag", "text-generation"),
                                compatibilityNote = note
                            )
                        )
                    }
                }
            }
        }
    }

    fun inspectOfflineLlmReadiness(): OfflineLlmInspection {
        val am = context.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(mi)
        val ramGb = mi.totalMem.toFloat() / (1024f * 1024f * 1024f)
        val stat = StatFs(Environment.getDataDirectory().path)
        val freeGb = stat.availableBytes.toFloat() / (1024f * 1024f * 1024f)

        val localDir = File(context.applicationContext.filesDir, "offline_llms").apply { mkdirs() }
        val modelFiles = localDir.listFiles()?.filter { it.extension in listOf("gguf", "litertlm", "task", "bin") }.orEmpty()

        val tier = when {
            ramGb >= 10f -> "Up to 7B–8B Q4_K_M"
            ramGb >= 6.5f -> "1B–3B Q4_K_M (Moto G Stylus class)"
            else -> "0.5B–1.5B Compact Quantized"
        }
        val status = if (modelFiles.isEmpty()) {
            "NOT_INSTALLED — Local GGUF/LiteRT runtime requires downloading a verified model file into filesDir/offline_llms."
        } else {
            "VERIFIED_MODEL_PRESENT (${modelFiles.size} file(s), metadata ready)"
        }
        return OfflineLlmInspection(ramGb, freeGb, tier, status, modelFiles.size)
    }

    @SuppressLint("MissingPermission")
    suspend fun searchNearbyPlaces(categoryQuery: String): Result<List<NearbyPlaceResult>> = withContext(Dispatchers.IO) {
        val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) {
            return@withContext Result.failure(SecurityException("Grant Location permission to search nearby places."))
        }
        val lm = context.getSystemService(LocationManager::class.java)
        val loc = lm?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: lm?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            ?: return@withContext Result.failure(IllegalStateException("No GPS fix available yet. Enable Location and try again."))

        val lat = loc.latitude
        val lon = loc.longitude
        val safeCat = categoryQuery.trim().lowercase().replace(Regex("[^a-z0-9_ ]"), "")
            .ifBlank { "cafe" }
        val overpassQl = "[out:json][timeout:10];node(around:2500,$lat,$lon)[\"amenity\"~\"$safeCat\",i];out 8;"
        val req = Request.Builder()
            .url("https://overpass-api.de/api/interpreter?data=${URLEncoder.encode(overpassQl, "UTF-8")}")
            .build()

        runCatching {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("Overpass HTTP ${resp.code}")
                val elements = JSONObject(resp.body?.string().orEmpty()).optJSONArray("elements") ?: JSONArray()
                buildList {
                    for (i in 0 until elements.length()) {
                        val el = elements.optJSONObject(i) ?: continue
                        val tags = el.optJSONObject("tags") ?: continue
                        val name = tags.optString("name").ifBlank { tags.optString("amenity", "Place") }
                        add(
                            NearbyPlaceResult(
                                name = name,
                                category = tags.optString("amenity", safeCat),
                                latitude = el.optDouble("lat"),
                                longitude = el.optDouble("lon")
                            )
                        )
                    }
                }
            }
        }
    }

    suspend fun saveKnowledgeNote(title: String, sourceType: String, content: String, tags: String = "") {
        repository?.saveKnowledgeNote(
            KnowledgeNoteEntity(
                id = UUID.randomUUID().toString(),
                title = title.trim().ifBlank { "Untitled Note" },
                sourceType = sourceType,
                content = content.trim(),
                tags = tags.trim(),
                createdAtMs = System.currentTimeMillis()
            )
        )
    }

    fun exportKnowledgeBackupJson(notes: List<KnowledgeNoteEntity>): File {
        val outFile = File(context.applicationContext.filesDir, "mahi_knowledge_backup.json")
        val arr = JSONArray()
        notes.forEach { n ->
            arr.put(
                JSONObject().put("id", n.id).put("title", n.title)
                    .put("sourceType", n.sourceType).put("content", n.content)
                    .put("tags", n.tags).put("createdAtMs", n.createdAtMs)
            )
        }
        outFile.writeText(arr.toString(2))
        return outFile
    }
}
