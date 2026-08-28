package ai.alagent.agent.subagents

import ai.alagent.agent.runtime.AgentExecutionConfig
import ai.alagent.agent.runtime.AgentRunResult
import ai.alagent.agent.runtime.AgentRuntime
import ai.alagent.core.common.ExecutionBudget
import kotlinx.coroutines.withTimeout

data class SubAgentTask(
    val goal: String,
    val allowedToolIds: Set<String>,
    val maxTurns: Int = 6,
    val maxToolCalls: Int = 12,
    val maxInputTokens: Long = 12_000,
    val maxOutputTokens: Long = 4_000,
    val timeoutMs: Long = 90_000
)

data class StructuredSubAgentResult(
    val success: Boolean,
    val summary: String,
    val stopReason: String?,
    val turnsUsed: Int,
    val toolCallsUsed: Int
)

/** A subagent gets a narrower tool allowlist and independent execution budgets by construction. */
class SubAgentRunner(private val runtimeFactory: () -> AgentRuntime) {
    suspend fun run(task: SubAgentTask): StructuredSubAgentResult = withTimeout(task.timeoutMs + 1_000) {
        require(task.allowedToolIds.isNotEmpty()) { "Subagent must have an explicit non-empty tool allowlist" }
        val config = AgentExecutionConfig(
            budget = ExecutionBudget(
                maxTurns = task.maxTurns,
                maxRetriesPerStep = 2,
                maxToolCalls = task.maxToolCalls,
                timeoutMs = task.timeoutMs,
                maxInputTokens = task.maxInputTokens,
                maxOutputTokens = task.maxOutputTokens,
                cloudCostBudgetUsd = 0.50
            ),
            allowedToolIds = task.allowedToolIds
        )
        when (val result = runtimeFactory().run(task.goal, config)) {
            is AgentRunResult.Success -> StructuredSubAgentResult(true, result.message, null, result.state.turnNumber, result.state.toolCallCount)
            is AgentRunResult.Stopped -> StructuredSubAgentResult(false, result.message, result.reason.name, result.state.turnNumber, result.state.toolCallCount)
            is AgentRunResult.Failed -> StructuredSubAgentResult(false, result.error, result.reason.name, result.state.turnNumber, result.state.toolCallCount)
        }
    }
}
