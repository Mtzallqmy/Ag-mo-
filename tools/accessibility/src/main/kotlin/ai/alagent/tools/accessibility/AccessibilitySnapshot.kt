package ai.alagent.tools.accessibility

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo

data class AccessibilityElement(
    val nodeId: String,
    val text: String?,
    val contentDescription: String?,
    val className: String?,
    val bounds: Rect,
    val clickable: Boolean,
    val editable: Boolean,
    val enabled: Boolean,
    val selected: Boolean,
    val checked: Boolean?,
    val scrollable: Boolean,
    val visible: Boolean,
    val depth: Int
)

data class AccessibilitySnapshot(
    val packageName: String?,
    val windowTitle: String?,
    val elements: List<AccessibilityElement>,
    val capturedAtEpochMs: Long,
    val screenSignature: String
)

class AccessibilityNodeMapper {
    fun map(root: AccessibilityNodeInfo?, maxDepth: Int = 18): List<AccessibilityElement> {
        val out = mutableListOf<AccessibilityElement>()
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > maxDepth) return
            val bounds = Rect().also(node::getBoundsInScreen)
            val platformId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                runCatching { node.uniqueId }.getOrNull()
            } else null
            val fallbackId = buildString {
                append(depth).append(':')
                append(node.className ?: "?").append(':')
                append(bounds.flattenToString()).append(':')
                append(node.text?.toString()?.take(64) ?: node.contentDescription?.toString()?.take(64).orEmpty())
            }
            out += AccessibilityElement(
                nodeId = platformId ?: fallbackId,
                text = node.text?.toString(),
                contentDescription = node.contentDescription?.toString(),
                className = node.className?.toString(),
                bounds = bounds,
                clickable = node.isClickable,
                editable = node.isEditable,
                enabled = node.isEnabled,
                selected = node.isSelected,
                checked = if (node.isCheckable) node.isChecked else null,
                scrollable = node.isScrollable,
                visible = node.isVisibleToUser,
                depth = depth
            )
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        walk(root, 0)
        return out
    }
}

object AccessibilitySnapshotPruner {
    fun prune(snapshot: AccessibilitySnapshot, maxNodes: Int = 80, maxTextChars: Int = 8_000): AccessibilitySnapshot {
        var chars = 0
        val ranked = snapshot.elements.asSequence()
            .filter { it.visible && it.enabled }
            .map { it to relevanceScore(it) }
            .sortedByDescending { it.second }
            .map { it.first }
            .filter { element ->
                val addedChars = (element.text?.length ?: 0) + (element.contentDescription?.length ?: 0)
                if (chars + addedChars > maxTextChars) false else { chars += addedChars; true }
            }
            .take(maxNodes)
            .toList()
        return snapshot.copy(elements = ranked)
    }

    private fun relevanceScore(element: AccessibilityElement): Int =
        (if (element.clickable) 30 else 0) +
            (if (element.editable) 35 else 0) +
            (if (element.scrollable) 15 else 0) +
            (if (!element.text.isNullOrBlank()) 20 else 0) +
            (if (!element.contentDescription.isNullOrBlank()) 15 else 0) - element.depth
}

class ElementSelector {
    fun find(snapshot: AccessibilitySnapshot, query: String, limit: Int = 10): List<AccessibilityElement> {
        val normalized = query.trim()
        if (normalized.isEmpty()) return emptyList()
        return snapshot.elements
            .map { element ->
                val haystack = listOfNotNull(element.text, element.contentDescription, element.className).joinToString(" ")
                val score = when {
                    element.nodeId == normalized -> 100
                    element.text.equals(normalized, ignoreCase = true) -> 90
                    element.contentDescription.equals(normalized, ignoreCase = true) -> 85
                    haystack.contains(normalized, ignoreCase = true) -> 60
                    else -> 0
                } + (if (element.clickable) 10 else 0) + (if (element.editable) 10 else 0)
                element to score
            }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(limit.coerceIn(1, 50))
            .map { it.first }
    }
}
