package ai.alagent.agent.runtime

import ai.alagent.ai.provider.api.AiMessage
import ai.alagent.ai.provider.api.AiRequest
import ai.alagent.ai.provider.api.AiStreamEvent
import ai.alagent.ai.provider.api.ConnectivityProvider
import ai.alagent.ai.provider.api.ModelRouter
import ai.alagent.ai.provider.api.RoutingContext
import ai.alagent.core.model.ModelCapability
import ai.alagent.tools.api.ToolObservation
import kotlinx.coroutines.flow.toList

/** Supplies model-routing policy for verification without coupling the runtime to Android settings. */
fun interface VerificationRoutingContextProvider {
    suspend fun context(required: Set<ModelCapability>): RoutingContext
}

/**
 * Uses a short, isolated verifier request for criteria that cannot be proven by deterministic probes.
 * The verifier is asked only for a three-state verdict and evidence summary; hidden reasoning is neither
 * requested nor persisted.
 */
class ModelCompletionCriterionProbe(
    private val router: ModelRouter,
    private val routing: VerificationRoutingContextProvider,
    private val delegate: CompletionCriterionProbe = CompletionCriterionProbe.Unknown
) : CompletionCriterionProbe {
    override suspend fun fileExists(path: String): Boolean? = delegate.fileExists(path)
    override suspend fun userConfirmed(prompt: String): Boolean? = delegate.userConfirmed(prompt)

    override suspend fun structuredSatisfied(description: String, observation: ToolObservation): Boolean? {
        return runCatching {
            val route = router.route(routing.context(setOf(ModelCapability.TEXT)))
            val response = route.provider.stream(
                AiRequest(
                    model = route.model,
                    messages = listOf(
                        AiMessage(
                            AiMessage.Role.SYSTEM,
                            "You are AL Agent's verification classifier. Evaluate only the supplied completion criterion against the fresh observation. Return exactly VERIFIED, NOT_VERIFIED, or UNKNOWN. Do not provide chain-of-thought."
                        ),
                        AiMessage(
                            AiMessage.Role.USER,
                            "Criterion: ${description.take(2_000)}\nFresh observation:\n${observation.summary.take(12_000)}"
                        )
                    ),
                    maxOutputTokens = 16,
                    temperature = 0.0
                )
            ).toList().filterIsInstance<AiStreamEvent.TextDelta>().joinToString("") { it.text }.trim().uppercase()
            when {
                response.startsWith("VERIFIED") -> true
                response.startsWith("NOT_VERIFIED") -> false
                else -> null
            }
        }.getOrNull()
    }
}
