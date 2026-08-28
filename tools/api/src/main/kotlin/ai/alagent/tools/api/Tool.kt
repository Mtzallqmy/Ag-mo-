package ai.alagent.tools.api

import ai.alagent.core.model.RiskLevel
import ai.alagent.core.model.ToolCallId
import ai.alagent.core.model.VerificationResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

data class ToolDescriptor(
    val id: String,
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val outputSchema: JsonObject? = null,
    val riskLevel: RiskLevel,
    val requiredPermissions: Set<String> = emptySet(),
    val capabilities: Set<String> = emptySet(),
    val timeoutMs: Long = 30_000,
    val requiresConfirmation: Boolean = false,
    val category: ToolCategory = ToolCategory.CORE
)
enum class ToolCategory { CORE, UI, FILES, NETWORK, SYSTEM, MCP, ADVANCED }
data class ToolRequest(val callId: ToolCallId, val arguments: JsonObject, val targetPackage: String? = null)
data class ToolExecutionResult(val callId: ToolCallId, val success: Boolean, val output: JsonElement? = null, val error: String? = null, val metadata: Map<String,String> = emptyMap())
data class ToolObservation(val summary: String, val structured: JsonElement? = null, val signature: String? = null, val packageName: String? = null)
data class VerificationContext(val before: ToolObservation?, val execution: ToolExecutionResult, val after: ToolObservation?)
interface Tool {
    val descriptor: ToolDescriptor
    suspend fun execute(request: ToolRequest): ToolExecutionResult
    suspend fun observe(request: ToolRequest, execution: ToolExecutionResult): ToolObservation?
    suspend fun verify(request: ToolRequest, context: VerificationContext): VerificationResult
}
