package ai.alagent.core.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        SessionEntity::class, TaskEntity::class, TurnEntity::class, MessageEntity::class,
        ToolCallEntity::class, ObservationEntity::class, VerificationResultEntity::class,
        MemoryEntity::class, MemoryEmbeddingEntity::class, TodoEntity::class, ScratchpadEntryEntity::class,
        SkillEntity::class, SkillRunEntity::class, ModelEntity::class, ModelDownloadEntity::class,
        ProviderEntity::class, ProviderConfigEntity::class, ScheduleEntity::class, AuditEventEntity::class,
        AttachmentEntity::class, PromptVersionEntity::class, EvaluationRunEntity::class
    ],
    version = 3,
    exportSchema = true
)
abstract class AlAgentDatabase : RoomDatabase() {
    abstract fun sessions(): SessionDao
    abstract fun tasks(): TaskDao
    abstract fun turns(): TurnDao
    abstract fun messages(): MessageDao
    abstract fun toolTrace(): ToolTraceDao
    abstract fun memories(): MemoryDao
    abstract fun taskMemory(): TaskMemoryDao
    abstract fun skills(): SkillDao
    abstract fun providers(): ProviderDao
    abstract fun models(): ModelDao
    abstract fun audit(): AuditDao
    abstract fun schedules(): ScheduleDao
}
