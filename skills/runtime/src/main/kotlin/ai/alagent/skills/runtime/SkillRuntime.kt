package ai.alagent.skills.runtime

import ai.alagent.skills.api.SkillManifest
import ai.alagent.skills.api.SkillPackage
import ai.alagent.skills.api.SkillRegistry
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

private val prohibitedExtensions = setOf(
    "sh", "bash", "zsh", "fish", "py", "pyc", "pl", "rb", "php", "exe", "dll", "so", "dylib",
    "jar", "dex", "apk", "aab", "class", "wasm", "bin"
)

class SkillPackageLoader(private val json: Json = Json { ignoreUnknownKeys = false }) {
    fun load(directory: File): SkillPackage {
        require(directory.isDirectory) { "Skill package must be a directory" }
        val canonicalRoot = directory.canonicalFile
        rejectSymlinks(canonicalRoot)
        val markdown = File(canonicalRoot, "SKILL.md")
        require(markdown.isFile) { "SKILL.md is required" }
        val instructions = markdown.readText(Charsets.UTF_8).trim()
        require(instructions.isNotBlank()) { "SKILL.md is empty" }

        val manifestFile = File(canonicalRoot, "skill.json")
        val manifest = if (manifestFile.isFile) {
            json.decodeFromString<SkillManifest>(manifestFile.readText(Charsets.UTF_8))
        } else {
            parseFrontMatter(instructions)
        }
        val assets = childFiles(File(canonicalRoot, "assets"), canonicalRoot)
        val templates = childFiles(File(canonicalRoot, "templates"), canonicalRoot)
        return SkillPackage(manifest, instructions, assets, templates)
    }

    private fun childFiles(directory: File, root: File): List<String> {
        if (!directory.exists()) return emptyList()
        require(directory.isDirectory) { "${directory.name} must be a directory" }
        return directory.walkTopDown()
            .filter(File::isFile)
            .map { file ->
                require(file.canonicalPath.startsWith(root.canonicalPath + File.separator)) { "Skill path escape blocked" }
                require(file.extension.lowercase() !in prohibitedExtensions) { "Executable/binary skill asset blocked: ${file.name}" }
                root.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/')
            }
            .toList()
    }

    private fun rejectSymlinks(root: File) {
        root.walkTopDown().forEach { file ->
            require(!Files.isSymbolicLink(file.toPath())) { "Symbolic links are not allowed in skill packages" }
        }
    }

    private fun parseFrontMatter(markdown: String): SkillManifest {
        val lines = markdown.lineSequence().toList()
        require(lines.firstOrNull()?.trim() == "---") { "skill.json is missing and SKILL.md has no front matter" }
        val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
        require(end >= 0) { "Unterminated SKILL.md front matter" }
        val fields = lines.subList(1, end + 1)
            .mapNotNull { line ->
                val i = line.indexOf(':')
                if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
            }.toMap()
        fun required(key: String) = fields[key]?.takeIf(String::isNotBlank) ?: error("Missing front matter field: $key")
        val tools = fields["allowedTools"].orEmpty().removePrefix("[").removeSuffix("]")
            .split(',').map(String::trim).filter(String::isNotBlank).toSet()
        val permissions = fields["permissions"].orEmpty().removePrefix("[").removeSuffix("]")
            .split(',').map(String::trim).filter(String::isNotBlank).toSet()
        return SkillManifest(
            id = required("id"),
            name = required("name"),
            version = required("version"),
            description = required("description"),
            permissions = permissions,
            allowedTools = tools
        )
    }
}

data class SkillInstallResult(val installed: SkillPackage, val destination: File)

class SkillInstaller(
    private val installRoot: File,
    private val loader: SkillPackageLoader,
    private val validator: SkillValidator,
    private val scanner: SkillSecurityScanner
) {
    init { installRoot.mkdirs() }

    fun install(sourceDirectory: File): SkillInstallResult {
        val candidate = loader.load(sourceDirectory)
        val validation = validator.validate(candidate)
        require(validation.isEmpty()) { validation.joinToString("; ") }
        val scan = scanner.scan(candidate)
        require(scan.safe) { scan.findings.joinToString("; ") }

        val safeId = candidate.manifest.id.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val safeVersion = candidate.manifest.version.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val destination = File(installRoot, "$safeId/$safeVersion").canonicalFile
        require(destination.path.startsWith(installRoot.canonicalFile.path + File.separator)) { "Skill install path escape" }
        val staging = File(installRoot, ".staging-${safeId}-${System.nanoTime()}")
        staging.mkdirs()
        try {
            copyDataOnly(sourceDirectory.canonicalFile, staging)
            loader.load(staging) // Validate the staged bytes, not only the source.
            destination.parentFile?.mkdirs()
            if (destination.exists()) destination.deleteRecursively()
            Files.move(staging.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
        return SkillInstallResult(loader.load(destination), destination)
    }

    private fun copyDataOnly(source: File, destination: File) {
        source.walkTopDown().forEach { item ->
            require(!Files.isSymbolicLink(item.toPath())) { "Symbolic links are not allowed" }
            val relative = source.toPath().relativize(item.toPath()).toString()
            val target = File(destination, relative)
            if (item.isDirectory) {
                target.mkdirs()
            } else {
                require(item.extension.lowercase() !in prohibitedExtensions) { "Executable/binary skill file blocked: ${item.name}" }
                require(item.length() <= 5L * 1024 * 1024) { "Skill file exceeds per-file safety limit" }
                target.parentFile?.mkdirs()
                Files.copy(item.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}

class FileSkillRegistry(
    private val installRoot: File,
    private val loader: SkillPackageLoader
) : SkillRegistry {
    override fun all(): List<SkillPackage> = installRoot.listFiles().orEmpty()
        .filter(File::isDirectory)
        .flatMap { idDir -> idDir.listFiles().orEmpty().filter(File::isDirectory) }
        .mapNotNull { runCatching { loader.load(it) }.getOrNull() }
        .groupBy { it.manifest.id }
        .mapNotNull { (_, versions) -> versions.maxByOrNull { it.manifest.version } }
        .sortedBy { it.manifest.name.lowercase() }

    override fun get(id: String): SkillPackage? = all().firstOrNull { it.manifest.id == id }
}

data class SkillInvocation(
    val skillId: String,
    val instructions: String,
    val allowedTools: Set<String>,
    val permissions: Set<String>
)

/** Skills contribute bounded instructions and an allowlist; they never execute downloaded code. */
class SkillRuntime(private val registry: SkillRegistry) {
    fun activate(skillId: String): SkillInvocation {
        val skill = requireNotNull(registry.get(skillId)) { "Unknown skill: $skillId" }
        return SkillInvocation(
            skillId = skill.manifest.id,
            instructions = skill.instructions,
            allowedTools = skill.manifest.allowedTools,
            permissions = skill.manifest.permissions
        )
    }
}

class SkillManager(
    private val registry: SkillRegistry,
    private val installer: SkillInstaller,
    private val runtime: SkillRuntime
) {
    fun list(): List<SkillPackage> = registry.all()
    fun install(directory: File): SkillInstallResult = installer.install(directory)
    fun activate(id: String): SkillInvocation = runtime.activate(id)
}
