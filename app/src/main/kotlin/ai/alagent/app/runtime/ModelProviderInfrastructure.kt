package ai.alagent.app.runtime

import ai.alagent.core.database.AlAgentDatabase
import ai.alagent.core.database.ModelEntity
import ai.alagent.core.model.Accelerator
import ai.alagent.core.model.ModelCapability
import ai.alagent.core.model.ModelDescriptor
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** Reads model metadata from Room and resolves local model files only after download/import completion. */
class RoomModelSource(
    private val database: AlAgentDatabase,
    private val json: Json
) {
    suspend fun forProvider(providerId: String): List<ModelDescriptor> {
        val provider = database.providers().get(providerId) ?: return emptyList()
        if (!provider.enabled) return emptyList()
        return database.models().listForProvider(providerId).map(::toDescriptor)
    }

    suspend fun local(): List<ModelDescriptor> = forProvider(LOCAL_PROVIDER_ID)

    suspend fun localPath(model: ModelDescriptor): String {
        require(model.supports(ModelCapability.LOCAL)) { "Model is not local" }
        val download = database.models().download(model.id)
            ?: error("Local model has not been imported/downloaded: ${model.id}")
        require(download.status == "COMPLETED") { "Local model is not ready: ${download.status}" }
        val file = File(download.destinationPath)
        require(file.isFile && file.length() > 0L) { "Local model file is missing" }
        return file.absolutePath
    }

    fun toDescriptor(entity: ModelEntity): ModelDescriptor {
        val capabilities = runCatching {
            json.decodeFromString<Set<ModelCapability>>(entity.capabilitiesJson)
        }.getOrDefault(emptySet())
        val accelerators = if (ModelCapability.LOCAL in capabilities) {
            setOf(Accelerator.NPU, Accelerator.GPU, Accelerator.CPU)
        } else {
            setOf(Accelerator.CPU)
        }
        val metadata = runCatching { json.parseToJsonElement(entity.metadataJson).jsonObject }.getOrNull()
        return ModelDescriptor(
            id = entity.id,
            displayName = entity.displayName,
            family = entity.family,
            format = entity.format,
            quantization = entity.quantization,
            sizeBytes = entity.sizeBytes,
            contextWindow = entity.contextWindow,
            source = metadata?.get("source")?.jsonPrimitive?.content,
            checksumSha256 = metadata?.get("checksumSha256")?.jsonPrimitive?.content,
            capabilities = capabilities,
            accelerators = accelerators
        )
    }

    companion object { const val LOCAL_PROVIDER_ID = "local" }
}
