package ai.alagent.agent.runtime

import ai.alagent.agent.planning.CompletionCriterion
import ai.alagent.agent.planning.PlanStep
import ai.alagent.core.model.ToolCallId
import ai.alagent.core.model.VerificationResult
import ai.alagent.core.model.VerificationStatus
import ai.alagent.tools.api.ToolExecutionResult
import ai.alagent.tools.api.ToolObservation
import ai.alagent.tools.api.ToolRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class VerificationPhaseRunnerTest {
    @Test
    fun `failed action verification cannot be rescued by matching screen evidence`() = runBlocking {
        val runner = VerificationPhaseRunner()
        val step = step(CompletionCriterion.TextAppears("Saved"))
        val action = actionResult(VerificationStatus.FAILED, executionSucceeded = true)

        val result = runner.verifyStep(step, listOf(action), ToolObservation("Saved successfully"))

        assertEquals(VerificationStatus.FAILED, result.status)
    }

    @Test
    fun `successful execute without completion criteria remains unknown`() = runBlocking {
        val runner = VerificationPhaseRunner()
        val action = actionResult(VerificationStatus.SUCCESS, executionSucceeded = true)

        val result = runner.verifyStep(step(), listOf(action), ToolObservation("Action finished"))

        assertEquals(VerificationStatus.UNKNOWN, result.status)
    }

    @Test
    fun `fresh observation must satisfy every explicit criterion`() = runBlocking {
        val runner = VerificationPhaseRunner()
        val step = step(
            CompletionCriterion.TextAppears("Connected"),
            CompletionCriterion.PackageIs("com.example.target")
        )

        val success = runner.verifyStep(
            step,
            listOf(actionResult(VerificationStatus.SUCCESS)),
            ToolObservation("Connected", packageName = "com.example.target")
        )
        val failure = runner.verifyStep(
            step,
            listOf(actionResult(VerificationStatus.SUCCESS)),
            ToolObservation("Connected", packageName = "com.example.other")
        )

        assertEquals(VerificationStatus.SUCCESS, success.status)
        assertEquals(VerificationStatus.FAILED, failure.status)
    }

    @Test
    fun `known satisfied evidence plus unknown probe is partial`() = runBlocking {
        val probe = object : CompletionCriterionProbe {
            override suspend fun fileExists(path: String): Boolean? = null
            override suspend fun userConfirmed(prompt: String): Boolean? = null
            override suspend fun structuredSatisfied(description: String, observation: ToolObservation): Boolean? = null
        }
        val runner = VerificationPhaseRunner(probe)
        val step = step(
            CompletionCriterion.TextAppears("Ready"),
            CompletionCriterion.FileExists("result.txt")
        )

        val result = runner.verifyStep(
            step,
            listOf(actionResult(VerificationStatus.SUCCESS)),
            ToolObservation("Ready")
        )

        assertEquals(VerificationStatus.PARTIAL, result.status)
    }

    @Test
    fun `all unknown completion evidence remains unknown`() = runBlocking {
        val runner = VerificationPhaseRunner(CompletionCriterionProbe.Unknown)
        val step = step(CompletionCriterion.Structured("Goal is verifiably satisfied"))

        val result = runner.verifyStep(
            step,
            listOf(actionResult(VerificationStatus.SUCCESS)),
            ToolObservation("No decisive evidence")
        )

        assertEquals(VerificationStatus.UNKNOWN, result.status)
    }

    private fun step(vararg criteria: CompletionCriterion) = PlanStep(
        id = "step-1",
        title = "Test",
        objective = "Verify safely",
        completionCriteria = criteria.toList()
    )

    private fun actionResult(
        verificationStatus: VerificationStatus,
        executionSucceeded: Boolean = true
    ): ExecutionPhaseResult {
        val callId = ToolCallId("call-1")
        val request = ToolRequest(callId, buildJsonObject { })
        return ExecutionPhaseResult(
            toolId = "test.tool",
            request = request,
            execution = ToolExecutionResult(callId, executionSucceeded),
            before = ToolObservation("before"),
            after = ToolObservation("after"),
            verification = VerificationResult(verificationStatus, reason = verificationStatus.name)
        )
    }
}
