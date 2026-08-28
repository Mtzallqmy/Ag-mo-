package ai.alagent.tools.api
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import ai.alagent.core.model.*
import kotlinx.serialization.json.buildJsonObject
class ToolRegistryTest{@Test fun rejectsDuplicateIds(){val t=object:Tool{override val descriptor=ToolDescriptor("x","x","x",buildJsonObject{},riskLevel=RiskLevel.LOW);override suspend fun execute(request:ToolRequest)=ToolExecutionResult(request.callId,true);override suspend fun observe(request:ToolRequest,execution:ToolExecutionResult)=null;override suspend fun verify(request:ToolRequest,context:VerificationContext)=VerificationResult(VerificationStatus.SUCCESS)};assertThrows(IllegalArgumentException::class.java){ToolRegistry(listOf(t,t))}}}
