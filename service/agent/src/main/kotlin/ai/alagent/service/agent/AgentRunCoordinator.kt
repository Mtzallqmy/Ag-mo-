package ai.alagent.service.agent

import ai.alagent.agent.runtime.AgentEvent
import ai.alagent.agent.runtime.AgentEventBus
import ai.alagent.agent.runtime.AgentExecutionConfig
import ai.alagent.agent.runtime.AgentExecutionGate
import ai.alagent.agent.runtime.AgentRunResult
import ai.alagent.agent.runtime.AgentRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

enum class RunLifecycle { IDLE, RUNNING, PAUSED, COMPLETED, FAILED, STOPPED }

data class AgentRunStatus(
    val runId: String? = null,
    val goal: String? = null,
    val lifecycle: RunLifecycle = RunLifecycle.IDLE,
    val result: AgentRunResult? = null,
    val error: String? = null
)

/** One foreground agent run at a time. The coordinator owns its structured coroutine scope. */
@Singleton
class AgentRunCoordinator @Inject constructor(
    private val runtime: AgentRuntime,
    private val gate: AgentExecutionGate,
    eventBus: AgentEventBus
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableStatus = MutableStateFlow(AgentRunStatus())
    val status: StateFlow<AgentRunStatus> = mutableStatus.asStateFlow()
    val events: SharedFlow<AgentEvent> = eventBus.events
    private var activeJob: Job? = null
    private val runMutex = Mutex()

    @Synchronized
    fun start(goal: String, config: AgentExecutionConfig): String {
        check(activeJob?.isActive != true) { "An agent run is already active" }
        val runId = UUID.randomUUID().toString()
        gate.resume()
        mutableStatus.value = AgentRunStatus(runId, goal, RunLifecycle.RUNNING)
        activeJob = scope.launch {
            try {
                val result = runMutex.withLock { runtime.run(goal, config) }
                mutableStatus.value = AgentRunStatus(
                    runId = runId,
                    goal = goal,
                    lifecycle = when (result) {
                        is AgentRunResult.Success -> RunLifecycle.COMPLETED
                        is AgentRunResult.Stopped -> RunLifecycle.STOPPED
                        is AgentRunResult.Failed -> RunLifecycle.FAILED
                    },
                    result = result,
                    error = (result as? AgentRunResult.Failed)?.error
                )
            } catch (cancelled: CancellationException) {
                mutableStatus.value = AgentRunStatus(runId, goal, RunLifecycle.STOPPED)
            } catch (t: Throwable) {
                mutableStatus.value = AgentRunStatus(runId, goal, RunLifecycle.FAILED, error = t.message)
            }
        }
        return runId
    }

    /** Runs scheduled/background work through the same process-wide runtime mutex. */
    suspend fun runBackground(goal: String, config: AgentExecutionConfig): AgentRunResult = runMutex.withLock {
        runtime.run(goal, config)
    }

    fun pause() {
        if (activeJob?.isActive == true) {
            gate.pause()
            mutableStatus.value = mutableStatus.value.copy(lifecycle = RunLifecycle.PAUSED)
        }
    }

    fun resume() {
        if (activeJob?.isActive == true) {
            gate.resume()
            mutableStatus.value = mutableStatus.value.copy(lifecycle = RunLifecycle.RUNNING)
        }
    }

    fun stop() {
        gate.resume()
        activeJob?.cancel()
        activeJob = null
        mutableStatus.value = mutableStatus.value.copy(lifecycle = RunLifecycle.STOPPED)
    }
}
