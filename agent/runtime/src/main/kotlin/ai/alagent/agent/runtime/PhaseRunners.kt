package ai.alagent.agent.runtime

import ai.alagent.ai.provider.api.*
import ai.alagent.agent.cognition.*
import ai.alagent.agent.planning.*
import ai.alagent.agent.policy.*
import ai.alagent.core.model.*
import ai.alagent.tools.api.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout

interface PerceptionProvider { suspend fun currentObservation(): ToolObservation }

data class PlanningPhaseResult(
    val plan: TaskPlan,
    val currentStep: PlanStep,
    val route: RoutedModel,
    val toolCalls: List<AiToolCall>,
    val text: String,
    val usage: TokenUsage?
)

class PlanningPhaseRunner(
    private val planner: Planner,
    private val router: ModelRouter,
    private val promptBuilder: PromptBuilder,
    private val contextAssembler: ContextAssembler,
    private val contextProvider: RuntimeContextProvider,
    private val connectivity: ConnectivityProvider,
    private val toolFilter: ToolEligibilityFilter,
    private val registry: ToolRegistry
) {
    suspend fun run(state: AgentState, observation: ToolObservation): PlanningPhaseResult {
        var plan = state.plan ?: planner.plan(
            PlanningContext(state.task.normalizedGoal, observation.summary, failureContext = state.lastFailure)
        )
        val current = requireNotNull(plan.activeStep()) { "Planner produced no executable step" }
        plan = plan.updateStep(current.id, StepStatus.RUNNING)
        val active = plan.steps.first { it.id == current.id }

        val policyEligible = toolFilter.eligible(state.task.normalizedGoal + " " + active.objective, registry.descriptors())
        val eligible = state.session.config.allowedToolIds?.let { allowlist ->
            policyEligible.filter { it.id in allowlist }
        } ?: policyEligible
        val required = buildSet {
            add(ModelCapability.TEXT)
            if (eligible.isNotEmpty()) add(ModelCapability.TOOL_CALLING)
        }
        val route = router.route(
            RoutingContext(
                selectedModelId = state.session.config.preferredModelId,
                requiredCapabilities = required,
                internetAvailable = connectivity.internetAvailable(),
                privacyMode = state.session.config.privacyMode,
                preferLocal = true
            )
        )
        val runtimeContext = contextProvider.context(state)
        val sections = buildList<PromptSection> {
            add(SystemPromptFactory.mainV1())
            add(Goal(state.task.normalizedGoal))
            add(TaskPlanSection(plan.steps.joinToString("\n") { "${it.id}: ${it.objective} [${it.status}] deps=${it.dependsOn}" }))
            add(CurrentStep(active.objective))
            addAll(contextAssembler.sections(runtimeContext))
            add(ToolInstructions("Use only eligible tools. Prefer the smallest sufficient action. After every action, wait for fresh observation and verification. Never claim completion based only on execute() success."))
            add(CurrentObservation(observation.summary))
            add(CompletionCriteriaSection(active.completionCriteria.joinToString("\n") { "- $it" }))
        }
        val prompt = promptBuilder.build(PromptDocument(state.session.config.promptVersion, sections))
        val events = route.provider.stream(
            AiRequest(
                model = route.model,
                messages = listOf(
                    AiMessage(AiMessage.Role.SYSTEM, prompt),
                    AiMessage(AiMessage.Role.USER, state.task.normalizedGoal)
                ),
                tools = eligible
            )
        ).toList()
        val eligibleIds = eligible.mapTo(mutableSetOf()) { it.id }
        val authorizedCalls = events.filterIsInstance<AiStreamEvent.ToolCall>()
            .map { it.call }
            .filter { it.toolId in eligibleIds }
        return PlanningPhaseResult(
            plan = plan,
            currentStep = active,
            route = route,
            toolCalls = authorizedCalls,
            text = events.filterIsInstance<AiStreamEvent.TextDelta>().joinToString("") { it.text },
            usage = events.filterIsInstance<AiStreamEvent.Usage>().lastOrNull()?.usage
        )
    }
}

data class ExecutionPhaseResult(
    val toolId: String,
    val request: ToolRequest,
    val execution: ToolExecutionResult,
    val before: ToolObservation?,
    val after: ToolObservation?,
    val verification: VerificationResult
)

