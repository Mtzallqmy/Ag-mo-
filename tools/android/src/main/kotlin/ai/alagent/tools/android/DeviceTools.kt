package ai.alagent.tools.android

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.StatFs
import ai.alagent.core.model.RiskLevel
import ai.alagent.core.model.VerificationResult
import ai.alagent.core.model.VerificationStatus
import ai.alagent.tools.api.*
import kotlinx.serialization.json.*

private fun readOnlyDescriptor(id: String, name: String, description: String, input: JsonObject) = ToolDescriptor(
    id = id,
    name = name,
    description = description,
    inputSchema = input,
    riskLevel = RiskLevel.READ_ONLY,
    category = ToolCategory.SYSTEM
)

class DeviceInfoTool(private val context: Context) : Tool {
    override val descriptor = readOnlyDescriptor("device_info", "Device info", "Read non-secret device capabilities required for local model/tool compatibility decisions.", buildJsonObject { put("type", "object") })
    override suspend fun execute(request: ToolRequest): ToolExecutionResult = runCatching {
        val memory = ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        val stat = StatFs(context.filesDir.absolutePath)
        buildJsonObject {
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            put("sdkInt", Build.VERSION.SDK_INT)
            putJsonArray("abis") { Build.SUPPORTED_ABIS.forEach { add(JsonPrimitive(it)) } }
            put("totalRamBytes", memory.totalMem)
            put("availableRamBytes", memory.availMem)
            put("lowMemory", memory.lowMemory)
            put("appStorageAvailableBytes", stat.availableBytes)
            put("appStorageTotalBytes", stat.totalBytes)
        }
    }.fold({ ToolExecutionResult(request.callId, true, it) }, { ToolExecutionResult(request.callId, false, error = it.message) })
    override suspend fun observe(request: ToolRequest, execution: ToolExecutionResult) = ToolObservation("device-info success=${execution.success}", execution.output)
    override suspend fun verify(request: ToolRequest, context: VerificationContext) = if (context.execution.success) VerificationResult(VerificationStatus.SUCCESS, listOf("Device capability snapshot captured")) else VerificationResult(VerificationStatus.FAILED, reason = context.execution.error)
}

class AppInfoTool(private val context: Context) : Tool {
    override val descriptor = readOnlyDescriptor("app_info", "App info", "Read package metadata for one installed application.", buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { putJsonObject("package_name") { put("type", "string") } }
        putJsonArray("required") { add(JsonPrimitive("package_name")) }
        put("additionalProperties", false)
    })
    override suspend fun execute(request: ToolRequest): ToolExecutionResult = runCatching {
        val pkg = request.arguments.getValue("package_name").jsonPrimitive.content
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0)) else @Suppress("DEPRECATION") pm.getPackageInfo(pkg, 0)
        val app = info.applicationInfo
        buildJsonObject {
            put("packageName", pkg)
            put("versionName", info.versionName ?: "")
            put("longVersionCode", info.longVersionCode)
            put("label", app?.loadLabel(pm)?.toString() ?: "")
            put("systemApp", app?.let { (it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 } ?: false)
            put("enabled", app?.enabled ?: false)
        }
    }.fold({ ToolExecutionResult(request.callId, true, it) }, { ToolExecutionResult(request.callId, false, error = it.message) })
    override suspend fun observe(request: ToolRequest, execution: ToolExecutionResult) = ToolObservation("app-info success=${execution.success}", execution.output)
    override suspend fun verify(request: ToolRequest, context: VerificationContext) = if (context.execution.success) VerificationResult(VerificationStatus.SUCCESS, listOf("Package metadata resolved")) else VerificationResult(VerificationStatus.FAILED, reason = context.execution.error)
}

class PackageQueryTool(private val context: Context) : Tool {
    override val descriptor = readOnlyDescriptor("package_query", "Package query", "Search visible installed packages by package id or app label, bounded to 100 results.", buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { putJsonObject("query") { put("type", "string"); put("maxLength", 200) } }
        putJsonArray("required") { add(JsonPrimitive("query")) }
        put("additionalProperties", false)
    })
    override suspend fun execute(request: ToolRequest): ToolExecutionResult = runCatching {
        val query = request.arguments.getValue("query").jsonPrimitive.content.trim()
        val pm = context.packageManager
        val apps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0)) else @Suppress("DEPRECATION") pm.getInstalledApplications(0)
        val matches = apps.asSequence().map { app -> app to app.loadLabel(pm).toString() }
            .filter { (app, label) -> app.packageName.contains(query, true) || label.contains(query, true) }
            .take(100).toList()
        buildJsonObject { put("count", matches.size); putJsonArray("packages") { matches.forEach { (app, label) -> add(buildJsonObject { put("packageName", app.packageName); put("label", label) }) } } }
    }.fold({ ToolExecutionResult(request.callId, true, it) }, { ToolExecutionResult(request.callId, false, error = it.message) })
    override suspend fun observe(request: ToolRequest, execution: ToolExecutionResult) = ToolObservation("package-query success=${execution.success}", execution.output)
    override suspend fun verify(request: ToolRequest, context: VerificationContext) = if (context.execution.success) VerificationResult(VerificationStatus.SUCCESS, listOf("Package query completed")) else VerificationResult(VerificationStatus.FAILED, reason = context.execution.error)
}

object AndroidInfoToolFactory { fun create(context: Context): List<Tool> = listOf(DeviceInfoTool(context), AppInfoTool(context), PackageQueryTool(context)) }
