package ai.alagent.core.model

import kotlinx.serialization.Serializable

@Serializable enum class RiskLevel { READ_ONLY, LOW, MEDIUM, HIGH, CRITICAL }
@Serializable enum class AppTier { NORMAL, CAUTIOUS, BLOCKED }
@Serializable enum class ApprovalChoice { APPROVE_ONCE, ALWAYS_ALLOW_TYPE, DENY }
@Serializable
data class ApprovalRequest(
    val requestId: String,
    val toolId: String,
    val operation: String,
    val targetApplication: String? = null,
    val potentialSensitiveData: List<String> = emptyList(),
    val reason: String,
    val riskLevel: RiskLevel
)