class ExecutionPhaseRunner(
    private val registry: ToolRegistry,
    private val preconditions: ToolPreconditionValidator,
    private val policy: PolicyEngine,
    private val approvals: ApprovalManager,
    private val eventBus: AgentEventBus
) {
    suspend fun execute(call: AiToolCall, before: ToolObservation?, targetPackage: String? = before?.packageName): ExecutionPhaseResult {
        val tool = registry.require(call.toolId)
        val req = ToolRequest(ToolCallId(call.id), call.arguments, targetPackage)
        preconditions.validate(tool.descriptor, req)?.let { failure ->
            return ExecutionPhaseResult(
                tool.descriptor.id, req,
                ToolExecutionResult(req.callId, false, error = failure), before, before,
                VerificationResult(VerificationStatus.FAILED, reason = failure)
            )
        }
        when (val decision = policy.evaluate(tool.descriptor, req)) {
            is PolicyDecision.Deny -> return ExecutionPhaseResult(
                tool.descriptor.id, req,
                ToolExecutionResult(req.callId, false, error = decision.reason), before, before,
                VerificationResult(VerificationStatus.FAILED, reason = decision.reason)
            )
            is PolicyDecision.RequireApproval -> {
                eventBus.emit(AgentEvent.ApprovalRequired(decision.request))
                if (approvals.request(decision.request) == ApprovalChoice.DENY) {
                    return ExecutionPhaseResult(
                        tool.descriptor.id, req,
                        ToolExecutionResult(req.callId, false, error = "User denied"), before, before,
                        VerificationResult(VerificationStatus.FAILED, reason = "User denied")
                    )
                }
            }
            PolicyDecision.Allow -> Unit
        }
        eventBus.emit(AgentEvent.ToolStarted(tool.descriptor.id, req.callId))
        return runCatching {
            withTimeout(tool.descriptor.timeoutMs) {
                val execution = tool.execute(req)
                val after = runCatching { tool.observe(req, execution) }.getOrNull()
                after?.let { eventBus.emit(AgentEvent.ObservationCaptured(it.summary)) }
                val toolVerification = tool.verify(req, VerificationContext(before, execution, after))
                eventBus.emit(AgentEvent.VerificationCompleted(toolVerification))
                ExecutionPhaseResult(tool.descriptor.id, req, execution, before, after, toolVerification)
            }
        }.getOrElse { error ->
            val message = if (error is kotlinx.coroutines.TimeoutCancellationException) {
                "Tool timed out after ${tool.descriptor.timeoutMs}ms"
            } else {
                error.message ?: error::class.simpleName.orEmpty()
            }
            val execution = ToolExecutionResult(req.callId, false, error = message)
            val result = VerificationResult(VerificationStatus.FAILED, reason = message)
            eventBus.emit(AgentEvent.VerificationCompleted(result))
            ExecutionPhaseResult(tool.descriptor.id, req, execution, before, before, result)
        }
    }
}

class VerificationPhaseRunner(private val probe: CompletionCriterionProbe = CompletionCriterionProbe.Unknown) {
    suspend fun verifyStep(
        step: PlanStep,
        actionResults: List<ExecutionPhaseResult>,
        freshObservation: ToolObservation
    ): VerificationResult {
        val actionFailures = actionResults.filter { it.verification.status == VerificationStatus.FAILED }
        if (actionFailures.isNotEmpty()) {
            return VerificationResult(
                VerificationStatus.FAILED,
                evidence = actionFailures.flatMap { it.verification.evidence },
                reason = actionFailures.first().verification.reason ?: "One or more actions failed verification"
            )
        }

        if (step.completionCriteria.isEmpty()) {
            return VerificationResult(
                VerificationStatus.UNKNOWN,
                evidence = actionResults.flatMap { it.verification.evidence },
                reason = "Step has no explicit completion criteria; action success alone cannot prove the goal"
            )
        }

        val results = step.completionCriteria.map { criterion -> evaluate(criterion, freshObservation) }
        val evidence = results.mapNotNull { it.second }
        return when {
            results.all { it.first == true } -> VerificationResult(VerificationStatus.SUCCESS, evidence)
            results.any { it.first == false } -> VerificationResult(VerificationStatus.FAILED, evidence, "Completion criteria not satisfied")
            results.any { it.first == true } -> VerificationResult(VerificationStatus.PARTIAL, evidence, "Some completion criteria are satisfied; others are unknown")
            else -> VerificationResult(VerificationStatus.UNKNOWN, evidence, "Completion criteria could not be proven from current evidence")
        }
    }

    private suspend fun evaluate(c: CompletionCriterion, observation: ToolObservation): Pair<Boolean?, String?> = when (c) {
        is CompletionCriterion.TextAppears -> {
            val ok = observation.summary.contains(c.text, ignoreCase = true)
            ok to "text '${c.text}' ${if (ok) "appeared" else "not present"}"
        }
        is CompletionCriterion.TextDisappears -> {
            val ok = !observation.summary.contains(c.text, ignoreCase = true)
            ok to "text '${c.text}' ${if (ok) "is absent" else "still present"}"
        }
        is CompletionCriterion.PackageIs -> {
            val ok = observation.packageName == c.packageName
            ok to "package=${observation.packageName} expected=${c.packageName}"
        }
        is CompletionCriterion.FileExists -> probe.fileExists(c.path) to "file=${c.path}"
        is CompletionCriterion.UserConfirmed -> probe.userConfirmed(c.prompt) to "user-confirmation=${c.prompt}"
        is CompletionCriterion.Structured -> probe.structuredSatisfied(c.description, observation) to c.description
    }
}

sealed interface RecoveryDecision {
    data object Retry : RecoveryDecision
    data object Replan : RecoveryDecision
    data class Stop(val reason: StopReason) : RecoveryDecision
}

class RecoveryPhaseRunner {
    fun decide(verification: VerificationResult, retries: Int, maxRetries: Int, loop: Boolean): RecoveryDecision = when {
        loop -> RecoveryDecision.Stop(StopReason.LOOP_DETECTED)
        verification.status == VerificationStatus.PARTIAL && retries < maxRetries -> RecoveryDecision.Replan
        retries < maxRetries -> RecoveryDecision.Retry
        else -> RecoveryDecision.Stop(StopReason.MAX_RETRIES)
    }
}
