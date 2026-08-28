package ai.alagent.agent.cognition

sealed interface PromptSection { val heading: String; val body: String }
data class Identity(override val body:String):PromptSection{override val heading="Identity"}
data class Goal(override val body:String):PromptSection{override val heading="Goal"}
data class Policies(override val body:String):PromptSection{override val heading="Policies"}
data class TaskPlanSection(override val body:String):PromptSection{override val heading="Task Plan"}
data class CurrentStep(override val body:String):PromptSection{override val heading="Current Step"}
data class Memory(override val body:String):PromptSection{override val heading="Memory"}
data class Todo(override val body:String):PromptSection{override val heading="Todo"}
data class Scratchpad(override val body:String):PromptSection{override val heading="Scratchpad"}
data class ToolInstructions(override val body:String):PromptSection{override val heading="Tool Instructions"}
data class CurrentObservation(override val body:String):PromptSection{override val heading="Current Observation"}
data class RecentHistory(override val body:String):PromptSection{override val heading="Recent History"}
data class FailureContext(override val body:String):PromptSection{override val heading="Failure Context"}
data class CompletionCriteriaSection(override val body:String):PromptSection{override val heading="Completion Criteria"}
data class PromptDocument(val version:String, val sections:List<PromptSection>)
class PromptBuilder {
    fun build(document: PromptDocument): String = buildString {
        appendLine("PROMPT_VERSION: ${document.version}")
        document.sections.filter { it.body.isNotBlank() }.forEach { appendLine(); appendLine("## ${it.heading}"); appendLine(it.body.trim()) }
    }
}
object SystemPromptFactory {
    fun mainV1() = Identity("You are AL Agent. Plan actions, use only eligible tools, verify outcomes from fresh observations, and never expose private chain-of-thought. Provide concise action summaries instead.")
}
