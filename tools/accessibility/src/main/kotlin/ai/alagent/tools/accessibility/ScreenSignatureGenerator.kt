package ai.alagent.tools.accessibility

import java.security.MessageDigest

object ScreenSignatureGenerator {
    fun generate(packageName: String?, elements: List<AccessibilityElement>): String {
        val canonical = buildString {
            append(packageName.orEmpty())
            elements.asSequence().filter { it.visible }.take(120).forEach { element ->
                append('|').append(element.className)
                append(':').append(element.text?.take(80))
                append(':').append(element.contentDescription?.take(80))
                append(':').append(element.bounds.flattenToString())
                append(':').append(element.selected)
                append(':').append(element.checked)
                append(':').append(element.enabled)
            }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray())
            .take(12)
            .joinToString("") { "%02x".format(it) }
    }
}
