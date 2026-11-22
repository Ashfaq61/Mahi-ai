package com.example.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "cached_models")
data class CachedModelEntity(
    @PrimaryKey val compositeKey: String, // "$provider:$modelId"
    val provider: String,
    val modelId: String,
    val displayName: String,
    val capabilitiesCsv: String, // e.g., "chat,vision,tools,live"
    val pricingSummary: String,
    val contextWindow: Int,
    val lastProbedOk: Boolean,
    val probeStatusNote: String,
    val latencyMs: Long,
    val verifiedAtMs: Long,
    val isManualOverride: Boolean = false
)

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val role: String, // "user" | "assistant" | "system"
    val text: String,
    val providerUsed: String,
    val modelUsed: String,
    val timestampMs: Long
)

@Entity(tableName = "agent_collaboration")
data class AgentCollaborationEntity(
    @PrimaryKey val id: String,
    val fromAgent: String,
    val toAgent: String,
    val topic: String,
    val payloadSummary: String,
    val status: String,
    val timestampMs: Long
)

@Entity(tableName = "release_records")
data class ReleaseRecordEntity(
    @PrimaryKey val id: String,
    val versionName: String,
    val versionCode: Int,
    val branchName: String,
    val sha256Checksum: String,
    val status: String,
    val notes: String,
    val createdAtMs: Long
)

@Entity(tableName = "knowledge_notes")
data class KnowledgeNoteEntity(
    @PrimaryKey val id: String,
    val title: String,
    val sourceType: String, // "web", "pdf", "file", "manual"
    val content: String,
    val tags: String,
    val createdAtMs: Long
)

@Dao
interface CachedModelDao {
    @Query("SELECT * FROM cached_models ORDER BY lastProbedOk DESC, verifiedAtMs DESC")
    fun observeAll(): Flow<List<CachedModelEntity>>

    @Query("SELECT * FROM cached_models WHERE provider = :provider")
    suspend fun getByProvider(provider: String): List<CachedModelEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<CachedModelEntity>)

    @Query("DELETE FROM cached_models WHERE provider = :provider")
    suspend fun clearProvider(provider: String)
}

@Dao
interface ChatMessageDao {
    @Query("SELECT * FROM chat_messages ORDER BY timestampMs ASC LIMIT 120")
    fun observeHistory(): Flow<List<ChatMessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: ChatMessageEntity)

    @Query("DELETE FROM chat_messages")
    suspend fun clearAll()
}

@Dao
interface AgentCollaborationDao {
    @Query("SELECT * FROM agent_collaboration ORDER BY timestampMs DESC LIMIT 100")
    fun observeAll(): Flow<List<AgentCollaborationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: AgentCollaborationEntity)

    @Query("DELETE FROM agent_collaboration")
    suspend fun clearAll()
}

@Dao
interface ReleaseRecordDao {
    @Query("SELECT * FROM release_records ORDER BY createdAtMs DESC")
    fun observeAll(): Flow<List<ReleaseRecordEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: ReleaseRecordEntity)
}

@Dao
interface KnowledgeNoteDao {
    @Query("SELECT * FROM knowledge_notes ORDER BY createdAtMs DESC")
    fun observeAll(): Flow<List<KnowledgeNoteEntity>>

    @Query("SELECT * FROM knowledge_notes WHERE title LIKE '%' || :q || '%' OR content LIKE '%' || :q || '%'")
    suspend fun search(q: String): List<KnowledgeNoteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(note: KnowledgeNoteEntity)

    @Query("DELETE FROM knowledge_notes WHERE id = :id")
    suspend fun delete(id: String)
}

@Database(
    entities = [
        CachedModelEntity::class,
        ChatMessageEntity::class,
        AgentCollaborationEntity::class,
        ReleaseRecordEntity::class,
        KnowledgeNoteEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class MahiDatabase : RoomDatabase() {
    abstract fun cachedModelDao(): CachedModelDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun agentCollaborationDao(): AgentCollaborationDao
    abstract fun releaseRecordDao(): ReleaseRecordDao
    abstract fun knowledgeNoteDao(): KnowledgeNoteDao

    companion object {
        @Volatile
        private var INSTANCE: MahiDatabase? = null

        fun getInstance(context: Context): MahiDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    MahiDatabase::class.java,
                    "mahi_ai_database"
                ).fallbackToDestructiveMigration(dropAllTables = true).build().also { INSTANCE = it }
            }
        }
    }
}

class MahiDataRepository(private val db: MahiDatabase) {
    val cachedModels: Flow<List<CachedModelEntity>> = db.cachedModelDao().observeAll()
    val chatMessages: Flow<List<ChatMessageEntity>> = db.chatMessageDao().observeHistory()
    val agentLogs: Flow<List<AgentCollaborationEntity>> = db.agentCollaborationDao().observeAll()
    val releases: Flow<List<ReleaseRecordEntity>> = db.releaseRecordDao().observeAll()
    val knowledgeNotes: Flow<List<KnowledgeNoteEntity>> = db.knowledgeNoteDao().observeAll()

    suspend fun saveModels(models: List<CachedModelEntity>) = db.cachedModelDao().upsertAll(models)
    suspend fun getProviderModels(provider: String) = db.cachedModelDao().getByProvider(provider)
    suspend fun saveChatMessage(msg: ChatMessageEntity) = db.chatMessageDao().insert(msg)
    suspend fun clearChatHistory() = db.chatMessageDao().clearAll()
    suspend fun logAgentCollaboration(entry: AgentCollaborationEntity) = db.agentCollaborationDao().insert(entry)
    suspend fun clearAgentLogs() = db.agentCollaborationDao().clearAll()
    suspend fun recordRelease(record: ReleaseRecordEntity) = db.releaseRecordDao().insert(record)
    suspend fun saveKnowledgeNote(note: KnowledgeNoteEntity) = db.knowledgeNoteDao().upsert(note)
    suspend fun searchKnowledge(query: String) = db.knowledgeNoteDao().search(query)
    suspend fun deleteKnowledgeNote(id: String) = db.knowledgeNoteDao().delete(id)
}
