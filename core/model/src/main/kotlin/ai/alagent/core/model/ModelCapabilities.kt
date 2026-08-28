package ai.alagent.core.model

import kotlinx.serialization.Serializable

@Serializable enum class ModelCapability { TEXT, VISION, AUDIO, TOOL_CALLING, STRUCTURED_OUTPUT, JSON_SCHEMA, STREAMING, REASONING, EMBEDDINGS, LONG_CONTEXT, LOCAL, CLOUD }
@Serializable enum class Accelerator { CPU, GPU, NPU }
@Serializable
data class ModelDescriptor(
    val id: String,
    val displayName: String,
    val family: String,
    val format: String,
    val quantization: String? = null,
    val sizeBytes: Long? = null,
    val contextWindow: Int,
    val recommendedRamBytes: Long? = null,
    val minimumRamBytes: Long? = null,
    val source: String? = null,
    val checksumSha256: String? = null,
    val capabilities: Set<ModelCapability>,
    val accelerators: Set<Accelerator> = setOf(Accelerator.CPU)
) { fun supports(capability: ModelCapability) = capability in capabilities }
