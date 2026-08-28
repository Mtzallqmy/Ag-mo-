package ai.alagent.app.runtime

import android.content.Context
import android.net.Uri
import ai.alagent.core.database.AlAgentDatabase
import ai.alagent.core.database.MemoryEntity
import ai.alagent.core.database.SkillEntity
import ai.alagent.skills.runtime.SkillManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MemoryAdminService @Inject constructor(private val database: AlAgentDatabase) {
    fun memories(): Flow<List<MemoryEntity>> = database.memories().observeAll()
    suspend fun add(kind: String, text: String) {
        require(text.isNotBlank())
        database.memories().upsert(MemoryEntity(UUID.randomUUID().toString(), kind.trim().ifBlank { "fact" }, text.trim(), 1.0, System.currentTimeMillis()))
    }
    suspend fun delete(id: String) = database.memories().delete(id)
}

@Singleton
class SkillAdminService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AlAgentDatabase,
    private val manager: SkillManager,
    private val json: Json
) {
    fun skills(): Flow<List<SkillEntity>> = database.skills().observeAll()

    suspend fun importZip(uri: Uri) = withContext(Dispatchers.IO) {
        val staging = File(context.cacheDir, "skill-import-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            extractDataOnlyZip(uri, staging)
            val root = resolveSkillRoot(staging)
            val installed = manager.install(root).installed
            val manifest = installed.manifest
            database.skills().upsert(
                SkillEntity(
                    id = database.skills().bySkillId(manifest.id)?.id ?: UUID.randomUUID().toString(),
                    skillId = manifest.id,
                    version = manifest.version,
                    name = manifest.name,
                    description = manifest.description,
                    enabled = true,
                    manifestJson = json.encodeToString(manifest)
                )
            )
        } finally {
            staging.deleteRecursively()
        }
    }

    suspend fun setEnabled(id: String, enabled: Boolean) = database.skills().setEnabled(id, enabled)

    private fun resolveSkillRoot(staging: File): File {
        if (File(staging, "SKILL.md").isFile) return staging
        val dirs = staging.listFiles().orEmpty().filter(File::isDirectory)
        require(dirs.size == 1 && File(dirs.single(), "SKILL.md").isFile) { "ZIP must contain SKILL.md at root or one top-level directory" }
        return dirs.single()
    }

    private fun extractDataOnlyZip(uri: Uri, destination: File) {
        val input = context.contentResolver.openInputStream(uri) ?: error("Unable to open skill ZIP")
        val canonicalRoot = destination.canonicalFile
        var entries = 0
        var totalBytes = 0L
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries++
                require(entries <= 256) { "Skill archive contains too many entries" }
                val target = File(canonicalRoot, entry.name).canonicalFile
                require(target.path == canonicalRoot.path || target.path.startsWith(canonicalRoot.path + File.separator)) { "Archive path traversal blocked" }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    val ext = target.extension.lowercase()
                    require(ext !in setOf("sh","bash","zsh","py","pyc","exe","dll","so","dylib","jar","dex","apk","aab","class","wasm","bin")) { "Executable skill payload blocked" }
                    target.parentFile?.mkdirs()
                    target.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var fileBytes = 0L
                        while (true) {
                            val read = zip.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            fileBytes += read
                            totalBytes += read
                            require(fileBytes <= 5L * 1024 * 1024) { "Skill file exceeds safety limit" }
                            require(totalBytes <= 25L * 1024 * 1024) { "Skill archive exceeds safety limit" }
                            output.write(buffer, 0, read)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
    }
}
