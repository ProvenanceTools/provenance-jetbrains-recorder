package dev.provenance.recorder.statusbar

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.HeavyPlatformTestCase
import dev.provenance.recorder.HeavyTestManifests
import dev.provenance.recorder.activation.RecorderActivationActivity
import dev.provenance.recorder.activation.RecorderState
import kotlinx.coroutines.runBlocking

/**
 * HEAVY status-bar disclosure gate (PRD §4.1) — a REAL project is opened, a real course-signed
 * `.provenance-manifest` is written, and the REAL production [RecorderActivationActivity] runs
 * against it. What this pins is that the full activation chain (manifest → verify →
 * [RecorderState]) drives the widget's availability gate, which is what decides whether the
 * student ever sees the "Provenance: recording" indicator.
 *
 * Scope limit — read before adding assertions here. This does NOT assert the widget is actually
 * present in the status bar. Widgets are installed by `StatusBarWidgetsManager` through frame
 * init, and a [HeavyPlatformTestCase] has no real `IdeFrame`, so no widget is ever installed
 * headlessly regardless of correctness. An "is it in the status bar?" assertion here fails when
 * the code is right, and — worse — its negative twin ("absent without activation") passes
 * vacuously, reporting confidence it does not have. Actual on-screen presence is verified
 * manually; see docs/manual-verification.md.
 *
 * [RecordingStatusBarWidgetFactoryTest] covers the same gate against a stubbed [RecorderState];
 * this test's distinct value is driving it from a real signed manifest through real activation.
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

    fun testValidManifestOpensTheDisclosureGate() {
        writeManifest(HeavyTestManifests.validManifestJson())

        runActivation()

        assertTrue("valid manifest must activate the privacy gate", project.service<RecorderState>().isActive)
        assertTrue(
            "an activated workspace must make the recording indicator available (PRD §4.1)",
            RecordingStatusBarWidgetFactory().isAvailable(project),
        )
    }

    fun testNoManifestLeavesTheDisclosureGateClosed() {
        // No manifest ⇒ no activation ⇒ the indicator must never become available.
        runActivation()

        assertFalse("no manifest ⇒ gate inactive", project.service<RecorderState>().isActive)
        assertFalse(
            "an unactivated workspace must not offer the recording indicator",
            RecordingStatusBarWidgetFactory().isAvailable(project),
        )
    }

    fun testInvalidManifestSignatureLeavesTheDisclosureGateClosed() {
        writeManifest(HeavyTestManifests.invalidSignatureManifestJson())

        runActivation()

        assertFalse("a manifest signed by a foreign key must not activate", project.service<RecorderState>().isActive)
        assertFalse(
            "a failed signature check must not offer the recording indicator",
            RecordingStatusBarWidgetFactory().isAvailable(project),
        )
    }
}
