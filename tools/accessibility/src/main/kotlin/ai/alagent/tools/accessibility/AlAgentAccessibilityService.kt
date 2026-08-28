package ai.alagent.tools.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class AlAgentAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile internal var instance: AlAgentAccessibilityService? = null
        val isConnected: Boolean get() = instance != null
        val snapshotProvider: AccessibilitySnapshotProvider = AccessibilitySnapshotProvider(service = { instance })
        val actionExecutor: AccessibilityActionExecutor = AccessibilityActionExecutor { instance }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    fun capture(maxDepth: Int = 18): AccessibilitySnapshot = snapshotProvider.capture(maxDepth)
}