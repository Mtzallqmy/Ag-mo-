package ai.alagent.tools.files

import ai.alagent.core.files.AtomicFileStore
import ai.alagent.core.model.RiskLevel
import ai.alagent.core.model.VerificationResult
import ai.alagent.core.model.VerificationStatus
import ai.alagent.tools.api.*
import kotlinx.serialization.json.*

private object FileSchemas {
    fun path(vararg extra: Pair<String, String>): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path") { put("type", "string"); put("maxLength", 1024) }
            extra.forEach { (name, type) -> putJsonObject(name) { put("type", type) } }
        }
        putJsonArray("required") { add(JsonPrimitive("path")) }
        put("additionalProperties", false)
    }
    val transfer = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("from") { put("type", "string") }
            putJsonObject("to") { put("type", "string") }
        }
        putJsonArray("required") { add(JsonPrimitive("from")); add(JsonPrimitive("to")) }
        put("additionalProperties", false)
    }
}

private abstract class StoreTool(
    override val descriptor: ToolDescriptor,
    protected val store: AtomicFileStore
) : Tool {
    override suspend fun observe(request: ToolRequest, execution: ToolExecutionResult): ToolObservation? =
        ToolObservation("file-operation=${descriptor.id} success=${execution.success}", execution.output)
}

private class ReadFileTool(store: AtomicFileStore) : StoreTool(
    ToolDescriptor("read_file", "Read file", "Read a UTF-8 file inside the AL Agent scoped file workspace.", FileSchemas.path(), riskLevel = RiskLevel.READ_ONLY, category = ToolCategory.FILES), store
) {
    override suspend fun execute(request: ToolRequest): ToolExecutionResult = runCatching {
        val path = request.arguments.getValue("path").jsonPrimitive.content
        val bytes = store.read(path)
        buildJsonObject { put("path", path); put("text", bytes.toString(Charsets.UTF_8)); put("sizeBytes", bytes.size); put("sha256", store.sha256(path)) }
    }.fold({ ToolExecutionResult(request.callId, true, it) }, { ToolExecutionResult(request.callId, false, error = it.message) })
    override suspend fun verify(request: ToolRequest, context: VerificationContext) =
        if (context.execution.success) VerificationResult(VerificationStatus.SUCCESS, listOf("File read completed")) else VerificationResult(VerificationStatus.FAILED, reason = context.execution.error)
}

private class WriteFileTool(store: AtomicFileStore) : StoreTool(
    ToolDescriptor("write_file", "Write file", "Atomically write UTF-8 text inside the AL Agent scoped file workspace.", FileSchemas.path("text" to "string"), riskLevel = RiskLevel.MEDIUM, category = ToolCategory.FILES), store
) {
    override suspend fun execute(request: ToolRequest): ToolExecutionResult = runCatching {
        val path = request.arguments.getValue("path").jsonPrimitive.content
        val text = request.arguments.getValue("text").jsonPrimitive.content
        require(text.toByteArray().size <= 4 * 1024 * 1024) { "Write exceeds 4 MiB tool limit" }
        store.write(path, text.toByteArray(Charsets.UTF_8))
        buildJsonObject { put("path", path); put("sha256", store.sha256(path)); put("sizeBytes", text.toByteArray().size) }
    }.fold({ ToolExecutionResult(request.callId, true, it, metadata = mapOf("path" to request.arguments.getValue("path").jsonPrimitive.content)) }, { ToolExecutionResult(request.callId, false, error = it.message) })
    override suspend fun verify(request: ToolRequest, context: VerificationContext): VerificationResult {
        if (!context.execution.success) return VerificationResult(VerificationStatus.FAILED, reason = context.execution.error)
        val path = context.execution.metadata["path"] ?: return VerificationResult(VerificationStatus.UNKNOWN, reason = "Path missing from execution metadata")
        return if (store.exists(path)) VerificationResult(VerificationStatus.SUCCESS, listOf("File exists after atomic write")) else VerificationResult(VerificationStatus.FAILED, reason = "File missing after write")
    }
}

private class ListFilesTool(store: AtomicFileStore) : StoreTool(
    ToolDescriptor("list_files", "List files", "List files inside the AL Agent scoped file workspace.", FileSchemas.path(), riskLevel = RiskLevel.READ_ONLY, category = ToolCategory.FILES), store
) {
    override suspend fun execute(request: ToolRequest): ToolExecutionResult = runCatching {
        val path = request.arguments["path"]?.jsonPrimitive?.content.orEmpty()
        val entries = store.list(path)
        buildJsonObject {
            put("path", path)
            putJsonArray("entries") { entries.forEach { entry -> add(buildJsonObject { put("path", entry.path); put("directory", entry.directory); entry.sizeBytes?.let { put("sizeBytes", it) }; put("modifiedAt", entry.modifiedAtEpochMs) }) } }
        }
    }.fold({ ToolExecutionResult(request.callId, true, it) }, { ToolExecutionResult(request.callId, false, error = it.message) })
    override suspend fun verify(request: ToolRequest, context: VerificationContext) = if (context.execution.success) VerificationResult(VerificationStatus.SUCCESS, listOf("Directory listing captured")) else VerificationResult(VerificationStatus.FAILED, reason = context.execution.error)
}

