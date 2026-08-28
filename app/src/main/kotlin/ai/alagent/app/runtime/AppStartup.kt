package ai.alagent.app.runtime

import ai.alagent.core.database.AlAgentDatabase
import ai.alagent.core.database.ProviderConfigEntity
import ai.alagent.core.database.ProviderEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppStartup @Inject constructor(
    private val database: AlAgentDatabase,
    private val settings: ai.alagent.app.settings.AppSettingsStore,
    private val localApi: LocalApiController,
    private val downloads: ModelDownloadManager,
    @ai.alagent.app.di.ApplicationScope private val scope: CoroutineScope
) {
    fun initialize() {
        scope.launch {
            seedProviders()
            downloads.recoverIncomplete()
            if (settings.current().localApiEnabled) runCatching { localApi.startLoopback() }
        }
    }

    private suspend fun seedProviders() {
        val definitions = listOf(
            ProviderSeed("openai", "OpenAI", "openai-compatible", "https://api.openai.com/v1", "provider:openai:key"),
            ProviderSeed("openrouter", "OpenRouter", "openai-compatible", "https://openrouter.ai/api/v1", "provider:openrouter:key"),
            ProviderSeed("anthropic", "Anthropic", "anthropic", "https://api.anthropic.com", "provider:anthropic:key"),
            ProviderSeed("gemini", "Gemini", "gemini", "https://generativelanguage.googleapis.com", "provider:gemini:key"),
            ProviderSeed("groq", "Groq", "openai-compatible", "https://api.groq.com/openai/v1", "provider:groq:key"),
            ProviderSeed("deepseek", "DeepSeek", "openai-compatible", "https://api.deepseek.com", "provider:deepseek:key"),
            ProviderSeed("mistral", "Mistral", "openai-compatible", "https://api.mistral.ai/v1", "provider:mistral:key"),
            ProviderSeed("ollama", "Ollama", "openai-compatible", "http://127.0.0.1:11434/v1", null),
            ProviderSeed("custom", "Custom Endpoint", "openai-compatible", null, "provider:custom:key"),
            ProviderSeed("local", "On-device", "local", null, null)
        )
        val dao = database.providers()
        definitions.forEach { seed ->
            if (dao.get(seed.id) == null) {
                dao.save(
                    ProviderEntity(seed.id, seed.type, seed.displayName, enabled = seed.id == "local"),
                    ProviderConfigEntity("config:${seed.id}", seed.id, seed.baseUrl, seed.secretAlias, "{}")
                )
            }
        }
    }

    private data class ProviderSeed(
        val id: String,
        val displayName: String,
        val type: String,
        val baseUrl: String?,
        val secretAlias: String?
    )
}
