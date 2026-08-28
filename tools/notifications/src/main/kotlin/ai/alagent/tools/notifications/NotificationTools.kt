package ai.alagent.tools.notifications

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import ai.alagent.core.model.RiskLevel
import ai.alagent.core.model.VerificationResult
import ai.alagent.core.model.VerificationStatus
import ai.alagent.tools.api.*
import kotlinx.serialization.json.*

class AlAgentNotificationListenerService : NotificationListenerService() {
    companion object {
        @Volatile internal var instance: AlAgentNotificationListenerService? = null
        val isConnected: Boolean get() = instance != null
    }
    override fun onListenerConnected() { super.onListenerConnected(); instance = this }
    override fun onListenerDisconnected() { if (instance === this) instance = null; super.onListenerDisconnected() }
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }
}

data class NotificationActionSnapshot(val id: String, val title: String)
data class NotificationSnapshot(
    val key: String,
    val packageName: String,
    val postedAtEpochMs: Long,
    val title: String?,
    val text: String?,
    val actions: List<NotificationActionSnapshot>
)

class NotificationSnapshotProvider(private val service: () -> AlAgentNotificationListenerService? = { AlAgentNotificationListenerService.instance }) {
    fun current(limit: Int = 50): List<NotificationSnapshot> {
        val listener = requireNotNull(service()) { "Notification access is not connected" }
        return listener.activeNotifications.orEmpty().asSequence()
            .sortedByDescending(StatusBarNotification::getPostTime)
            .take(limit.coerceIn(1, 200))
            .map { sbn ->
                val extras = sbn.notification.extras
                NotificationSnapshot(
                    key = sbn.key,
                    packageName = sbn.packageName,
                    postedAtEpochMs = sbn.postTime,
                    title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
                    text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
                    actions = sbn.notification.actions.orEmpty().mapIndexed { index, action -> NotificationActionSnapshot("${sbn.key}:$index", action.title?.toString().orEmpty()) }
                )
            }.toList()
    }

    fun invoke(actionId: String): Boolean {
        val listener = service() ?: return false
        val split = actionId.lastIndexOf(':')
        if (split <= 0) return false
        val key = actionId.substring(0, split)
        val index = actionId.substring(split + 1).toIntOrNull() ?: return false
        val notification = listener.activeNotifications.orEmpty().firstOrNull { it.key == key } ?: return false
        val action = notification.notification.actions?.getOrNull(index) ?: return false
        return runCatching { action.actionIntent.send(); true }.getOrDefault(false)
    }
}

class NotificationReadTool(private val provider: NotificationSnapshotProvider = NotificationSnapshotProvider()) : Tool {
    override val descriptor = ToolDescriptor(
        "notification_read", "Read notifications", "Read a bounded snapshot of active notifications and their explicit actions.",
        buildJsonObject { put("type", "object"); putJsonObject("properties") { putJsonObject("limit") { put("type", "integer"); put("minimum", 1); put("maximum", 100) } }; put("additionalProperties", false) },
        riskLevel = RiskLevel.MEDIUM, requiresConfirmation = true, category = ToolCategory.SYSTEM
    )
    override suspend fun execute(request: ToolRequest): ToolExecutionResult = runCatching {
        val limit = request.arguments["limit"]?.jsonPrimitive?.intOrNull ?: 30
        val notifications = provider.current(limit)
        buildJsonObject {
            put("count", notifications.size)
            putJsonArray("notifications") { notifications.forEach { n -> add(buildJsonObject {
                put("key", n.key); put("packageName", n.packageName); put("postedAt", n.postedAtEpochMs); put("title", n.title ?: ""); put("text", n.text ?: "")
                putJsonArray("actions") { n.actions.forEach { action -> add(buildJsonObject { put("id", action.id); put("title", action.title) }) } }
            }) } }
        }
    }.fold({ ToolExecutionResult(request.callId, true, it) }, { ToolExecutionResult(request.callId, false, error = it.message) })
    override suspend fun observe(request: ToolRequest, execution: ToolExecutionResult) = ToolObservation("notification-read success=${execution.success}", execution.output)
    override suspend fun verify(request: ToolRequest, context: VerificationContext) = if (context.execution.success) VerificationResult(VerificationStatus.SUCCESS, listOf("Active notification snapshot captured")) else VerificationResult(VerificationStatus.FAILED, reason = context.execution.error)
}

class NotificationActionTool(private val provider: NotificationSnapshotProvider = NotificationSnapshotProvider()) : Tool {
    override val descriptor = ToolDescriptor(
        "notification_action", "Notification action", "Invoke one explicit action exposed by an active Android notification.",
        buildJsonObject { put("type", "object"); putJsonObject("properties") { putJsonObject("action_id") { put("type", "string") } }; putJsonArray("required") { add(JsonPrimitive("action_id")) }; put("additionalProperties", false) },
        riskLevel = RiskLevel.HIGH, requiresConfirmation = true, category = ToolCategory.SYSTEM
    )
    override suspend fun execute(request: ToolRequest): ToolExecutionResult {
        val actionId = request.arguments["action_id"]?.jsonPrimitive?.contentOrNull ?: return ToolExecutionResult(request.callId, false, error = "action_id is required")
        val ok = provider.invoke(actionId)
        return ToolExecutionResult(request.callId, ok, metadata = mapOf("actionId" to actionId), error = if (ok) null else "Notification action unavailable or rejected")
    }
    override suspend fun observe(request: ToolRequest, execution: ToolExecutionResult): ToolObservation? {
        val actionId = execution.metadata["actionId"]
        val stillPresent = actionId?.let { id -> provider.current(100).flatMap { it.actions }.any { it.id == id } }
        return ToolObservation("notification-action success=${execution.success} actionStillPresent=${stillPresent ?: "unknown"}")
    }
    override suspend fun verify(request: ToolRequest, context: VerificationContext): VerificationResult {
        if (!context.execution.success) return VerificationResult(VerificationStatus.FAILED, reason = context.execution.error)
        return VerificationResult(VerificationStatus.UNKNOWN, listOf("PendingIntent action sent"), "Notification action outcome requires target-app observation")
    }
}

object NotificationToolFactory { fun create(provider: NotificationSnapshotProvider = NotificationSnapshotProvider()): List<Tool> = listOf(NotificationReadTool(provider), NotificationActionTool(provider)) }
