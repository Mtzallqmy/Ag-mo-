package ai.alagent.core.logging

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

data class StructuredLogEvent(
    val timestampEpochMs: Long,
    val sessionId: String? = null,
    val taskId: String? = null,
    val turnId: String? = null,
    val component: String,
    val level: LogLevel,
    val eventType: String,
    val durationMs: Long? = null,
    val metadata: Map<String, JsonElement> = emptyMap()
)
enum class LogLevel { DEBUG, INFO, WARN, ERROR }
fun interface StructuredEventLogger { fun log(event: StructuredLogEvent) }
object SecretRedactor {
    private val keyPattern = Regex("""(?i)(api[_-]?key|authorization|password|secret|token)\s*[:=]\s*[^,\s]+""")
    fun redact(value: String): String = keyPattern.replace(value) { m -> m.value.substringBefore(':').substringBefore('=') + "=[REDACTED]" }
}
