package ai.alagent.core.model

import kotlinx.serialization.Serializable

@Serializable enum class VerificationStatus { SUCCESS, PARTIAL, FAILED, UNKNOWN }
@Serializable
data class VerificationResult(
    val status: VerificationStatus,
    val evidence: List<String> = emptyList(),
    val reason: String? = null,
    val observedAtEpochMs: Long = System.currentTimeMillis()
) { val isSuccess: Boolean get() = status == VerificationStatus.SUCCESS }
