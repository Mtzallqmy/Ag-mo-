package ai.alagent.service.localapi

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.security.SecureRandom
import java.util.Base64

@Serializable data class Health(val status: String = "ok", val service: String = "AL Agent Local API")
@Serializable data class ChatRequest(val message: String, val sessionId: String? = null, val modelId: String? = null)
@Serializable data class AgentRunRequest(val goal: String, val modelId: String? = null, val allowedTools: Set<String>? = null)
@Serializable data class AgentStopRequest(val runId: String)
@Serializable data class MemorySearchRequest(val query: String, val limit: Int = 10)

/** Non-loopback exposure is forbidden unless the caller explicitly asserts TLS or a secure tunnel. */
data class LocalApiConfig(
    val host: String = "127.0.0.1",
    val port: Int = 8765,
    val remoteAccessEnabled: Boolean = false,
    val secureTransportConfigured: Boolean = false,
    val bearerToken: String = randomToken()
) {
    init {
        val loopback = host == "127.0.0.1" || host == "::1" || host.equals("localhost", ignoreCase = true)
        require(loopback || remoteAccessEnabled) { "Non-loopback binding requires explicit remote access" }
        require(loopback || secureTransportConfigured) { "Non-loopback binding requires TLS or a secure tunnel" }
        require(bearerToken.length >= 32) { "Bearer token is too short" }
    }
    companion object {
        fun randomToken(): String = ByteArray(32)
            .also { SecureRandom().nextBytes(it) }
            .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    }
}

interface LocalAgentApiFacade {
    suspend fun models(): JsonElement
    suspend fun providers(): JsonElement
    suspend fun sessions(): JsonElement
    suspend fun tools(): JsonElement
    suspend fun skills(): JsonElement
    suspend fun status(): JsonElement
    suspend fun chat(request: ChatRequest): JsonElement
    suspend fun run(request: AgentRunRequest): JsonElement
    suspend fun stop(request: AgentStopRequest): JsonElement
    suspend fun searchMemory(request: MemorySearchRequest): JsonElement
    fun events(): Flow<JsonElement>
}

class LocalAgentApiServer(
    private val config: LocalApiConfig,
    private val api: LocalAgentApiFacade,
    private val json: Json = Json { ignoreUnknownKeys = false; explicitNulls = false }
) {
    private var engine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    fun start() {
        check(engine == null) { "LocalAgentApiServer is already running" }
        engine = embeddedServer(CIO, host = config.host, port = config.port) {
            install(ContentNegotiation) { json(json) }
            routing {
                intercept(ApplicationCallPipeline.Plugins) {
                    if (config.remoteAccessEnabled) {
                        val currentCall = context.call
                        val authorization = currentCall.request.header(HttpHeaders.Authorization)
                        if (authorization != "Bearer ${config.bearerToken}") {
                            currentCall.respond(HttpStatusCode.Unauthorized)
                            finish()
                        }
                    }
                }

                get("/health") { call.respond(Health()) }
                get("/models") { call.respond(api.models()) }
                get("/providers") { call.respond(api.providers()) }
                get("/sessions") { call.respond(api.sessions()) }
                post("/chat") { call.respond(api.chat(call.receive<ChatRequest>())) }
                post("/agent/run") { call.respond(api.run(call.receive<AgentRunRequest>())) }
                post("/agent/stop") { call.respond(api.stop(call.receive<AgentStopRequest>())) }
                get("/agent/status") { call.respond(api.status()) }
                get("/tools") { call.respond(api.tools()) }
                get("/skills") { call.respond(api.skills()) }
                post("/memory/search") { call.respond(api.searchMemory(call.receive<MemorySearchRequest>())) }
                get("/events/stream") {
                    call.respondTextWriter(ContentType.Text.EventStream) {
                        api.events().collect { event ->
                            write("data: ")
                            write(json.encodeToString<JsonElement>(event))
                            write("\n\n")
                            flush()
                        }
                    }
                }
            }
        }.start(wait = false)
    }

    fun stop() {
        engine?.stop(500, 1_500)
        engine = null
    }
}