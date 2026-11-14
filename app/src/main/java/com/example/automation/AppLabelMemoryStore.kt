package com.example.automation

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists learned per-app UI button label variants that succeeded on this device
 * (for example Facebook's localized Like/Follow contentDescription) in a compact JSON file
 * inside app-private filesDir so repeat runs find buttons faster. Clearable from Settings.
 */
class AppLabelMemoryStore(context: Context) {
    companion object {
        private const val FILE_NAME = "app_label_memory.json"
        private const val MAX_VARIANTS_PER_ACTION = 6
    }

    private val file = File(context.applicationContext.filesDir, FILE_NAME)
    private val lock = Any()

    // package -> canonicalAction -> ordered labels (most recently successful first)
    private val cache = mutableMapOf<String, MutableMap<String, MutableList<String>>>()

    private val _entryCount = MutableStateFlow(0)
    val entryCount: StateFlow<Int> = _entryCount.asStateFlow()

    init {
        loadFromDisk()
    }

    private fun countTotalEntries(): Int =
        cache.values.sumOf { actionMap -> actionMap.values.sumOf { it.size } }

    @Synchronized
    private fun loadFromDisk() {
        synchronized(lock) {
            cache.clear()
            if (!file.exists()) {
                _entryCount.value = 0
                return
            }
            try {
                val root = JSONObject(file.readText())
                val pkgs = root.keys()
                while (pkgs.hasNext()) {
                    val pkg = pkgs.next()
                    val actionObj = root.optJSONObject(pkg) ?: continue
                    val actionMap = mutableMapOf<String, MutableList<String>>()
                    val actions = actionObj.keys()
                    while (actions.hasNext()) {
                        val action = actions.next()
                        val arr = actionObj.optJSONArray(action) ?: continue
                        val list = mutableListOf<String>()
                        for (i in 0 until arr.length()) {
                            val item = arr.optString(i).trim()
                            if (item.isNotBlank() && !list.contains(item)) {
                                list.add(item)
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
            _entryCount.value = countTotalEntries()
        }
    }

    private fun saveToDisk() {
        try {
            val root = JSONObject()
            cache.forEach { (pkg, actionMap) ->
                val aObj = JSONObject()
                actionMap.forEach { (action, labels) ->
                    aObj.put(action, JSONArray(labels))
                }
                root.put(pkg, aObj)
            }
            file.writeText(root.toString())
        } catch (_: Exception) {
        }
    }

    fun recordSuccess(packageName: String, canonicalAction: String, matchedLabel: String) {
        val pkg = packageName.trim().lowercase()
        val action = canonicalAction.trim().lowercase()
        val label = matchedLabel.trim()
        if (pkg.isBlank() || action.isBlank() || label.length < 2 || label.length > 60) return
        if (label.equals("[PASSWORD_HIDDEN]", ignoreCase = true)) return

        synchronized(lock) {
            val actionMap = cache.getOrPut(pkg) { mutableMapOf() }
            val list = actionMap.getOrPut(action) { mutableListOf() }
            list.removeAll { it.equals(label, ignoreCase = true) }
            list.add(0, label)
            while (list.size > MAX_VARIANTS_PER_ACTION) {
                list.removeAt(list.lastIndex)
            }
            saveToDisk()
            _entryCount.value = countTotalEntries()
        }
    }

    fun getVariants(packageName: String, canonicalAction: String): List<String> {
        val pkg = packageName.trim().lowercase()
        val action = canonicalAction.trim().lowercase()
        if (pkg.isBlank() || action.isBlank()) return emptyList()
        synchronized(lock) {
            return cache[pkg]?.get(action)?.toList().orEmpty()
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
