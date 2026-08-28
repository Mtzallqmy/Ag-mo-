package ai.alagent.tools.accessibility

import android.accessibilityservice.AccessibilityService
import ai.alagent.core.model.RiskLevel
import ai.alagent.core.model.VerificationResult
import ai.alagent.core.model.VerificationStatus
import ai.alagent.tools.api.Tool
import ai.alagent.tools.api.ToolCategory
import ai.alagent.tools.api.ToolDescriptor
import ai.alagent.tools.api.ToolExecutionResult
import ai.alagent.tools.api.ToolObservation
import ai.alagent.tools.api.ToolRequest
import ai.alagent.tools.api.VerificationContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private object AccessibilitySchemas {
    fun empty(): JsonObject = buildJsonObject { put("type", "object") }
    fun requiredString(name: String): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { putJsonObject(name) { put("type", "string") } }
        putJsonArray("required") { add(JsonPrimitive(name)) }
        put("additionalProperties", false)
    }
    fun coordinates(longPress: Boolean = false): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("x") { put("type", "number") }
            putJsonObject("y") { put("type", "number") }
            if (longPress) putJsonObject("duration_ms") { put("type", "integer"); put("minimum", 300) }
        }
        putJsonArray("required") { add(JsonPrimitive("x")); add(JsonPrimitive("y")) }
        put("additionalProperties", false)
    }
    fun swipe(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            listOf("from_x", "from_y", "to_x", "to_y").forEach { key -> putJsonObject(key) { put("type", "number") } }
            putJsonObject("duration_ms") { put("type", "integer"); put("minimum", 50); put("maximum", 5_000) }
        }
        putJsonArray("required") { listOf("from_x", "from_y", "to_x", "to_y").forEach { add(JsonPrimitive(it)) } }
        put("additionalProperties", false)
    }
    fun typeText(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("node_id") { put("type", "string") }
            putJsonObject("text") { put("type", "string"); put("maxLength", 8_000) }
        }
        putJsonArray("required") { add(JsonPrimitive("node_id")); add(JsonPrimitive("text")) }
        put("additionalProperties", false)
    }
    fun scroll(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("node_id") { put("type", "string") }
            putJsonObject("direction") { put("type", "string"); putJsonArray("enum") { add(JsonPrimitive("forward")); add(JsonPrimitive("backward")) } }
        }
        putJsonArray("required") { add(JsonPrimitive("direction")) }
        put("additionalProperties", false)
    }
}

private fun AccessibilitySnapshot.toStructuredJson(): JsonObject = buildJsonObject {
    put("packageName", packageName ?: "")
    put("window", windowTitle ?: "")
    put("screenSignature", screenSignature)
    put("capturedAt", capturedAtEpochMs)
    putJsonArray("nodes") {
        elements.forEach { element ->
            add(buildJsonObject {
                put("id", element.nodeId)
                put("text", element.text ?: "")
                put("contentDescription", element.contentDescription ?: "")
                put("className", element.className ?: "")
                putJsonObject("bounds") {
                    put("left", element.bounds.left); put("top", element.bounds.top)
                    put("right", element.bounds.right); put("bottom", element.bounds.bottom)
                }
                put("clickable", element.clickable)
                put("editable", element.editable)
                put("enabled", element.enabled)
                put("selected", element.selected)
                element.checked?.let { put("checked", it) }
                put("scrollable", element.scrollable)
            })
        }
    }
}

private fun AccessibilitySnapshot.toObservation(): ToolObservation {
    val summary = buildString {
        append("package=").append(packageName ?: "unknown")
        windowTitle?.let { append(" window=").append(it) }
        append("\nsignature=").append(screenSignature)
        elements.take(80).forEachIndexed { index, element ->
            append("\n[").append(index).append("] id=").append(element.nodeId)
            element.text?.takeIf(String::isNotBlank)?.let { append(" text=\"").append(it.take(160)).append('"') }
            element.contentDescription?.takeIf(String::isNotBlank)?.let { append(" desc=\"").append(it.take(160)).append('"') }
            if (element.clickable) append(" clickable")
            if (element.editable) append(" editable")
            if (element.scrollable) append(" scrollable")
            element.checked?.let { append(" checked=").append(it) }
        }
    }.take(9_000)
    return ToolObservation(summary, toStructuredJson(), screenSignature, packageName)
}