private class CopyFileTool(store: AtomicFileStore) : StoreTool(
    ToolDescriptor("copy_file", "Copy file", "Copy a file within the AL Agent scoped workspace.", FileSchemas.transfer, riskLevel = RiskLevel.MEDIUM, category = ToolCategory.FILES), store
) {
    override suspend fun execute(request: ToolRequest): ToolExecutionResult = runCatching {
        val from = request.arguments.getValue("from").jsonPrimitive.content
        val to = request.arguments.getValue("to").jsonPrimitive.content
        store.copy(from, to)
        ToolExecutionResult(request.callId, true, metadata = mapOf("to" to to))
    }.getOrElse { ToolExecutionResult(request.callId, false, error = it.message) }
    override suspend fun verify(request: ToolRequest, context: VerificationContext): VerificationResult {
        if (!context.execution.success) return VerificationResult(VerificationStatus.FAILED, reason = context.execution.error)
        val path = context.execution.metadata["to"] ?: return VerificationResult(VerificationStatus.UNKNOWN, reason = "Destination not recorded")
        return if (store.exists(path)) VerificationResult(VerificationStatus.SUCCESS, listOf("Destination exists")) else VerificationResult(VerificationStatus.FAILED, reason = "Destination missing")
    }
}

private class MoveFileTool(store: AtomicFileStore) : StoreTool(
    ToolDescriptor("move_file", "Move file", "Move or rename a file within the AL Agent scoped workspace.", FileSchemas.transfer, riskLevel = RiskLevel.MEDIUM, category = ToolCategory.FILES), store
) {
    override suspend fun execute(request: ToolRequest): ToolExecutionResult = runCatching {
        val from = request.arguments.getValue("from").jsonPrimitive.content
        val to = request.arguments.getValue("to").jsonPrimitive.content
        store.move(from, to)
        ToolExecutionResult(request.callId, true, metadata = mapOf("from" to from, "to" to to))
    }.getOrElse { ToolExecutionResult(request.callId, false, error = it.message) }
    override suspend fun verify(request: ToolRequest, context: VerificationContext): VerificationResult {
        if (!context.execution.success) return VerificationResult(VerificationStatus.FAILED, reason = context.execution.error)
        val from = context.execution.metadata.getValue("from"); val to = context.execution.metadata.getValue("to")
        return if (!store.exists(from) && store.exists(to)) VerificationResult(VerificationStatus.SUCCESS, listOf("Source disappeared and destination exists")) else VerificationResult(VerificationStatus.FAILED, reason = "Move postcondition not satisfied")
    }
}

private class DeleteFileTool(store: AtomicFileStore) : StoreTool(
    ToolDescriptor("delete_file", "Delete file", "Delete a file or directory from the AL Agent scoped workspace.", FileSchemas.path("recursive" to "boolean"), riskLevel = RiskLevel.HIGH, requiresConfirmation = true, category = ToolCategory.FILES), store
) {
    override suspend fun execute(request: ToolRequest): ToolExecutionResult = runCatching {
        val path = request.arguments.getValue("path").jsonPrimitive.content
        val recursive = request.arguments["recursive"]?.jsonPrimitive?.booleanOrNull ?: false
        store.delete(path, recursive)
        ToolExecutionResult(request.callId, true, metadata = mapOf("path" to path))
    }.getOrElse { ToolExecutionResult(request.callId, false, error = it.message) }
    override suspend fun verify(request: ToolRequest, context: VerificationContext): VerificationResult {
        if (!context.execution.success) return VerificationResult(VerificationStatus.FAILED, reason = context.execution.error)
        val path = context.execution.metadata.getValue("path")
        return if (!store.exists(path)) VerificationResult(VerificationStatus.SUCCESS, listOf("Target no longer exists")) else VerificationResult(VerificationStatus.FAILED, reason = "Target still exists")
    }
}

object FileToolFactory {
    fun create(store: AtomicFileStore): List<Tool> = listOf(ReadFileTool(store), WriteFileTool(store), ListFilesTool(store), CopyFileTool(store), MoveFileTool(store), DeleteFileTool(store))
}
