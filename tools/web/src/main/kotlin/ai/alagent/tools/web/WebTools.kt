package ai.alagent.tools.web

import ai.alagent.core.files.AtomicFileStore
import ai.alagent.core.model.RiskLevel
import ai.alagent.core.model.VerificationResult
import ai.alagent.core.model.VerificationStatus
import ai.alagent.core.network.SafeNetworkPolicy
import ai.alagent.tools.api.*
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.contentLength
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

class HttpRequestTool(
    private val client: HttpClient,
    private val policy: SafeNetworkPolicy = SafeNetworkPolicy(),
    private val maxResponseBytes: Long = 2L * 1024 * 1024
) : Tool {
    override val descriptor = ToolDescriptor(
        "http_request", "HTTP request", "Send a policy-validated outbound HTTPS request. Local/private network targets are blocked by default.",
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("url") { put("type", "string") }
                putJsonObject("method") { put("type", "string"); putJsonArray("enum") { listOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE").forEach { add(JsonPrimitive(it)) } } }
                putJsonObject("body") { put("type", "string"); put("maxLength", 1_000_000) }
            }
            putJsonArray("required") { add(JsonPrimitive("url")) }
            put("additionalProperties", false)
        },
        riskLevel = RiskLevel.HIGH,
        requiresConfirmation = true,
        timeoutMs = 45_000,
        category = ToolCategory.NETWORK
    )

    override suspend fun execute(request: ToolRequest): ToolExecutionResult = withContext(Dispatchers.IO) {
        runCatching {
            val url = request.arguments.getValue("url").jsonPrimitive.content
            val decision = policy.validate(url)
            require(decision.allowed) { decision.reason ?: "Network target blocked" }
            val methodName = request.arguments["method"]?.jsonPrimitive?.contentOrNull ?: "GET"
            val method = HttpMethod.parse(methodName)
            val body = request.arguments["body"]?.jsonPrimitive?.contentOrNull
            val response = client.request(url) { this.method = method; if (body != null) setBody(body) }
            response.contentLength()?.let { require(it <= maxResponseBytes) { "HTTP response exceeds size limit" } }
            val text = response.bodyAsText()
            require(text.toByteArray().size <= maxResponseBytes) { "HTTP response exceeds size limit" }
            buildJsonObject {
                put("status", response.status.value)
                put("body", text)
                putJsonObject("headers") { response.headers.entries().take(64).forEach { (key, values) -> put(key, values.joinToString(",").take(4_000)) } }
            }
        }.fold({ ToolExecutionResult(request.callId, true, it) }, { ToolExecutionResult(request.callId, false, error = it.message) })
    }

    override suspend fun observe(request: ToolRequest, execution: ToolExecutionResult) = ToolObservation("http-request success=${execution.success}", execution.output)
    override suspend fun verify(request: ToolRequest, context: VerificationContext): VerificationResult {
        if (!context.execution.success) return VerificationResult(VerificationStatus.FAILED, reason = context.execution.error)
        val status = (context.execution.output as? JsonObject)?.get("status")?.jsonPrimitive?.intOrNull
        return if (status != null && status in 200..399) VerificationResult(VerificationStatus.SUCCESS, listOf("HTTP status $status"))
        else VerificationResult(VerificationStatus.PARTIAL, status?.let { listOf("HTTP status $it") }.orEmpty(), "Request completed but response status did not prove application-level success")
    }
}

class DownloadFileTool(
    private val client: HttpClient,
    private val store: AtomicFileStore,
    private val policy: SafeNetworkPolicy = SafeNetworkPolicy(),
    private val maxBytes: Long = 25L * 1024 * 1024
) : Tool {
    override val descriptor = ToolDescriptor(
        "download_file", "Download file", "Download a bounded HTTPS resource into the AL Agent scoped file workspace.",
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("url") { put("type", "string") }
                putJsonObject("path") { put("type", "string") }
                putJsonObject("sha256") { put("type", "string"); put("pattern", "^[A-Fa-f0-9]{64}$") }
            }
            putJsonArray("required") { add(JsonPrimitive("url")); add(JsonPrimitive("path")) }
            put("additionalProperties", false)
        },
        riskLevel = RiskLevel.MEDIUM,
        requiresConfirmation = true,
        timeoutMs = 120_000,
        category = ToolCategory.NETWORK
    )

    override suspend fun execute(request: ToolRequest): ToolExecutionResult = withContext(Dispatchers.IO) {
        runCatching {
            val url = request.arguments.getValue("url").jsonPrimitive.content
            val path = request.arguments.getValue("path").jsonPrimitive.content
            val expected = request.arguments["sha256"]?.jsonPrimitive?.contentOrNull
            val decision = policy.validate(url)
            require(decision.allowed) { decision.reason ?: "Network target blocked" }
            val response = client.request(url) { method = HttpMethod.Get }
            require(response.status.value in 200..299) { "Download failed with HTTP ${response.status.value}" }
            response.contentLength()?.let { require(it <= maxBytes) { "Download exceeds size limit" } }
            val bytes: ByteArray = response.body()
            require(bytes.size <= maxBytes) { "Download exceeds size limit" }
            store.write(path, bytes)
            val actual = store.sha256(path)
            if (expected != null) require(actual.equals(expected, ignoreCase = true)) { "Downloaded file checksum mismatch" }
            ToolExecutionResult(request.callId, true, buildJsonObject { put("path", path); put("sizeBytes", bytes.size); put("sha256", actual) }, metadata = mapOf("path" to path, "sha256" to actual))
        }.getOrElse { ToolExecutionResult(request.callId, false, error = it.message) }
    }

    override suspend fun observe(request: ToolRequest, execution: ToolExecutionResult) = ToolObservation("download-file success=${execution.success}", execution.output)
    override suspend fun verify(request: ToolRequest, context: VerificationContext): VerificationResult {
        if (!context.execution.success) return VerificationResult(VerificationStatus.FAILED, reason = context.execution.error)
        val path = context.execution.metadata["path"] ?: return VerificationResult(VerificationStatus.UNKNOWN, reason = "Download path not recorded")
        val expected = context.execution.metadata["sha256"]
        return if (store.exists(path) && expected != null && store.sha256(path) == expected) VerificationResult(VerificationStatus.SUCCESS, listOf("Downloaded file exists and checksum is stable"))
        else VerificationResult(VerificationStatus.FAILED, reason = "Downloaded file verification failed")
    }
}

object WebToolFactory { fun create(client: HttpClient, store: AtomicFileStore): List<Tool> = listOf(HttpRequestTool(client), DownloadFileTool(client, store)) }
