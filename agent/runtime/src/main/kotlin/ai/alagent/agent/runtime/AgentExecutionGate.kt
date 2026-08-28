package ai.alagent.agent.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.asStateFlow

/** Cooperative pause gate. Cancellation still propagates while paused. */
class AgentExecutionGate {
    private val mutablePaused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = mutablePaused.asStateFlow()

    fun pause() { mutablePaused.value = true }
    fun resume() { mutablePaused.value = false }
    suspend fun awaitReady() { mutablePaused.filter { paused -> !paused }.first() }
}
