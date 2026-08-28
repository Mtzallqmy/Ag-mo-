package ai.alagent.agent.planning

import kotlinx.serialization.Serializable

@Serializable enum class StepStatus { PENDING, READY, RUNNING, VERIFYING, SUCCEEDED, PARTIAL, FAILED, BLOCKED, SKIPPED }

@Serializable sealed interface CompletionCriterion {
    @Serializable data class TextAppears(val text: String): CompletionCriterion
    @Serializable data class TextDisappears(val text: String): CompletionCriterion
    @Serializable data class PackageIs(val packageName: String): CompletionCriterion
    @Serializable data class FileExists(val path: String): CompletionCriterion
    @Serializable data class Structured(val description: String): CompletionCriterion
    @Serializable data class UserConfirmed(val prompt: String): CompletionCriterion
}

@Serializable
data class PlanStep(
    val id: String,
    val title: String,
    val objective: String,
    val dependsOn: Set<String> = emptySet(),
    val completionCriteria: List<CompletionCriterion> = emptyList(),
    val status: StepStatus = StepStatus.PENDING,
    val delegated: Boolean = false
)

@Serializable
data class TaskPlan(val id: String, val goal: String, val steps: List<PlanStep>, val version: Int = 1) {
    init { DependencyGraph.validate(steps) }

    fun readySteps(): List<PlanStep> {
        val done = steps.filter { it.status == StepStatus.SUCCEEDED }.map { it.id }.toSet()
        return steps.filter {
            it.status in setOf(StepStatus.PENDING, StepStatus.READY, StepStatus.PARTIAL, StepStatus.FAILED) &&
                it.dependsOn.all(done::contains)
        }
    }

    fun activeStep(): PlanStep? = steps.firstOrNull { it.status in setOf(StepStatus.RUNNING, StepStatus.VERIFYING) }
        ?: readySteps().firstOrNull()

    fun updateStep(stepId: String, status: StepStatus): TaskPlan = copy(
        steps = steps.map { if (it.id == stepId) it.copy(status = status) else it },
        version = version + 1
    )

    fun isComplete(): Boolean = steps.isNotEmpty() && steps.all { it.status in setOf(StepStatus.SUCCEEDED, StepStatus.SKIPPED) }
    fun unresolvedCount(): Int = steps.count { it.status !in setOf(StepStatus.SUCCEEDED, StepStatus.SKIPPED) }
}

object DependencyGraph {
    fun validate(steps: List<PlanStep>) {
        val ids = steps.map { it.id }
        require(ids.size == ids.toSet().size) { "Duplicate step ids" }
        val known = ids.toSet()
        steps.forEach { step ->
            require(step.dependsOn.all(known::contains)) { "Unknown dependency in ${step.id}" }
            require(step.id !in step.dependsOn) { "Step ${step.id} depends on itself" }
        }
        val byId = steps.associateBy { it.id }
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()
        fun visit(id: String) {
            if (id in visited) return
            require(visiting.add(id)) { "Cyclic plan dependency at $id" }
            byId.getValue(id).dependsOn.forEach(::visit)
            visiting.remove(id)
            visited.add(id)
        }
        ids.forEach(::visit)
    }
}
