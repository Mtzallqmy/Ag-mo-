package ai.alagent.app.runtime

import android.content.Context
import ai.alagent.ai.provider.api.AiStreamEvent
import ai.alagent.agent.runtime.AgentEvent
import ai.alagent.agent.runtime.AgentExecutionConfig
import ai.alagent.app.settings.AppSettingsStore
import ai.alagent.core.database.AlAgentDatabase
import ai.alagent.service.agent.AgentRunCoordinator
import ai.alagent.service.agent.AgentService
import ai.alagent.service.localapi.AgentRunRequest
import ai.alagent.service.localapi.AgentStopRequest
import ai.alagent.service.localapi.ChatRequest
import ai.alagent.service.localapi.LocalAgentApiFacade
import ai.alagent.service.localapi.LocalAgentApiServer
import ai.alagent.service.localapi.LocalApiConfig
import ai.alagent.service.localapi.MemorySearchRequest
import ai.alagent.tools.api.ToolRegistry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppLocalAgentApiFacade @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AlAgentDatabase,
    private val chat: ChatCoordinator,
    private val runs: AgentRunCoordinator,
    private val toolsRegistry: ToolRegistry,
    private val settings: AppSettingsStore
) : LocalAgentApiFacade {
    override suspend fun models(): JsonElement = buildJsonArray {
        database.models().listModels().forEach { m -> add(buildJsonObject { put("id", m.id); put("name", m.displayName); put("provider", m.providerId); put("capabilities", m.capabilitiesJson) }) }
    }
    override suspend fun providers(): JsonElement = buildJsonArray {
        database.providers().observeProviders().first().forEach { p -> add(buildJsonObject { put("id", p.id); put("name", p.displayName); put("enabled", p.enabled) }) }
    }
    override suspend fun sessions(): JsonElement = buildJsonArray {
        database.sessions().listRecent(50).forEach { s -> add(buildJsonObject { put("id", s.id); put("createdAt", s.createdAt); put("updatedAt", s.updatedAt); put("privacyMode", s.privacyMode) }) }
    }
    override suspend fun tools(): JsonElement = buildJsonArray {
        toolsRegistry.descriptors().forEach { t -> add(buildJsonObject { put("id", t.id); put("name", t.name); put("risk", t.riskLevel.name); put("category", t.category.name) }) }
    }
    override suspend fun skills(): JsonElement = buildJsonArray {
        database.skills().observeAll().first().forEach { s -> add(buildJsonObject { put("id", s.skillId); put("name", s.name); put("version", s.version); put("enabled", s.enabled) }) }
    }
    override suspend fun status(): JsonElement = runs.status.value.let { s -> buildJsonObject { put("runId", s.runId); put("goal", s.goal); put("lifecycle", s.lifecycle.name); put("error", s.error) } }

    override suspend fun chat(request: ChatRequest): JsonElement {
        val text = StringBuilder()
        chat.send(request.message).collect { if (it is AiStreamEvent.TextDelta) text.append(it.text) }
        return buildJsonObject { put("text", text.toString()) }
    }

    override suspend fun run(request: AgentRunRequest): JsonElement {
        val current = settings.current()
        AgentService.start(context)
        val id = runs.start(request.goal, AgentExecutionConfig(privacyMode = current.privacyMode, preferredModelId = request.modelId ?: current.preferredModelId, allowedToolIds = request.allowedTools))
        return buildJsonObject { put("runId", id); put("status", "RUNNING") }
    }

    override suspend fun stop(request: AgentStopRequest): JsonElement {
        val current = runs.status.value
        if (current.runId == request.runId) runs.stop()
        return buildJsonObject { put("runId", request.runId); put("status", runs.status.value.lifecycle.name) }
    }

    override suspend fun searchMemory(request: MemorySearchRequest): JsonElement {
        val terms = request.query.lowercase().split(Regex("\\s+")).filter(String::isNotBlank)
        val matches = database.memories().recentAny(500).asSequence()
            .map { it to terms.count { term -> it.text.lowercase().contains(term) } }
            .filter { terms.isEmpty() || it.second > 0 }
            .sortedByDescending { it.second }
            .take(request.limit.coerceIn(1, 100))
        return buildJsonArray { matches.forEach { (m, score) -> add(buildJsonObject { put("id", m.id); put("kind", m.kind); put("text", m.text); put("score", score) }) } }
    }

    override fun events(): Flow<JsonElement> = runs.events.map { event ->
        buildJsonObject {
            put("type", event::class.simpleName ?: "AgentEvent")
            put("at", event.at)
            put("summary", when (event) {
                is AgentEvent.PlanUpdated -> event.summary
                is AgentEvent.ObservationCaptured -> event.summary
                is AgentEvent.Retry -> event.reason
                is AgentEvent.Completed -> event.message
                is AgentEvent.Failed -> event.reason
                else -> event.toString()
            })
        }
    }
}

@Singleton
class LocalApiController @Inject constructor(
    private val facade: AppLocalAgentApiFacade,
    private val json: Json
) {
    private var server: LocalAgentApiServer? = null
    val running: Boolean get() = server != null

    @Synchronized fun startLoopback() {
        if (server != null) return
        server = LocalAgentApiServer(LocalApiConfig(), facade, json).also { it.start() }
    }
    @Synchronized fun stop() { server?.stop(); server = null }
}
