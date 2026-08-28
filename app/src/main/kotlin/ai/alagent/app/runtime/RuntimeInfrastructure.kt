package ai.alagent.app.runtime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import ai.alagent.ai.provider.api.ConnectivityProvider
import ai.alagent.ai.provider.api.RoutingContext
import ai.alagent.agent.cognition.ContextInputs
import ai.alagent.agent.policy.AppTierClassifier
import ai.alagent.agent.policy.PermissionManager
import ai.alagent.agent.policy.SensitiveDataDetector
import ai.alagent.agent.policy.ToolAuditLogger
import ai.alagent.agent.runtime.AgentRunRecorder
import ai.alagent.agent.runtime.AgentRunResult
import ai.alagent.agent.runtime.AgentSession
import ai.alagent.agent.runtime.AgentState
import ai.alagent.agent.runtime.AgentTask
import ai.alagent.agent.runtime.CompletionCriterionProbe
import ai.alagent.agent.runtime.ExecutionPhaseResult
import ai.alagent.agent.runtime.RuntimeContextProvider
import ai.alagent.agent.runtime.TurnRecorder
import ai.alagent.agent.runtime.VerificationRoutingContextProvider
import ai.alagent.app.settings.AppSettingsStore
import ai.alagent.core.database.AlAgentDatabase
import ai.alagent.core.database.AuditEventEntity
import ai.alagent.core.database.MessageEntity
import ai.alagent.core.database.ObservationEntity
import ai.alagent.core.database.SessionEntity
import ai.alagent.core.database.TaskEntity
import ai.alagent.core.database.ToolCallEntity
import ai.alagent.core.database.TurnEntity
import ai.alagent.core.database.VerificationResultEntity
import ai.alagent.core.files.AtomicFileStore
import ai.alagent.core.model.AppTier
import ai.alagent.core.model.ModelCapability
import ai.alagent.core.model.ModelDescriptor
import ai.alagent.core.model.RiskLevel
import ai.alagent.core.model.TurnRecord
import ai.alagent.tools.accessibility.AlAgentAccessibilityService
import ai.alagent.tools.notifications.AlAgentNotificationListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

class AndroidConnectivityProvider(private val context: Context) : ConnectivityProvider {
    override fun internetAvailable(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}

class AndroidPermissionManager(private val context: Context) : PermissionManager {
    override fun has(permission: String): Boolean = when (permission) {
        "accessibility" -> AlAgentAccessibilityService.isConnected
        "notifications" -> AlAgentNotificationListenerService.isConnected
        else -> ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
}

/** Returns sensitivity labels only; secret values are never copied into approval/audit metadata. */
class DefaultSensitiveDataDetector : SensitiveDataDetector {
    private val patterns = listOf(
        "password" to Regex("""(?i)\b(password|passcode|pin)\b"""),
        "one-time-code" to Regex("""\b\d{6}\b"""),
        "payment-card" to Regex("""\b(?:\d[ -]*?){13,19}\b"""),
        "api-credential" to Regex("""(?i)\b(api[_ -]?key|bearer|secret|access[_ -]?token)\b"""),
        "seed-phrase" to Regex("""(?i)\b(seed phrase|recovery phrase|mnemonic)\b"""),
        "iban" to Regex("""\b[A-Z]{2}\d{2}[A-Z0-9]{10,30}\b""")
    )

    override fun detect(text: String): List<String> = patterns.mapNotNull { (label, regex) ->
        label.takeIf { regex.containsMatchIn(text) }
    }.distinct()
}

class DefaultAppTierClassifier : AppTierClassifier {
    private val blockedTokens = setOf(
        "bank", "banking", "wallet", "payment", "pay", "crypto", "bitcoin", "authenticator",
        "password", "passkey", "keychain", "security"
    )
    private val cautiousTokens = setOf("health", "medical", "hospital", "clinic", "insurance", "government")
    private val normalSystemPackages = setOf(
        "com.android.settings", "com.google.android.settings", "com.android.chrome", "com.google.android.apps.nexuslauncher"
    )

    override fun classify(packageName: String?): AppTier {
        val pkg = packageName?.lowercase() ?: return AppTier.NORMAL
        if (pkg in normalSystemPackages) return AppTier.NORMAL
        val tokens = pkg.split('.', '_', '-')
        return when {
            tokens.any(blockedTokens::contains) -> AppTier.BLOCKED
            tokens.any(cautiousTokens::contains) -> AppTier.CAUTIOUS
            else -> AppTier.NORMAL
        }
    }
}

class DatabaseToolAuditLogger(
    private val database: AlAgentDatabase,
    private val applicationScope: CoroutineScope
) : ToolAuditLogger {
    override fun record(toolId: String, decision: String, detail: String?) {
        applicationScope.launch {
            database.audit().insert(
                AuditEventEntity(
                    id = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    sessionId = null,
                    taskId = null,
                    turnId = null,
                    component = "PolicyEngine",
                    level = if (decision == "deny") "WARN" else "INFO",
                    eventType = "tool_policy_$decision",
                    metadataJson = "{\"toolId\":\"${toolId.replace("\"", "") }\",\"detail\":\"${detail.orEmpty().replace("\"", "") }\"}"
                )
            )
        }
    }
}

