package com.example.memory

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class ChatHistoryTurn(
    val role: String, // "user" or "assistant"
    val text: String,
    val timestampMs: Long = System.currentTimeMillis()
)

data class ConversationThread(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val languageId: String,
    val moodName: String,
    val turns: List<ChatHistoryTurn> = emptyList(),
    val updatedAtMs: Long = System.currentTimeMillis()
)

/**
 * Stores persistent conversation threads in filesDir/conversation_history.json.
 * Users can browse, resume, delete individual threads, or clear all history.
 */
class ConversationHistoryStore(context: Context) {
    companion object {
        private const val FILE_NAME = "conversation_history.json"
        private const val MAX_THREADS = 30
        private const val MAX_TURNS_PER_THREAD = 80
    }

    private val file = File(context.applicationContext.filesDir, FILE_NAME)
    private val _threads = MutableStateFlow(load())
    val threads: StateFlow<List<ConversationThread>> = _threads.asStateFlow()

    @Volatile
    var activeThreadId: String? = _threads.value.firstOrNull()?.id
        private set

    private fun load(): List<ConversationThread> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val turnsArr = obj.optJSONArray("turns") ?: JSONArray()
                    val turns = buildList {
                        for (j in 0 until turnsArr.length()) {
                            val t = turnsArr.optJSONObject(j) ?: continue
                            add(
                                ChatHistoryTurn(
                                    role = t.optString("role", "user"),
                                    text = t.optString("text", ""),
                                    timestampMs = t.optLong("timestampMs", 0L)
                                )
                            )
                        }
                    }
                    add(
                        ConversationThread(
                            id = obj.optString("id", UUID.randomUUID().toString()),
                            title = obj.optString("title", "Conversation"),
                            languageId = obj.optString("languageId", "en"),
                            moodName = obj.optString("moodName", "SASSY"),
                            turns = turns,
                            updatedAtMs = obj.optLong("updatedAtMs", 0L)
                        )
                    )
                }
            }.sortedByDescending { it.updatedAtMs }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun persist(list: List<ConversationThread>) {
        val arr = JSONArray()
        list.take(MAX_THREADS).forEach { thread ->
            val turnsArr = JSONArray()
            thread.turns.takeLast(MAX_TURNS_PER_THREAD).forEach { turn ->
                turnsArr.put(
                    JSONObject().apply {
                        put("role", turn.role)
                        put("text", turn.text)
                        put("timestampMs", turn.timestampMs)
                    }
                )
            }
            arr.put(
                JSONObject().apply {
                    put("id", thread.id)
                    put("title", thread.title)
                    put("languageId", thread.languageId)
                    put("moodName", thread.moodName)
                    put("updatedAtMs", thread.updatedAtMs)
                    put("turns", turnsArr)
                }
            )
        }
        file.writeText(arr.toString())
    }

    fun startNewThread(languageId: String = "en", moodName: String = "SASSY"): ConversationThread {
        val thread = ConversationThread(
            title = "New Chat",
            languageId = languageId,
            moodName = moodName
        )
        activeThreadId = thread.id
        val updated = (listOf(thread) + _threads.value).take(MAX_THREADS)
        _threads.value = updated
        persist(updated)
        return thread
    }

    fun appendTurn(
        role: String,
        text: String,
        languageId: String,
        moodName: String
    ) {
        val clean = text.trim()
        if (clean.isBlank()) return

        val currentId = activeThreadId
        val existing = _threads.value.firstOrNull { it.id == currentId }
            ?: startNewThread(languageId, moodName)

        val newTurn = ChatHistoryTurn(role = role, text = clean)
        val newTitle = if (existing.turns.isEmpty() && role == "user") {
            clean.take(48)
        } else {
            existing.title
        }

        val updatedThread = existing.copy(
            title = newTitle,
            languageId = languageId,
            moodName = moodName,
            turns = (existing.turns + newTurn).takeLast(MAX_TURNS_PER_THREAD),
            updatedAtMs = System.currentTimeMillis()
        )
        activeThreadId = updatedThread.id

        val updatedList = (listOf(updatedThread) + _threads.value.filterNot { it.id == updatedThread.id })
            .sortedByDescending { it.updatedAtMs }
            .take(MAX_THREADS)
        _threads.value = updatedList
        persist(updatedList)
    }

    fun selectThread(threadId: String): ConversationThread? {
        val found = _threads.value.firstOrNull { it.id == threadId } ?: return null
        activeThreadId = found.id
        return found
    }

    fun deleteThread(threadId: String) {
        val updated = _threads.value.filterNot { it.id == threadId }
        if (activeThreadId == threadId) {
            activeThreadId = updated.firstOrNull()?.id
        }
        _threads.value = updated
        persist(updated)
    }

    fun clearAllHistory() {
        activeThreadId = null
        _threads.value = emptyList()
        if (file.exists()) file.delete()
    }
}