private abstract class AccessibilityTool(
    final override val descriptor: ToolDescriptor,
    protected val provider: AccessibilitySnapshotProvider,
    protected val executor: AccessibilityActionExecutor,
    protected val selector: ElementSelector
) : Tool {
    override suspend fun observe(request: ToolRequest, execution: ToolExecutionResult): ToolObservation? =
        runCatching { provider.capture().toObservation() }.getOrNull()

    protected fun element(nodeId: String): AccessibilityElement? =
        runCatching { provider.capture() }.getOrNull()?.elements?.firstOrNull { it.nodeId == nodeId }

    protected fun actionVerification(context: VerificationContext): VerificationResult {
        if (!context.execution.success) return VerificationResult(VerificationStatus.FAILED, reason = context.execution.error ?: "Action execution failed")
        val before = context.before
        val after = context.after ?: return VerificationResult(VerificationStatus.UNKNOWN, reason = "No post-action accessibility observation")
        val changed = before?.signature != after.signature || before?.packageName != after.packageName
        return if (changed) {
            VerificationResult(VerificationStatus.SUCCESS, evidence = listOf("Post-action screen/package state changed"))
        } else {
            VerificationResult(VerificationStatus.UNKNOWN, evidence = listOf("Action dispatched"), reason = "No observable accessibility state change")
        }
    }
}

private class ReadScreenTool(provider: AccessibilitySnapshotProvider, executor: AccessibilityActionExecutor, selector: ElementSelector) :
    AccessibilityTool(
        ToolDescriptor("read_screen", "Read screen", "Read a compact, pruned accessibility snapshot of the foreground app.", AccessibilitySchemas.empty(), riskLevel = RiskLevel.READ_ONLY, category = ToolCategory.UI),
        provider, executor, selector
    ) {
    override suspend fun execute(request: ToolRequest): ToolExecutionResult = runCatching {
        provider.capture().toStructuredJson()
    }.fold(
        onSuccess = { ToolExecutionResult(request.callId, true, it) },
        onFailure = { ToolExecutionResult(request.callId, false, error = it.message) }
    )

    override suspend fun verify(request: ToolRequest, context: VerificationContext): VerificationResult =
        if (context.execution.success && context.after != null) VerificationResult(VerificationStatus.SUCCESS, listOf("Accessibility snapshot captured"))
        else VerificationResult(VerificationStatus.FAILED, reason = context.execution.error ?: "Screen capture unavailable")
}

