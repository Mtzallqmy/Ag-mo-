package ai.alagent.service.agent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Thin foreground lifecycle owner; all cognition remains in :agent:runtime. */
@AndroidEntryPoint
class AgentService : Service() {
    @Inject lateinit var coordinator: AgentRunCoordinator
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var statusJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        statusJob = serviceScope.launch {
            coordinator.status.collectLatest { status ->
                if (status.lifecycle in setOf(RunLifecycle.COMPLETED, RunLifecycle.FAILED, RunLifecycle.STOPPED)) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else if (status.lifecycle == RunLifecycle.RUNNING || status.lifecycle == RunLifecycle.PAUSED) {
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(status))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { coordinator.stop(); stopSelf(); return START_NOT_STICKY }
            ACTION_PAUSE -> coordinator.pause()
            ACTION_RESUME -> coordinator.resume()
        }
        startForeground(NOTIFICATION_ID, buildNotification(coordinator.status.value))
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        statusJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Agent runs", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(status: AgentRunStatus) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_notify_sync)
        .setContentTitle("AL Agent • ${status.lifecycle.name.lowercase().replaceFirstChar { it.uppercase() }}")
        .setContentText(status.goal?.take(100) ?: "Foreground agent session")
        .setOngoing(status.lifecycle in setOf(RunLifecycle.RUNNING, RunLifecycle.PAUSED))
        .addAction(0, "Pause", actionIntent(ACTION_PAUSE, 1))
        .addAction(0, "Resume", actionIntent(ACTION_RESUME, 2))
        .addAction(0, "Stop", actionIntent(ACTION_STOP, 3))
        .build()

    private fun actionIntent(action: String, requestCode: Int): PendingIntent = PendingIntent.getService(
        this,
        requestCode,
        Intent(this, AgentService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    companion object {
        private const val CHANNEL_ID = "al_agent_runs"
        private const val NOTIFICATION_ID = 4101
        const val ACTION_START = "ai.alagent.action.AGENT_START"
        const val ACTION_PAUSE = "ai.alagent.action.AGENT_PAUSE"
        const val ACTION_RESUME = "ai.alagent.action.AGENT_RESUME"
        const val ACTION_STOP = "ai.alagent.action.AGENT_STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, AgentService::class.java).setAction(ACTION_START)
            )
        }
    }
}
