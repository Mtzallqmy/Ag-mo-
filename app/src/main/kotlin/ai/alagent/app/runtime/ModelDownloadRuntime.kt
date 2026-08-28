package ai.alagent.app.runtime

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ai.alagent.core.database.AlAgentDatabase
import ai.alagent.core.database.ModelDownloadEntity
import ai.alagent.core.database.ModelEntity
import ai.alagent.core.model.ModelCapability
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

@Singleton
class ModelDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AlAgentDatabase,
    private val json: Json,
    private val workManager: WorkManager
) {
    suspend fun enqueue(
        url: String,
        displayName: String,
        contextWindow: Int,
        expectedSha256: String?,
        agentCapable: Boolean
    ): String {
        val uri = URI(url.trim())
        require(uri.scheme.equals("https", ignoreCase = true)) { "Model downloads require HTTPS" }
        require(contextWindow in 1024..1_000_000)
        val checksum = expectedSha256?.trim()?.lowercase()?.takeIf(String::isNotEmpty)
        checksum?.let { require(it.matches(Regex("[0-9a-f]{64}"))) { "SHA-256 must contain 64 hex characters" } }
        val modelId = "local:${UUID.randomUUID()}"
        val modelsDir = File(context.filesDir, "models").apply { mkdirs() }
        val finalFile = File(modelsDir, modelId.substringAfter(':') + ".litertlm")
        val capabilities = buildSet {
            add(ModelCapability.TEXT); add(ModelCapability.STREAMING); add(ModelCapability.LOCAL)
            if (agentCapable) add(ModelCapability.TOOL_CALLING)
        }
        database.models().upsert(
            ModelEntity(
                modelId, RoomModelSource.LOCAL_PROVIDER_ID, displayName.ifBlank { "Downloaded model" },
                "local", "litertlm", null, null, contextWindow, json.encodeToString(capabilities),
                buildJsonObject { put("source", url); checksum?.let { put("checksumSha256", it) } }.toString()
            )
        )
        database.models().upsertDownload(
            ModelDownloadEntity("download:$modelId", modelId, url, finalFile.absolutePath, 0L, null, checksum, "QUEUED", System.currentTimeMillis())
        )
        enqueueWork(modelId)
        return modelId
    }

    suspend fun pause(modelId: String) {
        workManager.cancelUniqueWork(workName(modelId))
        database.models().download(modelId)?.let { database.models().upsertDownload(it.copy(status = "PAUSED", updatedAt = System.currentTimeMillis())) }
    }
    fun resume(modelId: String) = enqueueWork(modelId)
    fun retry(modelId: String) = enqueueWork(modelId)

    suspend fun recoverIncomplete() {
        database.models().interruptedDownloads().forEach { enqueueWork(it.modelId) }
    }

    private fun enqueueWork(modelId: String) {
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setConstraints(androidx.work.Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresStorageNotLow(true).build())
            .setInputData(Data.Builder().putString(ModelDownloadWorker.KEY_MODEL_ID, modelId).build())
            .build()
        workManager.enqueueUniqueWork(workName(modelId), ExistingWorkPolicy.REPLACE, request)
    }

    companion object { fun workName(modelId: String) = "al-agent-model-${modelId.substringAfter(':')}" }
}

class ModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies { fun database(): AlAgentDatabase }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val modelId = inputData.getString(KEY_MODEL_ID) ?: return@withContext Result.failure()
        val db = EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java).database()
        var record = db.models().download(modelId) ?: return@withContext Result.failure()
        val finalFile = File(record.destinationPath)
        val partFile = File(record.destinationPath + ".part")
        finalFile.parentFile?.mkdirs()
        var downloaded = if (partFile.isFile) partFile.length() else 0L
        db.models().upsertDownload(record.copy(bytesDownloaded = downloaded, status = "DOWNLOADING", updatedAt = System.currentTimeMillis()))
        setForeground(foreground(modelId, downloaded, record.totalBytes))
        try {
            val connection = (URI(record.url).toURL().openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 120_000
                instanceFollowRedirects = true
                setRequestProperty("Accept-Encoding", "identity")
                if (downloaded > 0) setRequestProperty("Range", "bytes=$downloaded-")
            }
            connection.connect()
            val response = connection.responseCode
            require(response in 200..299) { "HTTP $response" }
            if (downloaded > 0 && response != HttpURLConnection.HTTP_PARTIAL) {
                partFile.delete()
                downloaded = 0L
            }
            val remaining = connection.contentLengthLong.takeIf { it >= 0 }
            val total = when {
                response == HttpURLConnection.HTTP_PARTIAL && remaining != null -> downloaded + remaining
                remaining != null -> remaining
                else -> record.totalBytes
            }
            if (total != null) {
                val usable = finalFile.parentFile?.usableSpace ?: 0L
                val needed = (total - downloaded).coerceAtLeast(0L)
                require(usable > needed + 64L * 1024 * 1024) { "Insufficient disk space" }
            }
            record = record.copy(totalBytes = total)
            RandomAccessFile(partFile, "rw").use { output ->
                output.seek(downloaded)
                connection.inputStream.buffered().use { input ->
                    val buffer = ByteArray(1024 * 1024)
                    var lastPersist = downloaded
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (downloaded - lastPersist >= 4L * 1024 * 1024) {
                            db.models().upsertDownload(record.copy(bytesDownloaded = downloaded, status = "DOWNLOADING", updatedAt = System.currentTimeMillis()))
                            setForeground(foreground(modelId, downloaded, total))
                            lastPersist = downloaded
                        }
                    }
                }
            }
            total?.let { require(downloaded == it) { "Incomplete download: $downloaded/$it" } }
            record.checksum?.let { expected ->
                val actual = sha256(partFile)
                require(actual.equals(expected, ignoreCase = true)) { "Checksum mismatch" }
            }
            if (finalFile.exists()) finalFile.delete()
            require(partFile.renameTo(finalFile)) { "Unable to finalize model file" }
            db.models().updateSize(modelId, downloaded)
            db.models().upsertDownload(record.copy(bytesDownloaded = downloaded, totalBytes = total ?: downloaded, status = "COMPLETED", updatedAt = System.currentTimeMillis()))
            Result.success()
        } catch (cancelled: CancellationException) {
            db.models().upsertDownload(record.copy(bytesDownloaded = if (partFile.exists()) partFile.length() else downloaded, status = "PAUSED", updatedAt = System.currentTimeMillis()))
            throw cancelled
        } catch (t: Throwable) {
            db.models().upsertDownload(record.copy(bytesDownloaded = if (partFile.exists()) partFile.length() else downloaded, status = "FAILED", updatedAt = System.currentTimeMillis()))
            if (runAttemptCount < 3) Result.retry() else Result.failure(Data.Builder().putString("error", t.message).build())
        }
    }

    private fun foreground(modelId: String, bytes: Long, total: Long?): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Model downloads", NotificationManager.IMPORTANCE_LOW))
        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading local model")
            .setContentText(modelId.takeLast(12))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
        if (total != null && total > 0) builder.setProgress(100, ((bytes * 100 / total).coerceIn(0, 100)).toInt(), false)
        else builder.setProgress(0, 0, true)
        return ForegroundInfo(4301 + modelId.hashCode().and(0x7fff), builder.build())
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).buffered().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val KEY_MODEL_ID = "model_id"
        private const val CHANNEL_ID = "al_agent_model_downloads"
    }
}
