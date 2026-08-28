package ai.alagent.agent.planning

data class PlanningContext(val goal: String, val currentObservation: String?, val priorPlan: TaskPlan? = null, val failureContext: String? = null)
interface Planner { suspend fun plan(context: PlanningContext): TaskPlan }
class DirectPlanner : Planner {
    override suspend fun plan(context: PlanningContext) = TaskPlan("direct", context.goal, listOf(PlanStep("step-1", context.goal, context.goal, completionCriteria=listOf(CompletionCriterion.Structured("Goal is verifiably satisfied")))))
}
