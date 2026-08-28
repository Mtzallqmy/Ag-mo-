package ai.alagent.agent.policy

import ai.alagent.core.model.*
import ai.alagent.tools.api.*

sealed interface PolicyDecision { data object Allow:PolicyDecision; data class RequireApproval(val request:ApprovalRequest):PolicyDecision; data class Deny(val reason:String):PolicyDecision }
interface PermissionManager { fun has(permission:String):Boolean }
interface ApprovalManager { suspend fun request(approval:ApprovalRequest):ApprovalChoice }
fun interface SensitiveDataDetector { fun detect(text:String):List<String> }
fun interface AppTierClassifier { fun classify(packageName:String?):AppTier }
fun interface ToolAuditLogger { fun record(toolId:String, decision:String, detail:String?) }
interface PolicyEngine { suspend fun evaluate(tool:ToolDescriptor, request:ToolRequest):PolicyDecision }
class DefaultPolicyEngine(
    private val permissions:PermissionManager,
    private val classifier:AppTierClassifier,
    private val detector:SensitiveDataDetector,
    private val audit:ToolAuditLogger
):PolicyEngine {
    override suspend fun evaluate(tool:ToolDescriptor, request:ToolRequest):PolicyDecision {
        val missing=tool.requiredPermissions.filterNot(permissions::has)
        if(missing.isNotEmpty()) return PolicyDecision.Deny("Missing permissions: ${missing.joinToString()}").also{audit.record(tool.id,"deny","permission")}
        val tier=classifier.classify(request.targetPackage)
        if(tier==AppTier.BLOCKED) return PolicyDecision.Deny("Target application is blocked by policy").also{audit.record(tool.id,"deny","blocked-app")}
        val sensitive=detector.detect(request.arguments.toString())
        val needsApproval=tool.requiresConfirmation || tool.riskLevel>=RiskLevel.HIGH || tier==AppTier.CAUTIOUS || sensitive.isNotEmpty()
        if(needsApproval) {
            val approval=ApprovalRequest(request.callId.value,tool.id,tool.name,request.targetPackage,sensitive,"Sensitive or high-impact action requires explicit approval",tool.riskLevel)
            return PolicyDecision.RequireApproval(approval).also{audit.record(tool.id,"approval",tier.name)}
        }
        return PolicyDecision.Allow.also{audit.record(tool.id,"allow",null)}
    }
}
