package ai.alagent.app.runtime

import ai.alagent.ai.provider.api.AiMessage
import ai.alagent.ai.provider.api.AiRequest
import ai.alagent.ai.provider.api.AiStreamEvent
import ai.alagent.ai.provider.api.ConnectivityProvider
import ai.alagent.ai.provider.api.ModelRouter
import ai.alagent.ai.provider.api.RoutingContext
import ai.alagent.app.settings.AppSettingsStore
import ai.alagent.core.database.AlAgentDatabase
import ai.alagent.core.database.MessageEntity
import ai.alagent.core.database.SessionEntity
import ai.alagent.core.model.ModelCapability
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatCoordinator @Inject constructor(
    private val database: AlAgentDatabase,
    private val router: ModelRouter,
    private val connectivity: ConnectivityProvider,
    private val settings: AppSettingsStore
) {
    fun send(text: String): Flow<AiStreamEvent> = flow {
        val normalized = text.trim()
        require(normalized.isNotEmpty()) { "Message is empty" }
        ensureSession()
        val now = System.currentTimeMillis()
        database.messages().insert(MessageEntity(UUID.randomUUID().toString(), CHAT_SESSION_ID, "user", normalized, now))

        val history = database.messages().recent(CHAT_SESSION_ID, 32).asReversed().map { message ->
            AiMessage(
                role = when (message.role) {
                    "assistant" -> AiMessage.Role.ASSISTANT
                    "tool" -> AiMessage.Role.TOOL
                    else -> AiMessage.Role.USER
                },
                content = message.content
            )
        }
        val appSettings = settings.current()
        val route = router.route(
            RoutingContext(
                selectedModelId = appSettings.preferredModelId,
                requiredCapabilities = setOf(ModelCapability.TEXT, ModelCapability.STREAMING),
                internetAvailable = connectivity.internetAvailable(),
                privacyMode = appSettings.privacyMode,
                preferLocal = appSettings.preferLocal
            )
        )
        val response = StringBuilder()
        route.provider.stream(AiRequest(route.model, history)).collect { event ->
            if (event is AiStreamEvent.TextDelta) response.append(event.text)
            emit(event)
        }
        if (response.isNotBlank()) {
            database.messages().insert(
                MessageEntity(UUID.randomUUID().toString(), CHAT_SESSION_ID, "assistant", response.toString(), System.currentTimeMillis())
            )
            database.sessions().touch(CHAT_SESSION_ID, System.currentTimeMillis())
        }
    }

    suspend fun recentMessages(limit: Int = 80): List<MessageEntity> {
        ensureSession()
        return database.messages().recent(CHAT_SESSION_ID, limit).asReversed()
    }

    private suspend fun ensureSession() {
        if (database.sessions().get(CHAT_SESSION_ID) == null) {
            val now = System.currentTimeMillis()
            database.sessions().insert(SessionEntity(CHAT_SESSION_ID, now, now, privacyMode = settings.current().privacyMode))
        }
    }

    companion object { const val CHAT_SESSION_ID = "chat:main" }
}
