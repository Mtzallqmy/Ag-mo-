package ai.alagent.core.model

import java.util.UUID

@JvmInline value class SessionId(val value: String) { companion object { fun new() = SessionId(UUID.randomUUID().toString()) } }
@JvmInline value class TaskId(val value: String) { companion object { fun new() = TaskId(UUID.randomUUID().toString()) } }
@JvmInline value class TurnId(val value: String) { companion object { fun new() = TurnId(UUID.randomUUID().toString()) } }
@JvmInline value class ToolCallId(val value: String) { companion object { fun new() = ToolCallId(UUID.randomUUID().toString()) } }
@JvmInline value class ModelId(val value: String)
@JvmInline value class ProviderId(val value: String)
