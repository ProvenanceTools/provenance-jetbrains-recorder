package dev.provenance.recorder.failure

import com.intellij.notification.NotificationGroupManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Minimal platform test — the only BasePlatformTestCase in Plan 8. Confirms the
 * notificationGroup registered in plugin.xml resolves under a real platform and that
 * notifyDegraded() dispatches without error. Balloon *appearance* is a manual/runIde item.
 */
class DegradedModeNotifierTest : BasePlatformTestCase() {

    fun `test notification group from plugin xml is registered`() {
        val group = NotificationGroupManager.getInstance()
            .getNotificationGroup(DegradedModeNotifier.GROUP_ID)
        assertNotNull("group '${DegradedModeNotifier.GROUP_ID}' must be registered in plugin.xml", group)
    }

    fun `test notifyDegraded dispatches without throwing`() {
        DegradedModeNotifier(project).notifyDegraded()
        // notifyDegraded uses invokeLater; pump the EDT queue so the balloon construction runs.
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    }
}
