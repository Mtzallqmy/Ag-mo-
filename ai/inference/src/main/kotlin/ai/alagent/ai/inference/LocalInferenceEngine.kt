package ai.alagent.ai.inference

import ai.alagent.core.model.ModelDescriptor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class InferenceConfig(val maxContextTokens: Int, val maxOutputTokens: Int, val temperature: Float = 0.7f)
sealed interface LocalTokenEvent {
    data class Token(val text: String): LocalTokenEvent
    data class Done(val tokensPerSecond: Double?): LocalTokenEvent
}
interface LocalInferenceSession: AutoCloseable {
    val model: ModelDescriptor
    fun generate(prompt: String, config: InferenceConfig): Flow<LocalTokenEvent>
    fun cancel()
}
interface LocalInferenceEngine {
    suspend fun load(model: ModelDescriptor, path: String): LocalInferenceSession
    suspend fun unload(modelId: String)
}
interface LiteRtLmBackend { suspend fun open(modelPath: String, model: ModelDescriptor): LocalInferenceSession }

class LiteRtLocalInferenceEngine(private val backend: LiteRtLmBackend): LocalInferenceEngine {
    private val sessions = mutableMapOf<String, LocalInferenceSession>()
    private val mutex = Mutex()
    override suspend fun load(model: ModelDescriptor, path: String): LocalInferenceSession = mutex.withLock {
        sessions[model.id]?.let { return@withLock it }
        backend.open(path, model).also { sessions[model.id] = it }
    }
    override suspend fun unload(modelId: String) = mutex.withLock { sessions.remove(modelId)?.close() ?: Unit }
}
