package ai.alagent.ai.inference

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import ai.alagent.core.model.ModelDescriptor
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Production LiteRT-LM adapter with crash-aware accelerator fallback.
 *
 * A backend name is persisted before native initialization. If the process dies while that marker
 * is present, the next launch blacklists that backend and falls back to the next candidate. This
 * cannot make native code infallible, but it prevents a device from repeatedly crashing on startup
 * because of one bad accelerator/driver combination.
 */
class AndroidLiteRtLmBackend(
    private val context: Context
) : LiteRtLmBackend, DefaultLifecycleObserver {
    private val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val sessions = linkedSetOf<LiteRtInferenceSession>()
    private val sessionsMutex = Mutex()

    init {
        recoverNativeCrashMarker()
        runCatching { ProcessLifecycleOwner.get().lifecycle.addObserver(this) }
    }

    override suspend fun open(modelPath: String, model: ModelDescriptor): LocalInferenceSession {
        var lastFailure: Throwable? = null
        for (name in candidates()) {
            try {
                markAttempting(name)
                val session = createSession(modelPath, model, name)
                markSuccess(name)
                sessionsMutex.withLock { sessions += session }
                return session
            } catch (t: Throwable) {
                clearAttempting()
                lastFailure = t
            }
        }
        throw LocalInferenceLoadException("No LiteRT-LM backend could load ${model.displayName}", lastFailure)
    }


    override fun onStop(owner: LifecycleOwner) {
        // Sessions are intentionally retained. Model unloading is controlled by InferenceSessionPool;
        // lifecycle stop alone is not proof that an active foreground agent has finished.
    }

    suspend fun closeAll() {
        val copy = sessionsMutex.withLock { sessions.toList().also { sessions.clear() } }
        copy.forEach { runCatching { it.close() } }
    }

    @OptIn(ExperimentalApi::class)
    private fun createSession(
        modelPath: String,
        model: ModelDescriptor,
        backendName: String
    ): LiteRtInferenceSession {
        val backend = when (backendName) {
            NPU -> Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
            GPU -> Backend.GPU()
            else -> Backend.CPU()
        }
        val engine = Engine(
            EngineConfig(
                modelPath = modelPath,
                backend = backend,
                visionBackend = null,
                audioBackend = null,
                maxNumTokens = model.contextWindow,
                cacheDir = context.cacheDir.absolutePath
            )
        )
        engine.initialize()
        val conversation = engine.createConversation(
            ConversationConfig(
                samplerConfig = if (backendName == NPU) null else SamplerConfig(
                    topK = 40,
                    topP = 0.95,
                    temperature = 0.7
                ),
                tools = emptyList()
            )
        )
        return LiteRtInferenceSession(model, engine, conversation)
    }

    private fun candidates(): List<String> {
        val blocked = preferences.getString(KEY_BLACKLISTED, null)
            ?.split(',')?.filter(String::isNotBlank)?.toSet().orEmpty()
        val lastGood = preferences.getString(KEY_LAST_GOOD, null)
        return buildList {
            if (lastGood != null && lastGood !in blocked) add(lastGood)
            listOf(NPU, GPU, CPU).forEach { if (it !in blocked && it != lastGood) add(it) }
        }
    }

    private fun recoverNativeCrashMarker() {
        val crashed = preferences.getString(KEY_ATTEMPTING, null) ?: return
        val blocked = preferences.getString(KEY_BLACKLISTED, null)
            ?.split(',')?.filter(String::isNotBlank)?.toMutableSet() ?: mutableSetOf()
        blocked += crashed
        preferences.edit()
            .putString(KEY_BLACKLISTED, blocked.joinToString(","))
            .remove(KEY_ATTEMPTING)
            .commit()
    }

    private fun markAttempting(name: String) {
        preferences.edit().putString(KEY_ATTEMPTING, name).commit()
    }

    private fun markSuccess(name: String) {
        preferences.edit().remove(KEY_ATTEMPTING).putString(KEY_LAST_GOOD, name).apply()
    }

    private fun clearAttempting() {
        preferences.edit().remove(KEY_ATTEMPTING).apply()
    }

    private companion object {
        const val PREFS = "al_agent_litert_backend_v1"
        const val KEY_ATTEMPTING = "attempting"
        const val KEY_BLACKLISTED = "blacklisted"
        const val KEY_LAST_GOOD = "last_good"
        const val NPU = "NPU"
        const val GPU = "GPU"
        const val CPU = "CPU"
    }
}

class LocalInferenceLoadException(message: String, cause: Throwable? = null) : Exception(message, cause)

@OptIn(ExperimentalApi::class)
private class LiteRtInferenceSession(
    override val model: ModelDescriptor,
    private val engine: Engine,
    private val conversation: Conversation
) : LocalInferenceSession {
    private val generationMutex = Mutex()
    private val cancelled = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    override fun generate(prompt: String, config: InferenceConfig): Flow<LocalTokenEvent> = callbackFlow {
        require(!closed.get()) { "Inference session is closed" }
        cancelled.set(false)
        val started = System.nanoTime()
        var chunks = 0L
        val finished = AtomicBoolean(false)
        val channel = this

        generationMutex.lock()
        try {
            conversation.sendMessageAsync(
                Contents.of(Content.Text(prompt)),
                object : MessageCallback {
                    override fun onMessage(message: Message) {
                        if (cancelled.get() || closed.get()) return
                        val text = message.toString()
                        if (text.isNotEmpty()) {
                            chunks++
                            channel.trySend(LocalTokenEvent.Token(text))
                        }
                    }

                    override fun onDone() {
                        val seconds = (System.nanoTime() - started) / 1_000_000_000.0
                        val approximateRate = if (seconds > 0.0) chunks / seconds else null
                        finished.set(true)
                        channel.trySend(LocalTokenEvent.Done(approximateRate))
                        generationMutex.unlockSafely()
                        channel.close()
                    }

                    override fun onError(throwable: Throwable) {
                        generationMutex.unlockSafely()
                        channel.close(throwable)
                    }
                }
            )
        } catch (t: Throwable) {
            generationMutex.unlockSafely()
            channel.close(t)
        }

        awaitClose {
            if (!finished.get()) {
                cancelled.set(true)
                runCatching { conversation.close() }
                closed.set(true)
            }
            generationMutex.unlockSafely()
        }
    }

    override fun cancel() {
        cancelled.set(true)
        runCatching { conversation.close() }
        closed.set(true)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { conversation.close() }
        runCatching { engine.close() }
    }

    private fun Mutex.unlockSafely() {
        if (isLocked) runCatching { unlock() }
    }
}
