package ai.alagent.tools.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import ai.alagent.core.model.RiskLevel
import ai.alagent.core.model.VerificationResult
import ai.alagent.core.model.VerificationStatus
import ai.alagent.tools.api.*
import kotlinx.serialization.json.*

class ClipboardReadTool(context: Context) : Tool {
    private val clipboard = context.getSystemService(ClipboardManager::class.java)
    override val descriptor = ToolDescriptor(
        id = "clipboard_read",
        name = "Read clipboard",
        description = "Read the current plain-text clipboard. Clipboard contents can be sensitive.",
        inputSchema = buildJsonObject { put("type", "object") },
        riskLevel = RiskLevel.MEDIUM,
        requiresConfirmation = true,
        category = ToolCategory.SYSTEM
    )

    override suspend fun execute(request: ToolRequest): ToolExecutionResult = runCatching {
        val clip = clipboard.primaryClip
        val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(null)?.toString().orEmpty() else ""
        require(text.length <= 100_000) { "Clipboard text exceeds safety limit" }
        buildJsonObject { put("text", text); put("length", text.length) }
    }.fold({ ToolExecutionResult(request.callId, true, it) }, { ToolExecutionResult(request.callId, false, error = it.message) })

    override suspend fun observe(request: ToolRequest, execution: ToolExecutionResult) = ToolObservation("clipboard-read success=${execution.success}", execution.output)
    override suspend fun verify(request: ToolRequest, context: VerificationContext) = if (context.execution.success) VerificationResult(VerificationStatus.SUCCESS, listOf("Clipboard read completed")) else VerificationResult(VerificationStatus.FAILED, reason = context.execution.error)
}

class ClipboardWriteTool(context: Context) : Tool {
    private val clipboard = context.getSystemService(ClipboardManager::class.java)
    override val descriptor = ToolDescriptor(
        id = "clipboard_write",
        name = "Write clipboard",
        description = "Replace the clipboard with explicit plain text.",
        inputSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") { putJsonObject("text") { put("type", "string"); put("maxLength", 100_000) } }
            putJsonArray("required") { add(JsonPrimitive("text")) }
            put("additionalProperties", false)
        },
        riskLevel = RiskLevel.LOW,
        category = ToolCategory.SYSTEM
    )

    override suspend fun execute(request: ToolRequest): ToolExecutionResult = runCatching {
        val text = request.arguments.getValue("text").jsonPrimitive.content
        clipboard.setPrimaryClip(ClipData.newPlainText("AL Agent", text))
        ToolExecutionResult(request.callId, true, metadata = mapOf("text" to text))
    }.getOrElse { ToolExecutionResult(request.callId, false, error = it.message) }

    override suspend fun observe(request: ToolRequest, execution: ToolExecutionResult): ToolObservation? {
        val actual = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        return ToolObservation("clipboard-write observable=${actual != null}", buildJsonObject { put("text", actual ?: "") })
    }

    override suspend fun verify(request: ToolRequest, context: VerificationContext): VerificationResult {
        if (!context.execution.success) return VerificationResult(VerificationStatus.FAILED, reason = context.execution.error)
        val expected = context.execution.metadata["text"].orEmpty()
        val actual = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        return if (actual == expected) VerificationResult(VerificationStatus.SUCCESS, listOf("Clipboard matches requested text")) else VerificationResult(VerificationStatus.FAILED, reason = "Clipboard verification mismatch")
    }
}

object ClipboardToolFactory { fun create(context: Context): List<Tool> = listOf(ClipboardReadTool(context), ClipboardWriteTool(context)) }
