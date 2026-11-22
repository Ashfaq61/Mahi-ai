package com.example.automation

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class LearnedActionStrategy(
    val packageName: String,
    val canonicalAction: String,
    val matchedLabel: String,
    val tapMethod: String = "accessibility",
    val scrollDirectionHint: String = "",
    val updatedAtMs: Long = System.currentTimeMillis(),
    val consecutiveFailures: Int = 0
)

/**
 * Persists learned per-app UI button label variants and working strategies (label + tapMethod +
 * scroll hint) that succeeded on this device, with automatic expiry after 30 days or 2 consecutive
 * failures. Stored in app-private filesDir and clearable anytime from Settings.
 */
class AppLabelMemoryStore(context: Context) {
    companion object {
        private const val FILE_NAME = "app_label_memory.json"
        private const val MAX_VARIANTS_PER_ACTION = 6
        const val EXPIRY_AGE_MS: Long = 30L * 24L * 60L * 60L * 1000L
        const val MAX_CONSECUTIVE_FAILURES: Int = 2
    }

    private val file = File(context.applicationContext.filesDir, FILE_NAME)
    private val lock = Any()

    // package -> canonicalAction -> ordered strategies (most recently successful first)
    private val cache = mutableMapOf<String, MutableMap<String, MutableList<LearnedActionStrategy>>>()

    private val _entryCount = MutableStateFlow(0)
    val entryCount: StateFlow<Int> = _entryCount.asStateFlow()

    init {
        loadFromDisk()
    }

    private fun isExpired(entry: LearnedActionStrategy, nowMs: Long): Boolean {
        val age = (nowMs - entry.updatedAtMs).coerceAtLeast(0L)
        return age > EXPIRY_AGE_MS || entry.consecutiveFailures >= MAX_CONSECUTIVE_FAILURES
    }

    private fun pruneExpiredLocked(nowMs: Long = System.currentTimeMillis()): Boolean {
        var changed = false
        val pkgIter = cache.entries.iterator()
        while (pkgIter.hasNext()) {
            val (_, actionMap) = pkgIter.next()
            val actIter = actionMap.entries.iterator()
            while (actIter.hasNext()) {
                val (_, list) = actIter.next()
                val removed = list.removeAll { isExpired(it, nowMs) }
                if (removed) changed = true
                if (list.isEmpty()) {
                    actIter.remove()
                    changed = true
                }
            }
            if (actionMap.isEmpty()) {
                pkgIter.remove()
                changed = true
            }
        }
        return changed
    }

    private fun countTotalEntries(): Int =
        cache.values.sumOf { actionMap -> actionMap.values.sumOf { it.size } }

