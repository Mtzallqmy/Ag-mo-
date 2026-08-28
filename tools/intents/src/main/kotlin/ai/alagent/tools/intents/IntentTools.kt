package ai.alagent.tools.intents

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import ai.alagent.core.model.RiskLevel
import ai.alagent.core.model.VerificationResult
import ai.alagent.core.model.VerificationStatus
import ai.alagent.tools.api.*
import kotlinx.serialization.json.*

fun interface ForegroundAppProbe { suspend fun packageName(): String? }

private fun schema(properties: Map<String, String>, required: Set<String> = properties.keys): JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") { properties.forEach { (key, type) -> putJsonObject(key) { put("type", type) } } }
    putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
    put("additionalProperties", false)
}

private abstract class IntentTool(
    override val descriptor: ToolDescriptor,
    protected val context: Context,
    private val foreground: ForegroundAppProbe?
) : Tool {
    protected fun launch(intent: Intent): Boolean = runCatching {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    override suspend fun observe(request: ToolRequest, execution: ToolExecutionResult): ToolObservation? {
        val pkg = foreground?.packageName()
        return ToolObservation("intent=${descriptor.id} success=${execution.success} foreground=${pkg ?: "unknown"}", packageName = pkg)
    }

    protected fun launchVerification(context: VerificationContext, expectedPackage: String? = null): VerificationResult {
        if (!context.execution.success) return VerificationResult(VerificationStatus.FAILED, reason = context.execution.error ?: "Intent launch failed")
        if (expectedPackage != null && context.after?.packageName == expectedPackage) {
            return VerificationResult(VerificationStatus.SUCCESS, listOf("Foreground package is $expectedPackage"))
        }
        val before = context.before?.packageName
        val after = context.after?.packageName
        return if (before != null && after != null && before != after) VerificationResult(VerificationStatus.SUCCESS, listOf("Foreground application changed to $after"))
        else VerificationResult(VerificationStatus.UNKNOWN, listOf("Intent accepted by Android"), "Foreground result could not be proven")
    }
}

class OpenAppTool(context: Context, foreground: ForegroundAppProbe? = null) : IntentTool(
    ToolDescriptor("open_app", "Open app", "Launch an installed application by package name.", schema(mapOf("package_name" to "string")), riskLevel = RiskLevel.LOW, category = ToolCategory.SYSTEM), context, foreground
) {
    override suspend fun execute(request: ToolRequest): ToolExecutionResult {
        val packageName = request.arguments["package_name"]?.jsonPrimitive?.contentOrNull ?: return ToolExecutionResult(request.callId, false, error = "package_name is required")
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return ToolExecutionResult(request.callId, false, error = "Application is not installed or has no launcher activity")
        val ok = launch(intent)
        return ToolExecutionResult(request.callId, ok, metadata = mapOf("expectedPackage" to packageName), error = if (ok) null else "Android rejected application launch")
    }
    override suspend fun verify(request: ToolRequest, context: VerificationContext) = launchVerification(context, context.execution.metadata["expectedPackage"])
}

class OpenUrlTool(context: Context, foreground: ForegroundAppProbe? = null) : IntentTool(
    ToolDescriptor("open_url", "Open URL", "Open an HTTP or HTTPS URL using Android intent resolution.", schema(mapOf("url" to "string")), riskLevel = RiskLevel.LOW, category = ToolCategory.NETWORK), context, foreground
) {
    override suspend fun execute(request: ToolRequest): ToolExecutionResult {
        val raw = request.arguments["url"]?.jsonPrimitive?.contentOrNull ?: return ToolExecutionResult(request.callId, false, error = "url is required")
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return ToolExecutionResult(request.callId, false, error = "Invalid URL")
        if (uri.scheme !in setOf("http", "https")) return ToolExecutionResult(request.callId, false, error = "Only http/https URLs are allowed")
        val ok = launch(Intent(Intent.ACTION_VIEW, uri))
        return ToolExecutionResult(request.callId, ok, error = if (ok) null else "No application could open the URL")
    }
    override suspend fun verify(request: ToolRequest, context: VerificationContext) = launchVerification(context)
}

class ShareTool(context: Context, foreground: ForegroundAppProbe? = null) : IntentTool(
    ToolDescriptor("share", "Share", "Open the Android Sharesheet with explicit text content.", schema(mapOf("text" to "string"), setOf("text")), riskLevel = RiskLevel.HIGH, requiresConfirmation = true, category = ToolCategory.SYSTEM), context, foreground
) {
    override suspend fun execute(request: ToolRequest): ToolExecutionResult {
        val text = request.arguments["text"]?.jsonPrimitive?.contentOrNull ?: return ToolExecutionResult(request.callId, false, error = "text is required")
        require(text.length <= 20_000) { "Share payload exceeds limit" }
        val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
        val ok = launch(Intent.createChooser(send, null))
        return ToolExecutionResult(request.callId, ok, error = if (ok) null else "Sharesheet launch failed")
    }
    override suspend fun verify(request: ToolRequest, context: VerificationContext) = launchVerification(context)
}

class OpenFileTool(context: Context, foreground: ForegroundAppProbe? = null) : IntentTool(
    ToolDescriptor("open_file", "Open file", "Open a content:// URI with a declared MIME type. Raw filesystem paths are rejected.", schema(mapOf("content_uri" to "string", "mime_type" to "string")), riskLevel = RiskLevel.MEDIUM, category = ToolCategory.SYSTEM), context, foreground
) {
    override suspend fun execute(request: ToolRequest): ToolExecutionResult {
        val raw = request.arguments["content_uri"]?.jsonPrimitive?.contentOrNull ?: return ToolExecutionResult(request.callId, false, error = "content_uri is required")
        val mime = request.arguments["mime_type"]?.jsonPrimitive?.contentOrNull ?: "application/octet-stream"
        val uri = Uri.parse(raw)
        if (uri.scheme != "content") return ToolExecutionResult(request.callId, false, error = "Only content:// URIs are allowed")
        val intent = Intent(Intent.ACTION_VIEW).apply { setDataAndType(uri, mime); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val ok = launch(intent)
        return ToolExecutionResult(request.callId, ok, error = if (ok) null else "No application could open the content URI")
    }
    override suspend fun verify(request: ToolRequest, context: VerificationContext) = launchVerification(context)
}

class CreateIntentTool(context: Context, foreground: ForegroundAppProbe? = null) : IntentTool(
    ToolDescriptor(
        "create_intent",
        "Create constrained intent",
        "Launch a policy-constrained Android intent. Arbitrary components and arbitrary actions are not supported.",
        schema(mapOf("action" to "string", "data" to "string", "package_name" to "string"), setOf("action")),
        riskLevel = RiskLevel.HIGH,
        requiresConfirmation = true,
        category = ToolCategory.SYSTEM
    ), context, foreground
) {
    private val allowed = setOf(
        Intent.ACTION_VIEW,
        Intent.ACTION_SENDTO,
        Intent.ACTION_DIAL,
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Settings.ACTION_WIFI_SETTINGS,
        Settings.ACTION_BLUETOOTH_SETTINGS,
        Settings.ACTION_SETTINGS
    )

    override suspend fun execute(request: ToolRequest): ToolExecutionResult {
        val action = request.arguments["action"]?.jsonPrimitive?.contentOrNull ?: return ToolExecutionResult(request.callId, false, error = "action is required")
        if (action !in allowed) return ToolExecutionResult(request.callId, false, error = "Intent action is not allowlisted")
        val data = request.arguments["data"]?.jsonPrimitive?.contentOrNull?.let(Uri::parse)
        val targetPackage = request.arguments["package_name"]?.jsonPrimitive?.contentOrNull
        val intent = Intent(action, data).apply { if (targetPackage != null) setPackage(targetPackage) }
        val ok = launch(intent)
        return ToolExecutionResult(request.callId, ok, metadata = targetPackage?.let { mapOf("expectedPackage" to it) }.orEmpty(), error = if (ok) null else "Intent launch failed")
    }
    override suspend fun verify(request: ToolRequest, context: VerificationContext) = launchVerification(context, context.execution.metadata["expectedPackage"])
}

object IntentToolFactory {
    fun create(context: Context, foreground: ForegroundAppProbe? = null): List<Tool> = listOf(
        OpenAppTool(context, foreground), OpenUrlTool(context, foreground), ShareTool(context, foreground), OpenFileTool(context, foreground), CreateIntentTool(context, foreground)
    )
}