class DatabaseRuntimeContextProvider(private val database: AlAgentDatabase) : RuntimeContextProvider {
    override suspend fun context(state: AgentState): ContextInputs {
        val taskId = state.task.id.value
        val sessionId = state.session.id.value
        val memories = database.memories().recentAny(8).map { "${it.kind}: ${it.text}" }
        val todos = database.taskMemory().todos(taskId).filter { it.status != "DONE" }.map { it.text }
        val scratchpad = database.taskMemory().scratchpad(taskId, 12).asReversed().map { it.text }
        val history = database.messages().recent(sessionId, 20).asReversed().map(MessageEntity::content)
        return ContextInputs(memories, todos, scratchpad, history, state.lastFailure)
    }
}

class WorkspaceCompletionProbe(private val files: AtomicFileStore) : CompletionCriterionProbe {
    override suspend fun fileExists(path: String): Boolean? = runCatching { files.exists(path) }.getOrNull()
    override suspend fun userConfirmed(prompt: String): Boolean? = null
    override suspend fun structuredSatisfied(description: String, observation: ai.alagent.tools.api.ToolObservation): Boolean? = null
}

class DefaultVerificationRoutingContextProvider(
    private val settings: AppSettingsStore,
    private val connectivity: ConnectivityProvider
) : VerificationRoutingContextProvider {
    override suspend fun context(required: Set<ModelCapability>): RoutingContext {
        val current = settings.current()
        return RoutingContext(
            selectedModelId = current.preferredModelId,
            requiredCapabilities = required,
            internetAvailable = connectivity.internetAvailable(),
            privacyMode = current.privacyMode,
            preferLocal = current.preferLocal
        )
    }
}

class DatabaseAgentRunRecorder(private val database: AlAgentDatabase) : AgentRunRecorder {
    override suspend fun sessionStarted(session: AgentSession) {
        database.sessions().insert(
            SessionEntity(session.id.value, session.createdAtEpochMs, session.createdAtEpochMs, session.config.privacyMode)
        )
    }

    override suspend fun taskStarted(task: AgentTask) {
        database.tasks().insert(TaskEntity(task.id.value, task.sessionId.value, task.normalizedGoal, "RUNNING", task.createdAtEpochMs))
    }

    override suspend fun taskFinished(state: AgentState, outcome: AgentRunResult) {
        val status = when (outcome) {
            is AgentRunResult.Success -> "SUCCEEDED"
            is AgentRunResult.Stopped -> "STOPPED:${outcome.reason.name}"
            is AgentRunResult.Failed -> "FAILED:${outcome.reason.name}"
        }
        database.tasks().updateStatus(state.task.id.value, status)
        database.sessions().touch(state.session.id.value, System.currentTimeMillis())
    }
}

class DatabaseTurnRecorder(
    private val database: AlAgentDatabase,
    private val json: Json
) : TurnRecorder {
    override suspend fun record(
        turn: TurnRecord,
        executions: List<ExecutionPhaseResult>,
        finalObservation: ai.alagent.tools.api.ToolObservation
    ) {
        database.withTransaction {
            database.turns().insert(
                TurnEntity(
                    id = turn.turnId,
                    sessionId = turn.sessionId,
                    taskId = turn.taskId,
                    timestamp = turn.timestampEpochMs,
                    model = turn.model,
                    provider = turn.provider,
                    promptVersion = turn.promptVersion,
                    toolDefinitionsJson = json.encodeToString(turn.toolDefinitionIds),
                    inputTokens = turn.inputTokenCount,
                    outputTokens = turn.outputTokenCount,
                    latencyMs = turn.latencyMs,
                    verificationStatus = turn.verificationStatus?.name,
                    retryCount = turn.retryCount,
                    errorsJson = json.encodeToString(turn.errors),
                    stopReason = turn.stopReason?.name
                )
            )
            executions.forEachIndexed { index, execution ->
                val callId = execution.request.callId.value
                database.toolTrace().insertToolCall(
                    ToolCallEntity(
                        id = callId,
                        turnId = turn.turnId,
                        toolId = execution.toolId,
                        argumentsJson = execution.request.arguments.toString(),
                        resultJson = execution.execution.output?.toString(),
                        status = if (execution.execution.success) "EXECUTED" else "FAILED",
                        durationMs = null
                    )
                )
                execution.after?.let { observation ->
                    database.toolTrace().insertObservation(
                        ObservationEntity(
                            id = "obs:$callId:$index",
                            turnId = turn.turnId,
                            kind = "POST_TOOL",
                            summary = observation.summary,
                            payloadJson = observation.structured?.toString(),
                            signature = observation.signature,
                            createdAt = System.currentTimeMillis()
                        )
                    )
                }
                database.toolTrace().insertVerification(
                    VerificationResultEntity(
                        id = "verify:$callId",
                        toolCallId = callId,
                        status = execution.verification.status.name,
                        evidenceJson = json.encodeToString(execution.verification.evidence),
                        reason = execution.verification.reason,
                        createdAt = System.currentTimeMillis()
                    )
                )
            }
            database.toolTrace().insertObservation(
                ObservationEntity(
                    id = "obs:final:${turn.turnId}",
                    turnId = turn.turnId,
                    kind = "FINAL_TURN",
                    summary = finalObservation.summary,
                    payloadJson = finalObservation.structured?.toString(),
                    signature = finalObservation.signature,
                    createdAt = System.currentTimeMillis()
                )
            )
        }
    }
}
