package ai.alagent.app.runtime

import ai.alagent.agent.policy.ApprovalManager
import ai.alagent.core.model.ApprovalChoice
import ai.alagent.core.model.ApprovalRequest
import ai.alagent.core.model.RiskLevel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes approval prompts so concurrent tools cannot race the user. */
class ApprovalCoordinator : ApprovalManager {
    private val requestMutex = Mutex()
    private val mutablePending = MutableStateFlow<ApprovalRequest?>(null)
    private val rememberedLowRiskTools = mutableSetOf<String>()
    private var response: CompletableDeferred<ApprovalChoice>? = null

    val pending: StateFlow<ApprovalRequest?> = mutablePending.asStateFlow()

    override suspend fun request(approval: ApprovalRequest): ApprovalChoice {
        if (approval.toolId in rememberedLowRiskTools && approval.riskLevel <= RiskLevel.MEDIUM) {
            return ApprovalChoice.APPROVE_ONCE
        }
        return requestMutex.withLock {
            val deferred = CompletableDeferred<ApprovalChoice>()
            response = deferred
            mutablePending.value = approval
            try {
                val choice = deferred.await()
                if (choice == ApprovalChoice.ALWAYS_ALLOW_TYPE && approval.riskLevel <= RiskLevel.MEDIUM) {
                    rememberedLowRiskTools += approval.toolId
                    ApprovalChoice.APPROVE_ONCE
                } else {
                    choice
                }
            } finally {
                response = null
                mutablePending.value = null
            }
        }
    }

    fun resolve(choice: ApprovalChoice) {
        response?.complete(choice)
    }

    fun denyPending() {
        response?.complete(ApprovalChoice.DENY)
    }
}
