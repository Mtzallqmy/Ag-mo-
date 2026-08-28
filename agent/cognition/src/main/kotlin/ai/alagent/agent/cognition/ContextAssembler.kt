package ai.alagent.agent.cognition

/**
 * Produces bounded, explicit prompt sections. It never serializes a hidden reasoning trace.
 */
data class ContextInputs(
    val memory: List<String> = emptyList(),
    val todos: List<String> = emptyList(),
    val scratchpad: List<String> = emptyList(),
    val recentHistory: List<String> = emptyList(),
    val failureContext: String? = null,
    val maxChars: Int = 24_000
)

class ContextAssembler(private val compactor: ContextCompactor = DefaultContextCompactor()) {
    fun sections(inputs: ContextInputs): List<PromptSection> = buildList {
        if (inputs.memory.isNotEmpty()) add(Memory(compactor.compact(inputs.memory, inputs.maxChars / 4)))
        if (inputs.todos.isNotEmpty()) add(Todo(inputs.todos.joinToString("\n") { "- $it" }))
        if (inputs.scratchpad.isNotEmpty()) add(Scratchpad(compactor.compact(inputs.scratchpad, inputs.maxChars / 6)))
        if (inputs.recentHistory.isNotEmpty()) add(RecentHistory(compactor.compact(inputs.recentHistory, inputs.maxChars / 2)))
        inputs.failureContext?.takeIf(String::isNotBlank)?.let { add(FailureContext(it.take(inputs.maxChars / 4))) }
    }
}

class DefaultContextCompactor : ContextCompactor {
    override fun compact(items: List<String>, maxChars: Int): String {
        if (maxChars <= 0) return ""
        val out = ArrayDeque<String>()
        var size = 0
        for (item in items.asReversed()) {
            val normalized = item.trim()
            if (normalized.isEmpty()) continue
            if (size + normalized.length + 1 > maxChars) break
            out.addFirst(normalized)
            size += normalized.length + 1
        }
        return out.joinToString("\n")
    }
}
