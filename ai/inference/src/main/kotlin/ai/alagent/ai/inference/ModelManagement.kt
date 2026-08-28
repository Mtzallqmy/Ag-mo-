package ai.alagent.ai.inference

import ai.alagent.core.model.Accelerator
import ai.alagent.core.model.ModelDescriptor
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/** Catalog is metadata-only: capability routing never relies on string matching model ids. */
interface ModelCatalog {
    suspend fun list(): List<ModelDescriptor>
    suspend fun get(id: String): ModelDescriptor?
}

interface DeviceCapabilityDetector {
    suspend fun snapshot(): DeviceCapabilities
}

data class DeviceCapabilities(
    val totalRamBytes: Long,
    val freeStorageBytes: Long,
    val accelerators: Set<Accelerator>,
    val supportedAbis: List<String>,
    val sdkInt: Int
)

data class ModelCompatibility(
    val compatible: Boolean,
    val reasons: List<String>,
    val preferredAccelerator: Accelerator?
)

class ModelCompatibilityChecker {
    fun check(model: ModelDescriptor, device: DeviceCapabilities): ModelCompatibility {
        val reasons = buildList {
            model.minimumRamBytes?.let { if (device.totalRamBytes < it) add("RAM below model minimum") }
            if (model.sizeBytes != null && device.freeStorageBytes < model.sizeBytes) add("Insufficient free storage")
            if (model.accelerators.isNotEmpty() && model.accelerators.intersect(device.accelerators).isEmpty()) {
                add("No compatible accelerator; CPU fallback not declared")
            }
        }
        val preferred = listOf(Accelerator.NPU, Accelerator.GPU, Accelerator.CPU)
            .firstOrNull { it in model.accelerators && it in device.accelerators }
        return ModelCompatibility(reasons.isEmpty(), reasons, preferred)
    }
}

data class ModelStorageRecord(
    val modelId: String,
    val finalFile: File,
    val partialFile: File,
    val expectedSizeBytes: Long?,
    val expectedSha256: String?
)

class ModelStorageManager(private val root: File) {
    init { root.mkdirs() }

    fun record(modelId: String, fileName: String, sizeBytes: Long?, sha256: String?): ModelStorageRecord {
        val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val dir = File(root, modelId.replace(Regex("[^A-Za-z0-9._-]"), "_")).apply { mkdirs() }
        return ModelStorageRecord(modelId, File(dir, safeName), File(dir, "$safeName.part"), sizeBytes, sha256)
    }

    fun verify(record: ModelStorageRecord): Boolean {
        val file = record.finalFile
        if (!file.isFile) return false
        record.expectedSizeBytes?.let { if (file.length() != it) return false }
        record.expectedSha256?.let { expected ->
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(expected, ignoreCase = true)) return false
        }
        return true
    }

    fun delete(record: ModelStorageRecord) {
        record.finalFile.delete()
        record.partialFile.delete()
    }

    fun partialLength(record: ModelStorageRecord): Long = record.partialFile.takeIf(File::exists)?.length() ?: 0L

    fun append(record: ModelStorageRecord, offset: Long, bytes: ByteArray) {
        RandomAccessFile(record.partialFile, "rw").use { raf ->
            require(raf.length() == offset) { "Partial download offset mismatch" }
            raf.seek(offset)
            raf.write(bytes)
        }
    }

    fun promote(record: ModelStorageRecord) {
        require(record.partialFile.exists()) { "No partial model file" }
        record.expectedSizeBytes?.let { require(record.partialFile.length() == it) { "Model size mismatch" } }
        if (record.finalFile.exists()) require(record.finalFile.delete()) { "Cannot replace model file" }
        require(record.partialFile.renameTo(record.finalFile)) { "Atomic model promotion failed" }
        require(verify(record)) { "Model checksum verification failed" }
    }
}

enum class DownloadState { QUEUED, DOWNLOADING, PAUSED, VERIFYING, COMPLETED, FAILED, CANCELLED }
data class ModelDownloadProgress(val state: DownloadState, val bytesDownloaded: Long, val totalBytes: Long?, val error: String? = null)

/** Transport is deliberately abstract so WorkManager/HTTP implementations can support Range resume. */
interface ModelDownloadTransport {
    suspend fun fetchRange(url: String, offset: Long, onChunk: suspend (ByteArray) -> Unit): Long?
}

class ModelDownloadManager(
    private val storage: ModelStorageManager,
    private val transport: ModelDownloadTransport
) {
    suspend fun download(url: String, record: ModelStorageRecord, onProgress: suspend (ModelDownloadProgress) -> Unit) {
        var offset = storage.partialLength(record)
        onProgress(ModelDownloadProgress(DownloadState.DOWNLOADING, offset, record.expectedSizeBytes))
        runCatching {
            transport.fetchRange(url, offset) { chunk ->
                storage.append(record, offset, chunk)
                offset += chunk.size
                onProgress(ModelDownloadProgress(DownloadState.DOWNLOADING, offset, record.expectedSizeBytes))
            }
            onProgress(ModelDownloadProgress(DownloadState.VERIFYING, offset, record.expectedSizeBytes))
            storage.promote(record)
        }.onSuccess {
            onProgress(ModelDownloadProgress(DownloadState.COMPLETED, offset, record.expectedSizeBytes))
        }.onFailure { error ->
            onProgress(ModelDownloadProgress(DownloadState.FAILED, offset, record.expectedSizeBytes, error.message))
        }
    }
}

/** Bounded session reuse avoids repeated model load and gives one lifecycle authority. */
class InferenceSessionPool(private val engine: LocalInferenceEngine, private val maxLoaded: Int = 2) {
    private data class Entry(val session: LocalInferenceSession, var touched: Long)
    private val mutex = Mutex()
    private val sessions = linkedMapOf<String, Entry>()

    suspend fun acquire(model: ModelDescriptor, path: String): LocalInferenceSession = mutex.withLock {
        sessions[model.id]?.let { entry -> entry.touched = System.nanoTime(); return@withLock entry.session }
        while (sessions.size >= maxLoaded) {
            val victim = sessions.minByOrNull { it.value.touched } ?: break
            engine.unload(victim.key)
            sessions.remove(victim.key)
        }
        engine.load(model, path).also { sessions[model.id] = Entry(it, System.nanoTime()) }
    }

    suspend fun unload(modelId: String) = mutex.withLock {
        sessions.remove(modelId)
        engine.unload(modelId)
    }
}
