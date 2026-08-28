package ai.alagent.agent.runtime

import ai.alagent.core.common.ExecutionBudget
import ai.alagent.core.model.*
import ai.alagent.agent.planning.TaskPlan

data class AgentExecutionConfig(
    val budget: ExecutionBudget = ExecutionBudget(),
    val privacyMode: Boolean = false,
    val preferredModelId: String? = null,
    val allowedToolIds: Set<String>? = null,
    val promptVersion: String = "agent_main_v1"
)

data class AgentSession(val id: SessionId, val createdAtEpochMs: Long, val config: AgentExecutionConfig)
data class AgentTask(
    val id: TaskId,
    val sessionId: SessionId,
    val originalGoal: String,
    val normalizedGoal: String,
    val createdAtEpochMs: Long
)

data class AgentState(
    val session: AgentSession,
    val task: AgentTask,
    val plan: TaskPlan? = null,
    val turnNumber: Int = 0,
    val retryCount: Int = 0,
    val toolCallCount: Int = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cloudCostUsd: Double = 0.0,
    val navigationHistory: List<ai.alagent.agent.policy.NavigationFingerprint> = emptyList(),
    val lastFailure: String? = null
)

sealed interface AgentRunResult {
    data class Success(val message: String, val state: AgentState): AgentRunResult
    data class Stopped(val reason: StopReason, val message: String, val state: AgentState): AgentRunResult
    data class Failed(val reason: StopReason, val error: String, val state: AgentState): AgentRunResult
}
