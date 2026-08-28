package ai.alagent.core.model

import kotlinx.serialization.Serializable

@Serializable enum class StopReason { COMPLETED, USER_STOP, MAX_TURNS, MAX_RETRIES, MAX_TOOL_CALLS, TIMEOUT, TOKEN_BUDGET, COST_BUDGET, POLICY_BLOCKED, MODEL_ERROR, TOOL_ERROR, LOOP_DETECTED }
@Serializable
data class TurnRecord(
    val turnId: String,
    val sessionId: String,
    val taskId: String,
    val timestampEpochMs: Long,
    val model: String,
    val provider: String,
    val promptVersion: String,
    val toolDefinitionIds: List<String>,
    val inputTokenCount: Int? = null,
    val outputTokenCount: Int? = null,
    val latencyMs: Long? = null,
    val toolCallIds: List<String> = emptyList(),
    val observationIds: List<String> = emptyList(),
    val verificationStatus: VerificationStatus? = null,
    val retryCount: Int = 0,
    val errors: List<String> = emptyList(),
    val stopReason: StopReason? = null
)
