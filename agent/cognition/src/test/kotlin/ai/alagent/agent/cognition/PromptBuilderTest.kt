package ai.alagent.agent.cognition
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
class PromptBuilderTest{@Test fun preservesTypedSectionOrder(){val s=PromptBuilder().build(PromptDocument("agent_main_v1",listOf(Identity("i"),Goal("g"),CurrentObservation("o"))));assertTrue(s.indexOf("## Identity")<s.indexOf("## Goal"));assertTrue(s.contains("PROMPT_VERSION: agent_main_v1"))}}
