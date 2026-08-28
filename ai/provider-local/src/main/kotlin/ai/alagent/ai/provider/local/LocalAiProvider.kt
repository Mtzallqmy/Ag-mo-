package ai.alagent.ai.provider.local

import ai.alagent.ai.inference.InferenceConfig
import ai.alagent.ai.inference.LocalInferenceEngine
import ai.alagent.ai.inference.LocalTokenEvent
import ai.alagent.ai.provider.api.AiProvider
import ai.alagent.ai.provider.api.AiRequest
import ai.alagent.ai.provider.api.AiStreamEvent
import ai.alagent.ai.provider.api.AiToolCall
import ai.alagent.core.model.ModelDescriptor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Adapter from on-device inference to the provider contract.
 *
 * When tools are supplied, the local model is instructed to emit a small JSON envelope. This
 * keeps tool invocation provider-neutral and does not rely on model-name heuristics. Models that
 * cannot reliably follow the structured protocol should not advertise TOOL_CALLING capability.
 */
class LocalAiProvider(
    private val catalog: suspend () -> List<ModelDescriptor>,
    private val engine: LocalInferenceEngine,
    private val modelPath: suspend (ModelDescriptor) -> String,
    private val json: Json = Json { ignoreUnknownKeys = true }
) : AiProvider {
    override val id: String = "local"

    override suspend fun listModels(): List<ModelDescriptor> = catalog()

    override fun stream(request: AiRequest): Flow<AiStreamEvent> = flow {
        val session = engine.load(request.model, modelPath(request.model))
        val prompt = buildPrompt(request)
        val response = StringBuilder()
        var tokensPerSecond: Double? = null
        session.generate(
            prompt,
            InferenceConfig(
                maxContextTokens = request.model.contextWindow,
                maxOutputTokens = request.maxOutputTokens ?: 1024,
                temperature = request.temperature?.toFloat() ?: 0.7f
            )
        ).collect { event ->
            when (event) {
                is LocalTokenEvent.Token -> {
                    response.append(event.text)
                    if (request.tools.isEmpty()) emit(AiStreamEvent.TextDelta(event.text))
                }
                is LocalTokenEvent.Done -> tokensPerSecond = event.tokensPerSecond
            }
        }

        if (request.tools.isNotEmpty()) {
            val parsed = parseStructuredResponse(response.toString())
            parsed.text?.takeIf { it.isNotBlank() }?.let { emit(AiStreamEvent.TextDelta(it)) }
            parsed.toolCalls.forEach { emit(AiStreamEvent.ToolCall(it)) }
        }
        emit(AiStreamEvent.Completed(if (tokensPerSecond != null) "local-stop" else "local-stop"))
    }

    private fun buildPrompt(request: AiRequest): String = buildString {
        request.messages.forEach { message ->
            append(message.role.name.lowercase()).append(": ").appendLine(message.content)
        }
        if (request.tools.isNotEmpty()) {
            appendLine()
            appendLine("Available tools (use only these ids):")
            request.tools.forEach { tool ->
                append("- ").append(tool.id).append(": ").append(tool.description)
                    .append(" schema=").appendLine(tool.inputSchema.toString())
            }
            appendLine()
            appendLine("Return exactly one JSON object with this shape:")
            appendLine("{\"text\":\"optional user-facing text\",\"tool_calls\":[{\"id\":\"unique-call-id\",\"tool_id\":\"tool id\",\"arguments\":{}}]}")
            appendLine("If no tool is needed, return an empty tool_calls array. Do not include hidden reasoning.")
        }
    }

    private data class Parsed(val text: String?, val toolCalls: List<AiToolCall>)

    private fun parseStructuredResponse(raw: String): Parsed {
        val objectText = raw.substringAfter('{', missingDelimiterValue = "")
            .takeIf { it.isNotBlank() }
            ?.let { "{" + it.substringBeforeLast('}') + "}" }
            ?: return Parsed(raw.trim().takeIf { it.isNotEmpty() }, emptyList())
        val obj = runCatching { json.parseToJsonElement(objectText).jsonObject }.getOrNull()
            ?: return Parsed(raw.trim().takeIf { it.isNotEmpty() }, emptyList())
        val text = obj["text"]?.jsonPrimitive?.content
        val calls = obj["tool_calls"]?.let { element ->
            runCatching {
                element.jsonArray.mapNotNull { item ->
                    val call = item.jsonObject
                    val id = call["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val toolId = call["tool_id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val arguments = call["arguments"] as? JsonObject ?: buildJsonObject { }
                    AiToolCall(id, toolId, arguments)
                }
            }.getOrDefault(emptyList())
        }.orEmpty()
        return Parsed(text, calls)
    }
}
