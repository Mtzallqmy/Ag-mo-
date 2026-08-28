package ai.alagent.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(entity: SessionEntity)
    @Query("SELECT * FROM sessions WHERE id=:id") suspend fun get(id: String): SessionEntity?
    @Query("SELECT * FROM sessions ORDER BY updated_at DESC LIMIT :limit") fun observeRecent(limit: Int): Flow<List<SessionEntity>>
    @Query("SELECT * FROM sessions ORDER BY updated_at DESC LIMIT :limit") suspend fun listRecent(limit: Int): List<SessionEntity>
    @Query("UPDATE sessions SET updated_at=:updatedAt WHERE id=:id") suspend fun touch(id: String, updatedAt: Long)
}

@Dao
interface TaskDao {
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(entity: TaskEntity)
    @Query("SELECT * FROM tasks WHERE id=:id") suspend fun get(id: String): TaskEntity?
    @Query("SELECT * FROM tasks WHERE session_id=:sessionId ORDER BY created_at") fun observe(sessionId: String): Flow<List<TaskEntity>>
    @Query("UPDATE tasks SET status=:status WHERE id=:id") suspend fun updateStatus(id: String, status: String)
}

@Dao
interface TurnDao {
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(entity: TurnEntity)
    @Query("SELECT * FROM turns WHERE task_id=:taskId ORDER BY timestamp") fun observe(taskId: String): Flow<List<TurnEntity>>
    @Query("SELECT * FROM turns WHERE id=:id") suspend fun get(id: String): TurnEntity?
}

@Dao
interface MessageDao {
    @Insert suspend fun insert(entity: MessageEntity)
    @Query("SELECT * FROM messages WHERE session_id=:sessionId ORDER BY created_at") fun observe(sessionId: String): Flow<List<MessageEntity>>
    @Query("SELECT * FROM messages WHERE session_id=:sessionId ORDER BY created_at DESC LIMIT :limit") suspend fun recent(sessionId: String, limit: Int): List<MessageEntity>
}

@Dao
interface ToolTraceDao {
    @Insert suspend fun insertToolCall(entity: ToolCallEntity)
    @Insert suspend fun insertObservation(entity: ObservationEntity)
    @Insert suspend fun insertVerification(entity: VerificationResultEntity)
    @Query("SELECT * FROM tool_calls WHERE turn_id=:turnId ORDER BY id") suspend fun calls(turnId: String): List<ToolCallEntity>
    @Query("SELECT * FROM observations WHERE turn_id=:turnId ORDER BY created_at") suspend fun observations(turnId: String): List<ObservationEntity>
}

@Dao
interface TaskMemoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertTodo(entity: TodoEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertScratchpad(entity: ScratchpadEntryEntity)
    @Query("SELECT * FROM todos WHERE task_id=:taskId ORDER BY position") suspend fun todos(taskId: String): List<TodoEntity>
    @Query("SELECT * FROM scratchpad_entries WHERE task_id=:taskId ORDER BY created_at DESC LIMIT :limit") suspend fun scratchpad(taskId: String, limit: Int): List<ScratchpadEntryEntity>
    @Query("DELETE FROM todos WHERE task_id=:taskId") suspend fun clearTodos(taskId: String)
    @Query("DELETE FROM scratchpad_entries WHERE task_id=:taskId") suspend fun clearScratchpad(taskId: String)
}

@Dao
interface MemoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(entity: MemoryEntity)
    @Query("SELECT * FROM memories ORDER BY updated_at DESC") fun observeAll(): Flow<List<MemoryEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertEmbedding(entity: MemoryEmbeddingEntity)
    @Query("SELECT * FROM memories WHERE kind=:kind ORDER BY updated_at DESC LIMIT :limit") suspend fun recent(kind: String, limit: Int): List<MemoryEntity>
    @Query("SELECT * FROM memories ORDER BY updated_at DESC LIMIT :limit") suspend fun recentAny(limit: Int): List<MemoryEntity>
    @Query("SELECT * FROM memories WHERE id=:id") suspend fun get(id: String): MemoryEntity?
    @Query("DELETE FROM memories WHERE id=:id") suspend fun delete(id: String)
}


