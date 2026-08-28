package ai.alagent.agent.runtime

import ai.alagent.core.model.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

sealed interface AgentEvent {
    val at:Long
    data class SessionStarted(val sessionId:SessionId, override val at:Long=System.currentTimeMillis()):AgentEvent
    data class TaskStarted(val taskId:TaskId,val goal:String,override val at:Long=System.currentTimeMillis()):AgentEvent
    data class TurnStarted(val turnId:TurnId,val number:Int,override val at:Long=System.currentTimeMillis()):AgentEvent
    data class PlanUpdated(val summary:String,override val at:Long=System.currentTimeMillis()):AgentEvent
    data class ToolStarted(val toolId:String,val callId:ToolCallId,override val at:Long=System.currentTimeMillis()):AgentEvent
    data class ObservationCaptured(val summary:String,override val at:Long=System.currentTimeMillis()):AgentEvent
    data class VerificationCompleted(val result:VerificationResult,override val at:Long=System.currentTimeMillis()):AgentEvent
    data class ApprovalRequired(val request:ApprovalRequest,override val at:Long=System.currentTimeMillis()):AgentEvent
    data class Retry(val reason:String,val count:Int,override val at:Long=System.currentTimeMillis()):AgentEvent
    data class Completed(val message:String,override val at:Long=System.currentTimeMillis()):AgentEvent
    data class Failed(val reason:String,override val at:Long=System.currentTimeMillis()):AgentEvent
}
class AgentEventBus { private val mutable=MutableSharedFlow<AgentEvent>(extraBufferCapacity=128); val events:SharedFlow<AgentEvent> = mutable.asSharedFlow(); suspend fun emit(e:AgentEvent)=mutable.emit(e); fun tryEmit(e:AgentEvent)=mutable.tryEmit(e) }