private class FindElementTool(provider: AccessibilitySnapshotProvider, executor: AccessibilityActionExecutor, selector: ElementSelector) :
    AccessibilityTool(
        ToolDescriptor("find_element", "Find element", "Find visible accessibility elements by text, content description, class, or node id.", AccessibilitySchemas.requiredString("query"), riskLevel = RiskLevel.READ_ONLY, category = ToolCategory.UI),
        provider, executor, selector
    ) {
    override suspend fun execute(request: ToolRequest): ToolExecutionResult {
        val query = request.arguments["query"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val matches = runCatching { selector.find(provider.capture(), query) }.getOrElse { return ToolExecutionResult(request.callId, false, error = it.message) }
        val output = buildJsonObject {
            put("count", matches.size)
            putJsonArray("matches") { matches.forEach { add(buildJsonObject { put("node_id", it.nodeId); put("text", it.text ?: ""); put("contentDescription", it.contentDescription ?: "") }) } }
        }
        return ToolExecutionResult(request.callId, matches.isNotEmpty(), output, if (matches.isEmpty()) "No matching visible element" else null, mapOf("matchCount" to matches.size.toString()))
    }

    override suspend fun verify(request: ToolRequest, context: VerificationContext): VerificationResult =
        if (context.execution.success) VerificationResult(VerificationStatus.SUCCESS, listOf("Element match found"))
        else VerificationResult(VerificationStatus.FAILED, reason = context.execution.error)
}

private class ClickElementTool(provider: AccessibilitySnapshotProvider, executor: AccessibilityActionExecutor, selector: ElementSelector) :
    AccessibilityTool(
        ToolDescriptor("click_element", "Click element", "Click a visible accessibility element selected by node id from the latest screen snapshot.", AccessibilitySchemas.requiredString("node_id"), riskLevel = RiskLevel.LOW, category = ToolCategory.UI),
        provider, executor, selector
    ) {
    override suspend fun execute(request: ToolRequest): ToolExecutionResult {
        val id = request.arguments["node_id"]?.jsonPrimitive?.contentOrNull ?: return ToolExecutionResult(request.callId, false, error = "node_id is required")
        val target = element(id) ?: return ToolExecutionResult(request.callId, false, error = "Element is stale or not visible")
        val ok = executor.click(target)
        return ToolExecutionResult(request.callId, ok, error = if (ok) null else "Accessibility click action was rejected")
    }
    override suspend fun verify(request: ToolRequest, context: VerificationContext) = actionVerification(context)
}

private class SetTextTool(
    id: String,
    name: String,
    private val clear: Boolean,
    provider: AccessibilitySnapshotProvider,
    executor: AccessibilityActionExecutor,
    selector: ElementSelector
) : AccessibilityTool(
    ToolDescriptor(id, name, if (clear) "Clear an editable accessibility field." else "Set text in an editable accessibility field.", if (clear) AccessibilitySchemas.requiredString("node_id") else AccessibilitySchemas.typeText(), riskLevel = RiskLevel.MEDIUM, category = ToolCategory.UI),
    provider, executor, selector
) {
    override suspend fun execute(request: ToolRequest): ToolExecutionResult {
        val nodeId = request.arguments["node_id"]?.jsonPrimitive?.contentOrNull ?: return ToolExecutionResult(request.callId, false, error = "node_id is required")
        val text = if (clear) "" else request.arguments["text"]?.jsonPrimitive?.contentOrNull ?: return ToolExecutionResult(request.callId, false, error = "text is required")
        val target = element(nodeId) ?: return ToolExecutionResult(request.callId, false, error = "Editable element is stale or not visible")
        if (!target.editable) return ToolExecutionResult(request.callId, false, error = "Target element is not editable")
        val ok = executor.setText(target, text)
        return ToolExecutionResult(request.callId, ok, metadata = mapOf("expectedText" to text), error = if (ok) null else "ACTION_SET_TEXT failed")
    }

    override suspend fun verify(request: ToolRequest, context: VerificationContext): VerificationResult {
        if (!context.execution.success) return VerificationResult(VerificationStatus.FAILED, reason = context.execution.error)
        val expected = context.execution.metadata["expectedText"].orEmpty()
        val after = context.after ?: return VerificationResult(VerificationStatus.UNKNOWN, reason = "No post-action observation")
        val present = if (expected.isEmpty()) true else after.summary.contains(expected, ignoreCase = false)
        return if (present) VerificationResult(VerificationStatus.SUCCESS, listOf(if (expected.isEmpty()) "Text-clear action accepted" else "Expected text visible after edit"))
        else VerificationResult(VerificationStatus.UNKNOWN, reason = "Text action executed but expected field value was not observable")
    }
}

private class TapTool(
    id: String,
    name: String,
    private val longPress: Boolean,
    provider: AccessibilitySnapshotProvider,
    executor: AccessibilityActionExecutor,
    selector: ElementSelector
) : AccessibilityTool(
    ToolDescriptor(id, name, if (longPress) "Long-press screen coordinates." else "Tap screen coordinates.", AccessibilitySchemas.coordinates(longPress), riskLevel = if (longPress) RiskLevel.MEDIUM else RiskLevel.LOW, category = ToolCategory.UI),
    provider, executor, selector
) {
    override suspend fun execute(request: ToolRequest): ToolExecutionResult {
        val x = request.arguments["x"]?.jsonPrimitive?.floatOrNull ?: return ToolExecutionResult(request.callId, false, error = "x is required")
        val y = request.arguments["y"]?.jsonPrimitive?.floatOrNull ?: return ToolExecutionResult(request.callId, false, error = "y is required")
        val duration = request.arguments["duration_ms"]?.jsonPrimitive?.longOrNull ?: 650L
        val ok = if (longPress) executor.longPress(x, y, duration.coerceIn(300, 5_000)) else executor.tap(x, y)
        return ToolExecutionResult(request.callId, ok, error = if (ok) null else "Gesture was rejected")
    }
    override suspend fun verify(request: ToolRequest, context: VerificationContext) = actionVerification(context)
}

private class SwipeTool(provider: AccessibilitySnapshotProvider, executor: AccessibilityActionExecutor, selector: ElementSelector) : AccessibilityTool(
    ToolDescriptor("swipe", "Swipe", "Perform a bounded accessibility gesture swipe.", AccessibilitySchemas.swipe(), riskLevel = RiskLevel.LOW, category = ToolCategory.UI), provider, executor, selector
) {
    override suspend fun execute(request: ToolRequest): ToolExecutionResult {
        fun f(key: String) = request.arguments[key]?.jsonPrimitive?.floatOrNull
        val fromX = f("from_x") ?: return ToolExecutionResult(request.callId, false, error = "from_x is required")
        val fromY = f("from_y") ?: return ToolExecutionResult(request.callId, false, error = "from_y is required")
        val toX = f("to_x") ?: return ToolExecutionResult(request.callId, false, error = "to_x is required")
        val toY = f("to_y") ?: return ToolExecutionResult(request.callId, false, error = "to_y is required")
        val duration = request.arguments["duration_ms"]?.jsonPrimitive?.longOrNull ?: 350L
        val ok = executor.swipe(fromX, fromY, toX, toY, duration.coerceIn(50, 5_000))
        return ToolExecutionResult(request.callId, ok, error = if (ok) null else "Swipe was rejected")
    }
    override suspend fun verify(request: ToolRequest, context: VerificationContext) = actionVerification(context)
}

private class ScrollTool(provider: AccessibilitySnapshotProvider, executor: AccessibilityActionExecutor, selector: ElementSelector) : AccessibilityTool(
    ToolDescriptor("scroll", "Scroll", "Scroll a selected scrollable element, or the first visible scroll container.", AccessibilitySchemas.scroll(), riskLevel = RiskLevel.LOW, category = ToolCategory.UI), provider, executor, selector
) {
    override suspend fun execute(request: ToolRequest): ToolExecutionResult {
        val direction = request.arguments["direction"]?.jsonPrimitive?.contentOrNull ?: return ToolExecutionResult(request.callId, false, error = "direction is required")
        val target = request.arguments["node_id"]?.jsonPrimitive?.contentOrNull?.let(::element)
        val ok = executor.scroll(target, forward = direction == "forward")
        return ToolExecutionResult(request.callId, ok, error = if (ok) null else "No scroll action was accepted")
    }
    override suspend fun verify(request: ToolRequest, context: VerificationContext) = actionVerification(context)
}

private class GlobalActionTool(
    id: String,
    name: String,
    action: Int,
    provider: AccessibilitySnapshotProvider,
    executor: AccessibilityActionExecutor,
    selector: ElementSelector
) : AccessibilityTool(
    ToolDescriptor(id, name, "Perform Android global action: $name.", AccessibilitySchemas.empty(), riskLevel = RiskLevel.LOW, category = ToolCategory.UI), provider, executor, selector
) {
    private val globalAction = action
    override suspend fun execute(request: ToolRequest): ToolExecutionResult {
        val ok = executor.globalAction(globalAction)
        return ToolExecutionResult(request.callId, ok, error = if (ok) null else "Global action was rejected")
    }
    override suspend fun verify(request: ToolRequest, context: VerificationContext) = actionVerification(context)
}

object AccessibilityToolFactory {
    fun create(
        provider: AccessibilitySnapshotProvider = AlAgentAccessibilityService.snapshotProvider,
        executor: AccessibilityActionExecutor = AlAgentAccessibilityService.actionExecutor,
        selector: ElementSelector = ElementSelector()
    ): List<Tool> = listOf(
        ReadScreenTool(provider, executor, selector),
        FindElementTool(provider, executor, selector),
        ClickElementTool(provider, executor, selector),
        TapTool("tap", "Tap", false, provider, executor, selector),
        TapTool("long_press", "Long press", true, provider, executor, selector),
        SetTextTool("type_text", "Type text", false, provider, executor, selector),
        SetTextTool("clear_text", "Clear text", true, provider, executor, selector),
        ScrollTool(provider, executor, selector),
        SwipeTool(provider, executor, selector),
        GlobalActionTool("back", "Back", AccessibilityService.GLOBAL_ACTION_BACK, provider, executor, selector),
        GlobalActionTool("home", "Home", AccessibilityService.GLOBAL_ACTION_HOME, provider, executor, selector),
        GlobalActionTool("recent_apps", "Recent apps", AccessibilityService.GLOBAL_ACTION_RECENTS, provider, executor, selector)
    )
}
