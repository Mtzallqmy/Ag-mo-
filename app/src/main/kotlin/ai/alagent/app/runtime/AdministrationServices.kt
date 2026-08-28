package ai.alagent.app.runtime

import android.content.Context
import android.net.Uri
import ai.alagent.core.database.AlAgentDatabase
import ai.alagent.core.database.ModelDownloadEntity
import ai.alagent.core.database.ModelEntity
import ai.alagent.core.database.ProviderConfigEntity
import ai.alagent.core.database.ProviderEntity
import ai.alagent.core.model.ModelCapability
import ai.alagent.core.security.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class ProviderAdminService(
    private val database: AlAgentDatabase,
    private val secrets: SecretStore,
    private val json: Json
) {
    fun providers() = database.providers().observeProviders()

    suspend fun configure(
        providerId: String,
        enabled: Boolean,
        apiKey: String?,
        baseUrl: String?,
        modelId: String?,
        displayName: String? = null
    ) {
        val dao = database.providers()
        val current = dao.get(providerId) ?: ProviderEntity(providerId, "openai-compatible", providerId, enabled)
        val currentConfig = dao.config(providerId)
        val secretAlias = currentConfig?.secretAlias ?: "provider:$providerId:key"
        dao.save(
            current.copy(enabled = enabled),
            ProviderConfigEntity(
                id = currentConfig?.id ?: "config:$providerId",
                providerId = providerId,
                baseUrl = baseUrl?.trim()?.takeIf(String::isNotEmpty) ?: currentConfig?.baseUrl,
                secretAlias = secretAlias,
                configJson = currentConfig?.configJson ?: "{}"
            )
        )
        apiKey?.takeIf(String::isNotBlank)?.let { secrets.put(secretAlias, it.toByteArray()) }
        modelId?.trim()?.takeIf(String::isNotEmpty)?.let { id ->
            val capabilities = buildSet {
                add(ModelCapability.TEXT)
                add(ModelCapability.STREAMING)
                add(ModelCapability.CLOUD)
                if (current.type == "openai-compatible") add(ModelCapability.TOOL_CALLING)
            }
            database.models().upsert(
                ModelEntity(
                    id = id,
                    providerId = providerId,
                    displayName = displayName?.takeIf(String::isNotBlank) ?: id,
                    family = providerId,
                    format = "remote",
                    quantization = null,
                    sizeBytes = null,
                    contextWindow = 32_768,
                    capabilitiesJson = json.encodeToString(capabilities),
                    metadataJson = buildJsonObject { put("source", "user-configured") }.toString()
                )
            )
        }
    }

    fun hasSecret(providerId: String): Boolean {
        val alias = "provider:$providerId:key"
        return secrets.get(alias)?.isNotEmpty() == true
    }
}

class ModelAdminService(
    private val context: Context,
    private val database: AlAgentDatabase,
    private val json: Json
) {
    fun models(): Flow<List<ModelEntity>> = database.models().observeModels()

    suspend fun importLocalModel(
        uri: Uri,
        displayName: String,
        contextWindow: Int,
        agentCapable: Boolean
    ): String = withContext(Dispatchers.IO) {
        require(contextWindow in 1_024..1_000_000) { "Invalid context window" }
        val resolver = context.contentResolver
        val modelId = "local:${UUID.randomUUID()}"
        val extension = resolver.getType(uri)?.substringAfterLast('/')?.takeIf { it.length <= 12 } ?: "bin"
        val directory = File(context.filesDir, "models").apply { mkdirs() }
        val target = File(directory, modelId.substringAfter(':') + "." + extension)
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Unable to open selected model" }
            target.outputStream().buffered().use { output ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    output.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    size += read
                }
            }
        }
        require(size > 0) { "Selected model is empty" }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        val capabilities = buildSet {
            add(ModelCapability.TEXT)
            add(ModelCapability.STREAMING)
            add(ModelCapability.LOCAL)
            if (agentCapable) add(ModelCapability.TOOL_CALLING)
        }
        database.models().upsert(
            ModelEntity(
                id = modelId,
                providerId = RoomModelSource.LOCAL_PROVIDER_ID,
                displayName = displayName.ifBlank { target.nameWithoutExtension },
                family = "local",
                format = target.extension.ifBlank { "litertlm" },
                quantization = null,
                sizeBytes = size,
                contextWindow = contextWindow,
                capabilitiesJson = json.encodeToString(capabilities),
                metadataJson = buildJsonObject { put("source", "imported"); put("checksumSha256", sha) }.toString()
            )
        )
        database.models().upsertDownload(
            ModelDownloadEntity(
                id = "download:$modelId",
                modelId = modelId,
                url = "content://imported",
                destinationPath = target.absolutePath,
                bytesDownloaded = size,
                totalBytes = size,
                checksum = sha,
                status = "COMPLETED",
                updatedAt = System.currentTimeMillis()
            )
        )
        modelId
    }

    suspend fun deleteLocalModel(modelId: String) = withContext(Dispatchers.IO) {
        database.models().download(modelId)?.destinationPath?.let { runCatching { File(it).delete() } }
        database.models().deleteModel(modelId)
    }
}
