package ai.alagent.app.runtime

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ai.alagent.agent.runtime.AgentExecutionConfig
import ai.alagent.app.settings.AppSettingsStore
import ai.alagent.core.database.AlAgentDatabase
import ai.alagent.core.database.ScheduleEntity
import ai.alagent.service.agent.AgentRunCoordinator
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AutomationScheduler @Inject constructor(
    private val database: AlAgentDatabase,
    private val json: Json,
    private val workManager: WorkManager
) {
    fun schedules() = database.schedules().observeAll()

    suspend fun scheduleOnce(name: String, goal: String, runAtEpochMs: Long, allowedTools: Set<String>, modelId: String?): String {
        require(goal.isNotBlank())
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val entity = ScheduleEntity(id, name.ifBlank { goal.take(48) }, "once:$runAtEpochMs", goal.trim(), json.encodeToString(allowedTools), modelId, "{\"approval\":\"required-by-policy\"}", null, runAtEpochMs, "ENABLED")
        database.schedules().upsert(entity)
        val delay = (runAtEpochMs - now).coerceAtLeast(0)
        val request = OneTimeWorkRequestBuilder<AutomationWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(Data.Builder().putString(AutomationWorker.KEY_SCHEDULE_ID, id).build())
            .build()
        workManager.enqueueUniqueWork(workName(id), ExistingWorkPolicy.REPLACE, request)
        return id
    }

    suspend fun scheduleRecurring(name: String, goal: String, intervalMinutes: Long, allowedTools: Set<String>, modelId: String?): String {
        require(goal.isNotBlank())
        require(intervalMinutes >= 15) { "WorkManager recurring interval must be at least 15 minutes" }
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val next = now + TimeUnit.MINUTES.toMillis(intervalMinutes)
        database.schedules().upsert(
            ScheduleEntity(id, name.ifBlank { goal.take(48) }, "interval:$intervalMinutes", goal.trim(), json.encodeToString(allowedTools), modelId, "{\"approval\":\"required-by-policy\"}", null, next, "ENABLED")
        )
        val request = PeriodicWorkRequestBuilder<AutomationWorker>(intervalMinutes, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .setInputData(Data.Builder().putString(AutomationWorker.KEY_SCHEDULE_ID, id).build())
            .build()
        workManager.enqueueUniquePeriodicWork(workName(id), ExistingPeriodicWorkPolicy.UPDATE, request)
        return id
    }

    suspend fun disable(id: String) {
        val current = database.schedules().get(id) ?: return
        workManager.cancelUniqueWork(workName(id))
        database.schedules().upsert(current.copy(status = "DISABLED", nextRun = null))
    }

    companion object { fun workName(id: String) = "al-agent-automation-$id" }
}

class AutomationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun database(): AlAgentDatabase
        fun coordinator(): AgentRunCoordinator
        fun settings(): AppSettingsStore
        fun json(): Json
    }

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_SCHEDULE_ID) ?: return Result.failure()
        val deps = EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java)
        val db = deps.database()
        val schedule = db.schedules().get(id) ?: return Result.failure()
        if (schedule.status != "ENABLED") return Result.success()
        setForeground(foreground(schedule.name))
        val json = deps.json()
        val allowed = runCatching { json.decodeFromString<Set<String>>(schedule.allowedToolsJson) }.getOrDefault(emptySet())
        val settings = deps.settings().current()
        return try {
            val outcome = deps.coordinator().runBackground(
                schedule.goal,
                AgentExecutionConfig(
                    privacyMode = settings.privacyMode,
                    preferredModelId = schedule.modelId ?: settings.preferredModelId,
                    allowedToolIds = allowed.takeIf(Set<String>::isNotEmpty)
                )
            )
            val now = System.currentTimeMillis()
            val next = schedule.schedule.substringAfter("interval:", "").toLongOrNull()?.let { now + TimeUnit.MINUTES.toMillis(it) }
            db.schedules().updateRunState(id, now, next, if (next == null) "COMPLETED" else "ENABLED")
            Result.success(Data.Builder().putString("outcome", outcome::class.simpleName).build())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            db.schedules().updateRunState(id, System.currentTimeMillis(), schedule.nextRun, "FAILED")
            if (runAttemptCount < 3) Result.retry() else Result.failure(Data.Builder().putString("error", t.message).build())
        }
    }

    private fun foreground(name: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, "Automations", NotificationManager.IMPORTANCE_LOW)
        manager.createNotificationChannel(channel)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("AL Agent automation")
            .setContentText(name)
            .setOngoing(true)
            .build()
        return ForegroundInfo(4201, notification)
    }

    companion object {
        const val KEY_SCHEDULE_ID = "schedule_id"
        private const val CHANNEL_ID = "al_agent_automations"
    }
}
