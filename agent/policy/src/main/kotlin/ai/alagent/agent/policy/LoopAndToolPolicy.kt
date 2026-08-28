package ai.alagent.agent.policy

import ai.alagent.tools.api.*

data class NavigationFingerprint(val screenSignature:String?, val toolId:String?, val argumentHash:Int?)
data class LoopDetectionResult(val loop:Boolean, val reason:String?=null)
class LoopDetectionPolicy(private val repeatThreshold:Int=3, private val window:Int=8) {
    fun detect(history:List<NavigationFingerprint>):LoopDetectionResult {
        val recent=history.takeLast(window); if(recent.size<repeatThreshold) return LoopDetectionResult(false)
        val last=recent.last(); val repeats=recent.count{it==last}
        return if(repeats>=repeatThreshold) LoopDetectionResult(true,"Repeated identical screen/tool pattern $repeats times") else LoopDetectionResult(false)
    }
}
class ToolEligibilityFilter {
    fun eligible(goal:String, all:List<ToolDescriptor>):List<ToolDescriptor> {
        val g=goal.lowercase(); val cats=buildSet { add(ToolCategory.CORE); if(listOf("screen","tap","click","app","scroll","swipe","type").any(g::contains)) add(ToolCategory.UI); if(listOf("file","folder","download").any(g::contains)) add(ToolCategory.FILES); if(listOf("http","web","url","download").any(g::contains)) add(ToolCategory.NETWORK); if("mcp" in g) add(ToolCategory.MCP) }
        return all.filter { it.category in cats }
    }
}
