package ai.alagent.core.common

data class ExecutionBudget(
    val maxTurns: Int = 24,
    val maxRetriesPerStep: Int = 3,
    val maxToolCalls: Int = 48,
    val timeoutMs: Long = 10 * 60_000L,
    val maxInputTokens: Long = 160_000,
    val maxOutputTokens: Long = 24_000,
    val cloudCostBudgetUsd: Double? = null
) { init { require(maxTurns > 0 && maxRetriesPerStep >= 0 && maxToolCalls > 0 && timeoutMs > 0) } }