    @Synchronized
    private fun loadFromDisk(nowMs: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            cache.clear()
            if (!file.exists()) {
                _entryCount.value = 0
                return
            }
            var dirty = false
            try {
                val root = JSONObject(file.readText())
                val pkgs = root.keys()
                while (pkgs.hasNext()) {
                    val pkg = pkgs.next()
                    val actionObj = root.optJSONObject(pkg) ?: continue
                    val actionMap = mutableMapOf<String, MutableList<LearnedActionStrategy>>()
                    val actions = actionObj.keys()
                    while (actions.hasNext()) {
                        val action = actions.next()
                        val arr = actionObj.optJSONArray(action) ?: continue
                        val list = mutableListOf<LearnedActionStrategy>()
                        for (i in 0 until arr.length()) {
                            val rawItem = arr.opt(i)
                            val entry = when (rawItem) {
                                is JSONObject -> {
                                    val lbl = rawItem.optString("label").trim()
                                    if (lbl.isBlank()) null else LearnedActionStrategy(
                                        packageName = pkg,
                                        canonicalAction = action,
                                        matchedLabel = lbl,
                                        tapMethod = rawItem.optString("tapMethod").ifBlank { "accessibility" },
                                        scrollDirectionHint = rawItem.optString("scrollDirectionHint"),
                                        updatedAtMs = rawItem.optLong("updatedAtMs", nowMs),
                                        consecutiveFailures = rawItem.optInt("consecutiveFailures", 0)
                                    )
                                }
                                is String -> {
                                    val lbl = rawItem.trim()
                                    if (lbl.isBlank()) null else {
                                        dirty = true
                                        LearnedActionStrategy(
                                            packageName = pkg,
                                            canonicalAction = action,
                                            matchedLabel = lbl,
                                            tapMethod = "accessibility",
                                            updatedAtMs = nowMs
                                        )
                                    }
                                }
                                else -> null
                            }
                            if (entry != null && !isExpired(entry, nowMs) &&
                                list.none { it.matchedLabel.equals(entry.matchedLabel, ignoreCase = true) }
                            ) {
                                list.add(entry)
                            } else if (entry != null) {
                                dirty = true
                            }
                        }
                        if (list.isNotEmpty()) {
                            actionMap[action] = list
                        }
                    }
                    if (actionMap.isNotEmpty()) {
                        cache[pkg] = actionMap
                    }
                }
            } catch (_: Exception) {
                cache.clear()
            }
            if (pruneExpiredLocked(nowMs) || dirty) {
                saveToDisk()
            }
            _entryCount.value = countTotalEntries()
        }
    }

    private fun saveToDisk() {
        try {
            val root = JSONObject()
            cache.forEach { (pkg, actionMap) ->
                val aObj = JSONObject()
                actionMap.forEach { (action, entries) ->
                    val arr = JSONArray()
                    entries.forEach { entry ->
                        arr.put(
                            JSONObject().apply {
                                put("label", entry.matchedLabel)
                                put("tapMethod", entry.tapMethod)
                                if (entry.scrollDirectionHint.isNotBlank()) {
                                    put("scrollDirectionHint", entry.scrollDirectionHint)
                                }
                                put("updatedAtMs", entry.updatedAtMs)
                                put("consecutiveFailures", entry.consecutiveFailures)
                            }
                        )
                    }
                    aObj.put(action, arr)
                }
                root.put(pkg, aObj)
            }
            file.writeText(root.toString())
        } catch (_: Exception) {
        }
    }

    fun recordSuccess(
        packageName: String,
        canonicalAction: String,
        matchedLabel: String,
        tapMethod: String = "accessibility",
        scrollDirectionHint: String = "",
        nowMs: Long = System.currentTimeMillis()
    ) {
        val pkg = packageName.trim().lowercase()
        val action = canonicalAction.trim().lowercase()
        val label = matchedLabel.trim()
        if (pkg.isBlank() || action.isBlank() || label.length < 2 || label.length > 60) return
        if (label.equals("[PASSWORD_HIDDEN]", ignoreCase = true)) return

        synchronized(lock) {
            pruneExpiredLocked(nowMs)
            val actionMap = cache.getOrPut(pkg) { mutableMapOf() }
            val list = actionMap.getOrPut(action) { mutableListOf() }
            list.removeAll { it.matchedLabel.equals(label, ignoreCase = true) }
            list.add(
                0,
                LearnedActionStrategy(
                    packageName = pkg,
                    canonicalAction = action,
                    matchedLabel = label,
                    tapMethod = tapMethod.ifBlank { "accessibility" },
                    scrollDirectionHint = scrollDirectionHint.trim().lowercase(),
                    updatedAtMs = nowMs,
                    consecutiveFailures = 0
                )
            )
            while (list.size > MAX_VARIANTS_PER_ACTION) {
                list.removeAt(list.lastIndex)
            }
            saveToDisk()
            _entryCount.value = countTotalEntries()
        }
    }

    fun recordFailure(
        packageName: String,
        canonicalAction: String,
        matchedLabel: String? = null,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val pkg = packageName.trim().lowercase()
        val action = canonicalAction.trim().lowercase()
        if (pkg.isBlank() || action.isBlank()) return

        synchronized(lock) {
            val actionMap = cache[pkg] ?: return
            val list = actionMap[action] ?: return
            val targetIdx = if (!matchedLabel.isNullOrBlank()) {
                list.indexOfFirst { it.matchedLabel.equals(matchedLabel.trim(), ignoreCase = true) }
            } else {
                0
            }
            if (targetIdx !in list.indices) return
            val updated = list[targetIdx].copy(consecutiveFailures = list[targetIdx].consecutiveFailures + 1)
            if (isExpired(updated, nowMs)) {
                list.removeAt(targetIdx)
            } else {
                list[targetIdx] = updated
            }
            pruneExpiredLocked(nowMs)
            saveToDisk()
            _entryCount.value = countTotalEntries()
        }
    }

    fun getPreferredStrategy(
        packageName: String,
        canonicalAction: String,
        nowMs: Long = System.currentTimeMillis()
    ): LearnedActionStrategy? {
        val pkg = packageName.trim().lowercase()
        val action = canonicalAction.trim().lowercase()
        if (pkg.isBlank() || action.isBlank()) return null
        synchronized(lock) {
            if (pruneExpiredLocked(nowMs)) {
                saveToDisk()
                _entryCount.value = countTotalEntries()
            }
            return cache[pkg]?.get(action)?.firstOrNull()
        }
    }

    fun getVariants(
        packageName: String,
        canonicalAction: String,
        nowMs: Long = System.currentTimeMillis()
    ): List<String> {
        val pkg = packageName.trim().lowercase()
        val action = canonicalAction.trim().lowercase()
        if (pkg.isBlank() || action.isBlank()) return emptyList()
        synchronized(lock) {
            if (pruneExpiredLocked(nowMs)) {
                saveToDisk()
                _entryCount.value = countTotalEntries()
            }
            return cache[pkg]?.get(action)?.map { it.matchedLabel }.orEmpty()
        }
    }

    fun clear() {
        synchronized(lock) {
            cache.clear()
            if (file.exists()) {
                file.delete()
            }
            _entryCount.value = 0
        }
    }
}
