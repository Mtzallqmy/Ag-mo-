package ai.alagent.agent.runtime

import ai.alagent.agent.planning.StepStatus
import ai.alagent.agent.policy.*
import ai.alagent.core.model.*
import ai.alagent.tools.api.ToolObservation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout

class AgentRuntime(
    private val taskController: TaskController,
    private val perception: PerceptionProvider,
    private val planning: PlanningPhaseRunner,
    private val execution: ExecutionPhaseRunner,
    private val verification: VerificationPhaseRunner,
    private val recovery: RecoveryPhaseRunner,
    private val loops: LoopDetectionPolicy,
    private val executionGate: AgentExecutionGate = AgentExecutionGate(),
    private val stateUpdater: RuntimeStateUpdater = RuntimeStateUpdater.NoOp,
    private val turnRecorder: TurnRecorder = TurnRecorder.NoOp,
    private val runRecorder: AgentRunRecorder = AgentRunRecorder.NoOp,
    private val events: AgentEventBus
) {
    suspend fun run(goal: String, config: AgentExecutionConfig = AgentExecutionConfig()): AgentRunResult {
        val session = AgentSession(SessionId.new(), System.currentTimeMillis(), config)
        events.emit(AgentEvent.SessionStarted(session.id))
        runRecorder.sessionStarted(session)
        val task = taskController.create(session, goal)
        events.emit(AgentEvent.TaskStarted(task.id, task.normalizedGoal))
        runRecorder.taskStarted(task)
        var state = AgentState(session, task)

        return try {
            withTimeout(config.budget.timeoutMs) {
                while (state.turnNumber < config.budget.maxTurns) {
                    currentCoroutineContext().ensureActive()
                    executionGate.awaitReady()
                    checkBudgets(state, config)?.let { return@withTimeout finish(AgentRunResult.Stopped(it, "Execution budget exhausted", state), state) }

                    val turnId = TurnId.new()
                    val turnStartedAt = System.currentTimeMillis()
                    events.emit(AgentEvent.TurnStarted(turnId, state.turnNumber + 1))
                    var observation = perception.currentObservation()
                    events.emit(AgentEvent.ObservationCaptured(observation.summary))

                    val planResult = planning.run(state, observation)
                    val usage = planResult.usage
                    state = state.copy(
                        plan = planResult.plan,
                        turnNumber = state.turnNumber + 1,
                        inputTokens = state.inputTokens + (usage?.inputTokens ?: 0),
                        outputTokens = state.outputTokens + (usage?.outputTokens ?: 0),
                        cloudCostUsd = state.cloudCostUsd + (usage?.estimatedCostUsd ?: 0.0)
                    )
                    events.emit(AgentEvent.PlanUpdated("${planResult.plan.steps.size} steps; current=${planResult.currentStep.id}"))

                    val batch = mutableListOf<ExecutionPhaseResult>()
                    for (call in planResult.toolCalls) {
                        executionGate.awaitReady()
                        currentCoroutineContext().ensureActive()
                        if (state.toolCallCount >= config.budget.maxToolCalls) {
                            return@withTimeout finish(AgentRunResult.Stopped(StopReason.MAX_TOOL_CALLS, "Tool-call budget exhausted", state), state)
                        }
                        val result = execution.execute(call, before = observation)
                        batch += result
                        observation = result.after ?: perception.currentObservation()
                        state = state.copy(
                            toolCallCount = state.toolCallCount + 1,
                            navigationHistory = state.navigationHistory + NavigationFingerprint(
                                observation.signature,
                                result.toolId,
                                result.request.arguments.hashCode()
                            )
                        )
                        if (!result.verification.isSuccess) break
                    }

                    // Verification always uses a fresh post-action observation, even if the tool returned an observation.
                    val freshObservation = perception.currentObservation()
                    events.emit(AgentEvent.ObservationCaptured(freshObservation.summary))
                    val stepVerification = verification.verifyStep(planResult.currentStep, batch, freshObservation)
                    events.emit(AgentEvent.VerificationCompleted(stepVerification))

                    var updatedPlan = requireNotNull(state.plan)
                    when (stepVerification.status) {
                        VerificationStatus.SUCCESS -> updatedPlan = updatedPlan.updateStep(planResult.currentStep.id, StepStatus.SUCCEEDED)
                        VerificationStatus.PARTIAL -> updatedPlan = updatedPlan.updateStep(planResult.currentStep.id, StepStatus.PARTIAL)
                        VerificationStatus.FAILED -> updatedPlan = updatedPlan.updateStep(planResult.currentStep.id, StepStatus.FAILED)
                        VerificationStatus.UNKNOWN -> updatedPlan = updatedPlan.updateStep(planResult.currentStep.id, StepStatus.VERIFYING)
                    }
                    state = state.copy(plan = updatedPlan)
                    state = stateUpdater.onTurnCompleted(state, freshObservation)

                    turnRecorder.record(
                        TurnRecord(
                            turnId = turnId.value,
                            sessionId = session.id.value,
                            taskId = task.id.value,
                            timestampEpochMs = turnStartedAt,
                            model = planResult.route.model.id,
                            provider = planResult.route.provider.id,
                            promptVersion = config.promptVersion,
                            toolDefinitionIds = planResult.toolCalls.map { it.toolId }.distinct(),
                            inputTokenCount = usage?.inputTokens,
                            outputTokenCount = usage?.outputTokens,
                            latencyMs = System.currentTimeMillis() - turnStartedAt,
                            toolCallIds = batch.map { it.request.callId.value },
                            verificationStatus = stepVerification.status,
                            retryCount = state.retryCount,
                            errors = batch.mapNotNull { it.execution.error }
                        ),
                        executions = batch,
                        finalObservation = freshObservation
                    )

                    if (stepVerification.isSuccess) {
                        state = state.copy(retryCount = 0, lastFailure = null)
                        if (updatedPlan.isComplete()) {
                            val message = planResult.text.ifBlank { "Goal verified as complete" }
                            events.emit(AgentEvent.Completed(message))
                            return@withTimeout finish(AgentRunResult.Success(message, state), state)
                        }
                        continue
                    }

                    val loop = loops.detect(state.navigationHistory).loop
                    val reason = stepVerification.reason ?: stepVerification.status.name
                    when (val decision = recovery.decide(stepVerification, state.retryCount, config.budget.maxRetriesPerStep, loop)) {
                        RecoveryDecision.Retry -> {
                            state = state.copy(retryCount = state.retryCount + 1, lastFailure = reason)
                            events.emit(AgentEvent.Retry(reason, state.retryCount))
                        }
                        RecoveryDecision.Replan -> {
                            state = state.copy(plan = null, retryCount = state.retryCount + 1, lastFailure = reason)
                            events.emit(AgentEvent.Retry("Replanning: $reason", state.retryCount))
                        }
                        is RecoveryDecision.Stop -> return@withTimeout finish(AgentRunResult.Stopped(decision.reason, reason, state), state)
                    }
                }
                finish(AgentRunResult.Stopped(StopReason.MAX_TURNS, "Turn budget exhausted", state), state)
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            finish(AgentRunResult.Stopped(StopReason.TIMEOUT, "Agent run timed out", state), state)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            events.emit(AgentEvent.Failed(e.message ?: e::class.simpleName.orEmpty()))
            finish(AgentRunResult.Failed(StopReason.MODEL_ERROR, e.message ?: "Unhandled agent failure", state), state)
        }
    }

    private suspend fun finish(outcome: AgentRunResult, state: AgentState): AgentRunResult {
        runCatching { runRecorder.taskFinished(state, outcome) }
        return outcome
    }

    private fun checkBudgets(state: AgentState, config: AgentExecutionConfig): StopReason? {
        val b = config.budget
        if (state.toolCallCount >= b.maxToolCalls) return StopReason.MAX_TOOL_CALLS
        if (state.inputTokens >= b.maxInputTokens || state.outputTokens >= b.maxOutputTokens) return StopReason.TOKEN_BUDGET
        val cloudCostBudgetUsd = b.cloudCostBudgetUsd
        if (cloudCostBudgetUsd != null && state.cloudCostUsd >= cloudCostBudgetUsd) return StopReason.COST_BUDGET
        return null
    }
}
