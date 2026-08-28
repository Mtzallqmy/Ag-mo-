package ai.alagent.tools.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class AccessibilitySnapshotProvider(
    private val service: () -> AlAgentAccessibilityService?,
    private val mapper: AccessibilityNodeMapper = AccessibilityNodeMapper()
) {
    fun capture(maxDepth: Int = 18): AccessibilitySnapshot {
        val active = requireNotNull(service()) { "AL Agent AccessibilityService is not connected" }
        val root = active.rootInActiveWindow
        val elements = mapper.map(root, maxDepth)
        val packageName = root?.packageName?.toString()
        val windowTitle = active.windows.firstOrNull { it.isActive }?.title?.toString()
        return AccessibilitySnapshotPruner.prune(
            AccessibilitySnapshot(
                packageName = packageName,
                windowTitle = windowTitle,
                elements = elements,
                capturedAtEpochMs = System.currentTimeMillis(),
                screenSignature = ScreenSignatureGenerator.generate(packageName, elements)
            )
        )
    }
}

class AccessibilityActionExecutor(private val service: () -> AlAgentAccessibilityService?) {
    fun click(target: AccessibilityElement): Boolean = withMatchingNode(target) { node ->
        findActionableParent(node, AccessibilityNodeInfo.ACTION_CLICK)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    fun setText(target: AccessibilityElement, text: String): Boolean = withMatchingNode(target) { node ->
        val editable = if (node.isEditable) node else null
        editable?.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        ) == true
    }

    fun scroll(target: AccessibilityElement?, forward: Boolean): Boolean {
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        if (target != null) return withMatchingNode(target) { node -> findActionableParent(node, action)?.performAction(action) == true }
        val root = service()?.rootInActiveWindow ?: return false
        return traverse(root).firstOrNull { it.isScrollable && it.actions.and(action) != 0 }?.performAction(action) == true
    }

    fun globalAction(action: Int): Boolean = service()?.performGlobalAction(action) == true

    suspend fun tap(x: Float, y: Float, durationMs: Long = 80L): Boolean = gesture {
        val path = Path().apply { moveTo(x, y) }
        addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(1)))
    }

    suspend fun longPress(x: Float, y: Float, durationMs: Long = 650L): Boolean = tap(x, y, durationMs)

    suspend fun swipe(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long = 350L): Boolean = gesture {
        val path = Path().apply { moveTo(fromX, fromY); lineTo(toX, toY) }
        addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(1)))
    }

    private suspend fun gesture(block: GestureDescription.Builder.() -> Unit): Boolean {
        val active = service() ?: return false
        val gesture = GestureDescription.Builder().apply(block).build()
        return suspendCancellableCoroutine { continuation ->
            val accepted = active.dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(true)
                    }
                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                },
                null
            )
            if (!accepted && continuation.isActive) continuation.resume(false)
        }
    }

    private fun withMatchingNode(target: AccessibilityElement, block: (AccessibilityNodeInfo) -> Boolean): Boolean {
        val root = service()?.rootInActiveWindow ?: return false
        val match = traverse(root).firstOrNull { node -> matches(node, target) } ?: return false
        return block(match)
    }

    private fun matches(node: AccessibilityNodeInfo, target: AccessibilityElement): Boolean {
        val bounds = Rect().also(node::getBoundsInScreen)
        return bounds == target.bounds &&
            node.className?.toString() == target.className &&
            node.text?.toString() == target.text &&
            node.contentDescription?.toString() == target.contentDescription
    }

    private fun findActionableParent(start: AccessibilityNodeInfo, action: Int): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = start
        var hops = 0
        while (current != null && hops++ < 6) {
            if (current.actions.and(action) != 0) return current
            current = current.parent
        }
        return null
    }

    private fun traverse(root: AccessibilityNodeInfo): Sequence<AccessibilityNodeInfo> = sequence {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            yield(node)
            for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let(stack::add)
        }
    }
}
