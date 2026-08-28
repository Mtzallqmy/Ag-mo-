package ai.alagent.core.files

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** A path-confined store. Relative paths can never escape [root], including through existing symlinks. */
class AtomicFileStore(root: File) {
    private val root = root.canonicalFile.also { it.mkdirs() }

    fun resolve(relative: String): File {
        require(!File(relative).isAbsolute) { "Absolute paths are not allowed" }
        val file = File(root, relative.ifBlank { "." }).canonicalFile
        val prefix = root.path + File.separator
        require(file.path == root.path || file.path.startsWith(prefix)) { "Path escape blocked" }
        return file
    }

    fun write(relative: String, bytes: ByteArray) {
        val target = resolve(relative)
        require(target != root) { "Cannot overwrite storage root" }
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, ".${target.name}.${System.nanoTime()}.tmp")
        tmp.outputStream().buffered().use { it.write(bytes) }
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            tmp.delete()
        }
    }

    fun read(relative: String, maxBytes: Long = 4L * 1024 * 1024): ByteArray {
        val file = resolve(relative)
        require(file.isFile) { "File not found" }
        require(file.length() <= maxBytes) { "File exceeds read limit" }
        return file.readBytes()
    }

    fun list(relative: String = "", limit: Int = 500): List<FileEntry> {
        val directory = resolve(relative)
        require(directory.isDirectory) { "Directory not found" }
        return directory.listFiles().orEmpty()
            .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
            .take(limit.coerceIn(1, 5_000))
            .map { file -> FileEntry(relativePath(file), file.isDirectory, if (file.isFile) file.length() else null, file.lastModified()) }
    }

    fun copy(from: String, to: String) {
        val source = resolve(from)
        val target = resolve(to)
        require(source.isFile) { "Source file not found" }
        target.parentFile?.mkdirs()
        Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    fun move(from: String, to: String) {
        val source = resolve(from)
        val target = resolve(to)
        require(source.exists()) { "Source not found" }
        target.parentFile?.mkdirs()
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun delete(relative: String, recursive: Boolean = false) {
        val target = resolve(relative)
        require(target != root) { "Cannot delete storage root" }
        if (!target.exists()) return
        if (target.isDirectory) {
            require(recursive || target.listFiles().isNullOrEmpty()) { "Directory is not empty; recursive=true required" }
            if (recursive) target.deleteRecursively() else require(target.delete()) { "Delete failed" }
        } else require(target.delete()) { "Delete failed" }
    }

    fun exists(relative: String): Boolean = resolve(relative).exists()

    fun sha256(relative: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        resolve(relative).inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun relativePath(file: File): String = root.toPath().relativize(file.canonicalFile.toPath()).toString().replace(File.separatorChar, '/')
}

data class FileEntry(val path: String, val directory: Boolean, val sizeBytes: Long?, val modifiedAtEpochMs: Long)
