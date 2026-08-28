package ai.alagent.agent.runtime

import ai.alagent.agent.cognition.ContextInputs
import ai.alagent.agent.planning.CompletionCriterion
import ai.alagent.core.model.TurnRecord

fun interface RuntimeContextProvider {
    suspend fun context(state: AgentState): ContextInputs
}

interface RuntimeStateUpdater {
    suspend fun onTurnCompleted(state: AgentState, observation: ai.alagent.tools.api.ToolObservation): AgentState
    object NoOp : RuntimeStateUpdater {
        override suspend fun onTurnCompleted(state: AgentState, observation: ai.alagent.tools.api.ToolObservation) = state
    }
}

interface TurnRecorder {
    suspend fun record(
        turn: TurnRecord,
        executions: List<ExecutionPhaseResult>,
        finalObservation: ai.alagent.tools.api.ToolObservation
    )
    object NoOp : TurnRecorder {
        override suspend fun record(
            turn: TurnRecord,
            executions: List<ExecutionPhaseResult>,
            finalObservation: ai.alagent.tools.api.ToolObservation
        ) = Unit
    }
}

interface AgentRunRecorder {
    suspend fun sessionStarted(session: AgentSession)
    suspend fun taskStarted(task: AgentTask)
    suspend fun taskFinished(state: AgentState, outcome: AgentRunResult)

    object NoOp : AgentRunRecorder {
        override suspend fun sessionStarted(session: AgentSession) = Unit
        override suspend fun taskStarted(task: AgentTask) = Unit
        override suspend fun taskFinished(state: AgentState, outcome: AgentRunResult) = Unit
    }
}

interface CompletionCriterionProbe {
    suspend fun fileExists(path: String): Boolean?
    suspend fun userConfirmed(prompt: String): Boolean?
    suspend fun structuredSatisfied(description: String, observation: ai.alagent.tools.api.ToolObservation): Boolean?
    object Unknown : CompletionCriterionProbe {
        override suspend fun fileExists(path: String) = null
        override suspend fun userConfirmed(prompt: String) = null
        override suspend fun structuredSatisfied(description: String, observation: ai.alagent.tools.api.ToolObservation) = null
    }
}
