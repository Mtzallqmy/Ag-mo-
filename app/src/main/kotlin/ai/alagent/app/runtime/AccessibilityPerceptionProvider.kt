package ai.alagent.app.runtime

import ai.alagent.agent.runtime.AgentState
import ai.alagent.agent.runtime.PerceptionProvider
import ai.alagent.agent.runtime.RuntimeStateUpdater
import ai.alagent.tools.accessibility.AlAgentAccessibilityService
import ai.alagent.tools.api.ToolObservation

class AccessibilityPerceptionProvider : PerceptionProvider {
    override suspend fun currentObservation(): ToolObservation {
        val snapshot = AlAgentAccessibilityService.snapshotProvider.capture()
        val summary = buildString {
            append("package=").append(snapshot.packageName ?: "unknown")
            snapshot.windowTitle?.let { append(" window=").append(it) }
            append("\nsignature=").append(snapshot.screenSignature)
            snapshot.elements.take(80).forEachIndexed { index, element ->
                append("\n[").append(index).append("] id=").append(element.nodeId)
                element.text?.takeIf(String::isNotBlank)?.let { append(" text=\"").append(it.take(160)).append('"') }
                element.contentDescription?.takeIf(String::isNotBlank)?.let { append(" desc=\"").append(it.take(160)).append('"') }
                if (element.clickable) append(" clickable")
                if (element.editable) append(" editable")
                if (element.scrollable) append(" scrollable")
                element.checked?.let { append(" checked=").append(it) }
            }
        }.take(9_000)
        return ToolObservation(
            summary = summary,
            signature = snapshot.screenSignature,
            packageName = snapshot.packageName
        )
    }
}

object AppRuntimeStateUpdater : RuntimeStateUpdater {
    override suspend fun onTurnCompleted(state: AgentState, observation: ToolObservation): AgentState = state
}
