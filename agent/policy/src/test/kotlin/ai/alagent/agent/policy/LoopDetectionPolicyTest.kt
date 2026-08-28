package ai.alagent.agent.policy
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
class LoopDetectionPolicyTest { @Test fun detectsRepeatedFingerprint(){val f=NavigationFingerprint("same","tap",1);assertTrue(LoopDetectionPolicy(3).detect(listOf(f,f,f)).loop)} @Test fun ignoresNormalProgress(){assertFalse(LoopDetectionPolicy(3).detect(listOf(NavigationFingerprint("a","tap",1),NavigationFingerprint("b","tap",2),NavigationFingerprint("c","tap",3))).loop)} }
