package ai.alagent.core.database

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Domain-facing records deliberately contain no Room annotations. */
data class SessionRecord(val id: String, val createdAt: Long, val updatedAt: Long, val privacyMode: Boolean)
data class TaskRecord(val id: String, val sessionId: String, val goal: String, val status: String, val createdAt: Long)
data class ProviderRecord(val id: String, val type: String, val displayName: String, val enabled: Boolean)
data class ModelRecord(val id: String, val displayName: String, val family: String, val format: String, val sizeBytes: Long?, val contextWindow: Int)
data class AutomationRecord(
    val id: String,
    val name: String,
    val schedule: String,
    val goal: String,
    val allowedToolsJson: String,
    val modelId: String?,
    val permissionPolicyJson: String,
    val lastRun: Long?,
    val nextRun: Long?,
    val status: String
)

interface SessionRepository {
    suspend fun create(record: SessionRecord)
    suspend fun get(id: String): SessionRecord?
    fun recent(limit: Int = 50): Flow<List<SessionRecord>>
}

class RoomSessionRepository(private val dao: SessionDao) : SessionRepository {
    override suspend fun create(record: SessionRecord) = dao.insert(record.toEntity())
    override suspend fun get(id: String) = dao.get(id)?.toRecord()
    override fun recent(limit: Int) = dao.observeRecent(limit).map { rows -> rows.map(SessionEntity::toRecord) }
}

interface TaskRepository {
    suspend fun create(record: TaskRecord)
    suspend fun get(id: String): TaskRecord?
    fun forSession(sessionId: String): Flow<List<TaskRecord>>
    suspend fun updateStatus(id: String, status: String)
}

class RoomTaskRepository(private val dao: TaskDao) : TaskRepository {
    override suspend fun create(record: TaskRecord) = dao.insert(record.toEntity())
    override suspend fun get(id: String) = dao.get(id)?.toRecord()
    override fun forSession(sessionId: String) = dao.observe(sessionId).map { rows -> rows.map(TaskEntity::toRecord) }
    override suspend fun updateStatus(id: String, status: String) = dao.updateStatus(id, status)
}

interface ProviderRepository {
    fun observe(): Flow<List<ProviderRecord>>
    suspend fun config(providerId: String): ProviderConfigRecord?
    suspend fun save(provider: ProviderRecord, config: ProviderConfigRecord)
}

data class ProviderConfigRecord(val providerId: String, val baseUrl: String?, val secretAlias: String?, val configJson: String)

class RoomProviderRepository(private val dao: ProviderDao) : ProviderRepository {
    override fun observe() = dao.observeProviders().map { rows -> rows.map { ProviderRecord(it.id, it.type, it.displayName, it.enabled) } }
    override suspend fun config(providerId: String) = dao.config(providerId)?.let { ProviderConfigRecord(it.providerId, it.baseUrl, it.secretAlias, it.configJson) }
    override suspend fun save(provider: ProviderRecord, config: ProviderConfigRecord) {
        require(config.providerId == provider.id) { "Provider/config id mismatch" }
        dao.save(
            ProviderEntity(provider.id, provider.type, provider.displayName, provider.enabled),
            ProviderConfigEntity("config:${provider.id}", provider.id, config.baseUrl, config.secretAlias, config.configJson)
        )
    }
}

interface AutomationRepository {
    fun observe(): Flow<List<AutomationRecord>>
    suspend fun get(id: String): AutomationRecord?
    suspend fun upsert(record: AutomationRecord)
    suspend fun due(now: Long): List<AutomationRecord>
    suspend fun updateRunState(id: String, lastRun: Long?, nextRun: Long?, status: String)
}

class RoomAutomationRepository(private val dao: ScheduleDao) : AutomationRepository {
    override fun observe() = dao.observeAll().map { rows -> rows.map(ScheduleEntity::toRecord) }
    override suspend fun get(id: String) = dao.get(id)?.toRecord()
    override suspend fun upsert(record: AutomationRecord) = dao.upsert(record.toEntity())
    override suspend fun due(now: Long) = dao.due(now).map(ScheduleEntity::toRecord)
    override suspend fun updateRunState(id: String, lastRun: Long?, nextRun: Long?, status: String) = dao.updateRunState(id, lastRun, nextRun, status)
}

private fun SessionRecord.toEntity() = SessionEntity(id, createdAt, updatedAt, privacyMode)
private fun SessionEntity.toRecord() = SessionRecord(id, createdAt, updatedAt, privacyMode)
private fun TaskRecord.toEntity() = TaskEntity(id, sessionId, goal, status, createdAt)
private fun TaskEntity.toRecord() = TaskRecord(id, sessionId, goal, status, createdAt)
private fun ScheduleEntity.toRecord() = AutomationRecord(id, name, schedule, goal, allowedToolsJson, modelId, permissionPolicyJson, lastRun, nextRun, status)
private fun AutomationRecord.toEntity() = ScheduleEntity(id, name, schedule, goal, allowedToolsJson, modelId, permissionPolicyJson, lastRun, nextRun, status)
