package ai.alagent.ai.provider.api

import ai.alagent.core.model.ModelCapability

class CapabilityModelRouter(private val providers: List<AiProvider>) : ModelRouter {
    override suspend fun route(context: RoutingContext): RoutedModel {
        val candidates = providers.flatMap { p -> p.listModels().map { p to it } }
            .filter { (_, m) -> context.requiredCapabilities.all(m::supports) }
            .filter { (_, m) -> !context.privacyMode || m.supports(ModelCapability.LOCAL) }
            .filter { (_, m) -> context.internetAvailable || m.supports(ModelCapability.LOCAL) }
        val selected = context.selectedModelId?.let { id -> candidates.firstOrNull { it.second.id == id } }
        val chosen = selected ?: candidates.sortedByDescending { (_,m) -> (if (context.preferLocal && m.supports(ModelCapability.LOCAL)) 10 else 0) + m.contextWindow / 100_000 }.firstOrNull()
        requireNotNull(chosen) { "No model satisfies required capabilities and privacy/connectivity policy" }
        return RoutedModel(chosen.first, chosen.second, if (selected != null) "user-selected" else "capability-policy")
    }
}
