package ai.alagent.agent.planning
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
class DependencyGraphTest{@Test fun rejectsCycle(){assertThrows(IllegalArgumentException::class.java){TaskPlan("x","g",listOf(PlanStep("a","a","a",setOf("b")),PlanStep("b","b","b",setOf("a"))))}}}