@Dao
interface SkillDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(entity: SkillEntity)
    @Query("SELECT * FROM skills ORDER BY name") fun observeAll(): Flow<List<SkillEntity>>
    @Query("SELECT * FROM skills WHERE skill_id=:skillId LIMIT 1") suspend fun bySkillId(skillId: String): SkillEntity?
    @Query("UPDATE skills SET enabled=:enabled WHERE skill_id=:skillId") suspend fun setEnabled(skillId: String, enabled: Boolean)
    @Query("DELETE FROM skills WHERE skill_id=:skillId") suspend fun delete(skillId: String)
}

@Dao
interface ProviderDao {
    @Transaction
    suspend fun save(provider: ProviderEntity, config: ProviderConfigEntity) {
        upsertProvider(provider)
        upsertConfig(config)
    }
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertProvider(entity: ProviderEntity)
    @Query("SELECT * FROM providers WHERE id=:id") suspend fun get(id: String): ProviderEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertConfig(entity: ProviderConfigEntity)
    @Query("SELECT * FROM providers ORDER BY display_name") fun observeProviders(): Flow<List<ProviderEntity>>
    @Query("SELECT * FROM provider_configs WHERE provider_id=:providerId") suspend fun config(providerId: String): ProviderConfigEntity?
}

@Dao
interface ModelDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(entity: ModelEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertDownload(entity: ModelDownloadEntity)
    @Query("SELECT * FROM models ORDER BY display_name") fun observeModels(): Flow<List<ModelEntity>>
    @Query("SELECT * FROM models ORDER BY display_name") suspend fun listModels(): List<ModelEntity>
    @Query("SELECT * FROM models WHERE provider_id=:providerId ORDER BY display_name") suspend fun listForProvider(providerId: String): List<ModelEntity>
    @Query("SELECT * FROM model_downloads WHERE model_id=:modelId") fun observeDownload(modelId: String): Flow<ModelDownloadEntity?>
    @Query("SELECT * FROM model_downloads ORDER BY updated_at DESC") fun observeDownloads(): Flow<List<ModelDownloadEntity>>
    @Query("SELECT * FROM model_downloads WHERE status IN ('QUEUED','DOWNLOADING')") suspend fun interruptedDownloads(): List<ModelDownloadEntity>
    @Query("UPDATE models SET size_bytes=:sizeBytes WHERE id=:modelId") suspend fun updateSize(modelId: String, sizeBytes: Long)
    @Query("SELECT * FROM model_downloads WHERE model_id=:modelId") suspend fun download(modelId: String): ModelDownloadEntity?
    @Query("DELETE FROM models WHERE id=:modelId") suspend fun deleteModel(modelId: String)
}

@Dao
interface AuditDao {
    @Insert suspend fun insert(entity: AuditEventEntity)
    @Query("SELECT * FROM audit_events ORDER BY timestamp DESC LIMIT :limit") fun observeRecent(limit: Int): Flow<List<AuditEventEntity>>
    @Query("DELETE FROM audit_events WHERE timestamp < :before") suspend fun deleteBefore(before: Long): Int
}

@Dao
interface ScheduleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(entity: ScheduleEntity)
    @Query("SELECT * FROM schedules WHERE id=:id") suspend fun get(id: String): ScheduleEntity?
    @Query("SELECT * FROM schedules ORDER BY COALESCE(next_run, 9223372036854775807), name") fun observeAll(): Flow<List<ScheduleEntity>>
    @Query("SELECT * FROM schedules WHERE status='ENABLED' AND next_run IS NOT NULL AND next_run <= :now ORDER BY next_run") suspend fun due(now: Long): List<ScheduleEntity>
    @Query("UPDATE schedules SET last_run=:lastRun,next_run=:nextRun,status=:status WHERE id=:id") suspend fun updateRunState(id: String, lastRun: Long?, nextRun: Long?, status: String)
}
