package ai.alagent.ai.provider.api

import ai.alagent.core.model.ModelCapability
import ai.alagent.core.model.ModelDescriptor
import ai.alagent.tools.api.ToolDescriptor
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

data class AiMessage(val role: Role, val content: String) {
    enum class Role { SYSTEM, USER, ASSISTANT, TOOL }
}

data class AiToolCall(val id: String, val toolId: String, val arguments: JsonObject)
data class TokenUsage(
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val estimatedCostUsd: Double? = null
)

data class AiRequest(
    val model: ModelDescriptor,
    val messages: List<AiMessage>,
    val tools: List<ToolDescriptor> = emptyList(),
    val temperature: Double? = null,
    val maxOutputTokens: Int? = null,
    val responseJsonSchema: JsonObject? = null
)

sealed interface AiStreamEvent {
    data class TextDelta(val text: String): AiStreamEvent
    data class ToolCall(val call: AiToolCall): AiStreamEvent
    data class Usage(val usage: TokenUsage): AiStreamEvent
    data class Completed(val finishReason: String?): AiStreamEvent
}

interface AiProvider {
    val id: String
    suspend fun listModels(): List<ModelDescriptor>
    fun stream(request: AiRequest): Flow<AiStreamEvent>
}

data class RoutingContext(
    val selectedModelId: String?,
    val requiredCapabilities: Set<ModelCapability>,
    val internetAvailable: Boolean,
    val privacyMode: Boolean,
    val preferLocal: Boolean,
    val allowFallback: Boolean = true
)

data class RoutedModel(val provider: AiProvider, val model: ModelDescriptor, val reason: String)
interface ModelRouter { suspend fun route(context: RoutingContext): RoutedModel }
fun interface ConnectivityProvider { fun internetAvailable(): Boolean }

class AiProviderRegistry(providers: Collection<AiProvider>) {
    private val byId = providers.associateBy(AiProvider::id).also {
        require(it.size == providers.size) { "Duplicate AI provider ids" }
    }
    fun all(): List<AiProvider> = byId.values.toList()
    fun get(id: String): AiProvider? = byId[id]
    fun require(id: String): AiProvider = byId[id] ?: error("Unknown AI provider: $id")
}
