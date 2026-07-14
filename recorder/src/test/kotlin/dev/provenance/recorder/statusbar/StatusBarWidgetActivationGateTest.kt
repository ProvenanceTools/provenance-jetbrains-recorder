package dev.provenance.recorder.statusbar

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.WindowManager
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import dev.provenance.recorder.HeavyTestManifests
import dev.provenance.recorder.activation.RecorderActivationActivity
import dev.provenance.recorder.activation.RecorderState
import kotlinx.coroutines.runBlocking

/**
 * HEAVY status-bar disclosure gate (PRD §4.1) — a REAL project is opened, a real
 * course-signed `.provenance-manifest` is written, and the REAL production
 * [RecorderActivationActivity] runs against it. The assertion is the shipped-to-students
 * disclosure requirement: once activation flips [RecorderState.isActive], the
 * "Provenance: recording" widget must ACTUALLY be present in the project's status bar —
 * not merely that the factory's isAvailable() returns true in isolation.
 *
 * This reproduces the user-observed bug (recording worked but the widget never appeared):
 * the widget is installed asynchronously through StatusBarWidgetsManager, and the refresh
 * has to land on the EDT after the status bar exists — otherwise the install no-ops and
 * the indicator never shows. Asserting real presence in WindowManager's status bar (via
 * getWidget) exercises that install path, unlike the isolated factory unit test.
 */
class StatusBarWidgetActivationGateTest : HeavyPlatformTestCase() {

    private fun baseDir(): VirtualFile = getOrCreateProjectBaseDir()

    private fun writeManifest(text: String) {
        WriteAction.runAndWait<RuntimeException> {
            val dir = baseDir()
            val f = dir.findChild(".provenance-manifest") ?: dir.createChildData(this, ".provenance-manifest")
            VfsUtil.saveText(f, text)
        }
    }

    private fun runActivation() {
        runBlocking { RecorderActivationActivity().execute(project) }
    }

    /**
     * Poll for the widget to appear in the project's status bar. The install runs
     * asynchronously off the activation coroutine (StatusBarWidgetsManager launches it on
     * its own scope), so dispatch pending EDT events + retry for a bounded window rather
     * than asserting once.
     */
    private fun awaitWidgetInStatusBar(): Any? {
        val statusBar = WindowManager.getInstance().getStatusBar(project)
        assertNotNull("project must have a status bar", statusBar)
        repeat(200) {
            val w = statusBar.getWidget(RecordingStatusBarWidget.WIDGET_ID)
            if (w != null) return w
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(10)
        }
        return statusBar.getWidget(RecordingStatusBarWidget.WIDGET_ID)
    }

    fun testWidgetAppearsInStatusBarOnActivation() {
        writeManifest(HeavyTestManifests.validManifestJson())

        runActivation()

        assertTrue("valid manifest must activate the privacy gate", project.service<RecorderState>().isActive)

        val widget = awaitWidgetInStatusBar()
        assertNotNull(
            "the 'Provenance: recording' widget must be present in the status bar after activation (PRD §4.1)",
            widget,
        )
        assertEquals(RecordingStatusBarWidget.WIDGET_ID, (widget as com.intellij.openapi.wm.StatusBarWidget).ID())
    }

    fun testWidgetAbsentWithoutActivation() {
        // No manifest ⇒ no activation ⇒ the indicator must never be installed.
        runActivation()

        assertFalse("no manifest ⇒ gate inactive", project.service<RecorderState>().isActive)
        val statusBar = WindowManager.getInstance().getStatusBar(project)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertNull(
            "no activation ⇒ no recording widget in the status bar",
            statusBar.getWidget(RecordingStatusBarWidget.WIDGET_ID),
        )
    }
}
