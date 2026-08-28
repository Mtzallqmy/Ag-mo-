package ai.alagent.agent.cognition

import ai.alagent.ai.provider.api.RoutedModel
import ai.alagent.core.model.ModelCapability
import ai.alagent.tools.api.ToolDescriptor

fun interface ObservationFormatter { fun format(raw: String): String }
interface HistoryManager { fun recent(maxItems:Int): List<String>; fun append(item:String) }
fun interface ContextCompactor { fun compact(items: List<String>, maxChars: Int): String }
fun interface ToolSelectionPolicy { fun select(goal:String, tools:List<ToolDescriptor>):List<ToolDescriptor> }
fun interface CompletionPolicy { fun canComplete(verificationSuccess:Boolean, unresolvedSteps:Int):Boolean }
enum class FailureKind { TRANSIENT_NETWORK, RATE_LIMIT, PERMISSION, POLICY, STALE_UI, TOOL, MODEL, TIMEOUT, UNKNOWN }
object FailureClassifier { fun classify(message:String):FailureKind = when { "429" in message || "rate" in message.lowercase() -> FailureKind.RATE_LIMIT; "permission" in message.lowercase() -> FailureKind.PERMISSION; "timeout" in message.lowercase() -> FailureKind.TIMEOUT; else -> FailureKind.UNKNOWN } }
object ModelCapabilityResolver { fun validate(model:RoutedModel, required:Set<ModelCapability>) = required.all(model.model::supports) }
