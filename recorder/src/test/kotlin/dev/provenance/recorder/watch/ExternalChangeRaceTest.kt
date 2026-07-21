package dev.provenance.recorder.watch

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.provenance.core.FsExternalChangePayload
import dev.provenance.core.Sha256
import dev.provenance.recorder.state.Delta
import dev.provenance.recorder.state.ExpectedContentRegistry
import java.nio.file.Files
import java.nio.file.Path

/**
 * The save-time race (recorder PRD §4.5).
 *
 * [VfsExternalChangeListener.after] runs on the EDT inside a write action and hands the
 * actual content read + compare to [VfsExternalChangeListener.DEFAULT_DISPATCH], which
 * runs it on a pooled background thread. The expected-content model, meanwhile, is fed
 * from the EDT by ExternalChangeCoordinator's DocumentListener. A keystroke processed on
 * the EDT between the VFS save event and the pooled-thread comparison therefore advances
 * the model past the bytes that were actually written — and a naive hash compare reports
 * the student's own save as an external write.
 *
 * Worse, the divergence branch used to call `expected.reset(onDiskContent)`, rolling the
 * model BACKWARDS onto the stale snapshot, which guaranteed the next save mismatched too.
 * In the VS Code recorder (same mechanism, `readFile().then(...)` instead of a thread hop)
 * this produced 3316 false fs.external_change events across a 156-submission corpus.
 *
 * Seams used here, both already injectable and both used by the existing suite:
 *  - `dispatch` is deferred instead of synchronous, so the test controls exactly when the
 *    background work runs and can advance the model inside that window.
 *  - `readDisk` is stubbed, so "what the editor wrote" is stated explicitly rather than
 *    depending on filesystem timing.
 *
 * The model is advanced by calling `applyDelta` directly — the same call
 * ExternalChangeCoordinator's DocumentListener makes — rather than by driving a real
 * typing session, per CLAUDE.md ("test the direction/dedup/payload logic as pure
 * functions separate from VFS wiring").
 */
class ExternalChangeRaceTest : BasePlatformTestCase() {
    private lateinit var wsRoot: Path
    private val emitted = mutableListOf<FsExternalChangePayload>()

    /** Work handed to `dispatch`, held until [drain] — stands in for the pooled thread. */
    private val pending = mutableListOf<() -> Unit>()

    /** What a disk read returns. Set by each test to whatever the editor actually wrote. */
    private var diskContent: String = ""

    private companion object {
        const val BASE = "def foo():\n    pass\n"
        const val TYPED = "print(1)\n"
        /** The keystroke that lands inside the race window. */
        const val RACED = "x"
    }

    override fun setUp() {
        super.setUp()
        wsRoot = Files.createTempDirectory("race-ws")
        emitted.clear()
        pending.clear()
        diskContent = BASE
    }

    private fun vfFor(name: String, content: String): VirtualFile {
        val p = wsRoot.resolve(name)
        Files.writeString(p, content)
        return LocalFileSystem.getInstance().refreshAndFindFileByNioFile(p)!!
    }

    private fun install(reg: ExpectedContentRegistry) {
        val engine = ExternalChangeEngine(reg)
        val readDisk: (VirtualFile) -> String = { diskContent }
        val saveChecker = SaveTimeExternalChangeChecker(
            engine,
            emit = { emitted.add(it) },
            readDisk = readDisk,
        )
        val listener = VfsExternalChangeListener(
            workspaceRoot = wsRoot,
            engine = engine,
            saveChecker = saveChecker,
            emit = { emitted.add(it) },
            readDisk = readDisk,
            // Deferred, NOT synchronous: this is the pooled-thread hop the bug lives in.
            dispatch = { pending.add(it) },
        )
        ApplicationManager.getApplication().messageBus.connect(testRootDisposable)
            .subscribe(VirtualFileManager.VFS_CHANGES, listener)
    }

    /** Run the work the listener handed to the background thread. */
    private fun drain() {
        val work = pending.toList()
        pending.clear()
        work.forEach { it() }
    }

    private fun saveEditor(vf: VirtualFile, insert: String) {
        val doc = FileDocumentManager.getInstance().getDocument(vf)!!
        WriteCommandAction.runWriteCommandAction(project) { doc.insertString(doc.textLength, insert) }
        ApplicationManager.getApplication().invokeAndWait {
            WriteAction.run<RuntimeException> { FileDocumentManager.getInstance().saveDocument(doc) }
        }
    }

    private fun append(reg: ExpectedContentRegistry, rel: String, text: String) {
        val ec = reg.get(rel)!!
        ec.applyDelta(Delta(ec.content.length, 0, text))
    }

    /**
     * T1 — the regression test. A keystroke lands between the VFS save event and the
     * pooled-thread comparison. Nothing external happened, so nothing may be emitted, and
     * the model must NOT be rolled backwards onto the stale on-disk snapshot.
     */
    fun testKeystrokeDuringDispatchGapEmitsNothingAndDoesNotRollTheModelBack() {
        val vf = vfFor("hw.py", BASE)
        val rel = relativePathOf(vf, wsRoot)!!
        val reg = ExpectedContentRegistry(listOf(rel))
        reg.getOrCreate(rel, BASE)
        install(reg)

        // 1. Student types. Buffer and model advance together.
        append(reg, rel, TYPED)

        // 2. Autosave writes those bytes; the VFS save event queues the check.
        diskContent = BASE + TYPED
        saveEditor(vf, TYPED)
        assertTrue("the save must have queued background work", pending.isNotEmpty())

        // 3. The race: one more keystroke reaches the EDT before that work runs.
        append(reg, rel, RACED)

        // 4. The background comparison finally runs, against the older on-disk bytes.
        drain()

        assertEquals("the editor's own save is not an external change", 0, emitted.size)
        assertEquals(
            "the live buffer is authoritative and must not be rolled back",
            BASE + TYPED + RACED,
            reg.get(rel)!!.content,
        )
    }

    /**
     * T2 — the anti-regression. Identical harness, but the disk holds content the buffer
     * never held. This must still be reported, with the correct direction, and the model
     * must be reseeded to disk reality. A fix that bought silence by going blind here
     * would be worse than the bug.
     */
    fun testGenuineExternalWriteDuringDispatchGapIsStillReportedExactlyOnce() {
        val external = "import os\nos.system(\"rm -rf /\")\n"
        val vf = vfFor("hw.py", BASE)
        val rel = relativePathOf(vf, wsRoot)!!
        val reg = ExpectedContentRegistry(listOf(rel))
        reg.getOrCreate(rel, BASE)
        install(reg)

        append(reg, rel, TYPED)
        diskContent = BASE + TYPED
        saveEditor(vf, TYPED)
        append(reg, rel, RACED)

        // Something else wrote the file before our read landed.
        diskContent = external
        drain()

        assertEquals("a real external write must still be reported", 1, emitted.size)
        val p = emitted[0]
        assertEquals(rel, p.path)
        assertEquals("modify", p.operation)
        // Direction: old = the expected model, new = on-disk reality.
        assertEquals(Sha256.hex(BASE + TYPED + RACED), p.oldHash)
        assertEquals(Sha256.hex(external), p.newHash)
        assertEquals("the model is reseeded to disk reality", external, reg.get(rel)!!.content)
    }

    /**
     * T3 — no self-perpetuation. The backwards reset is what turned one mistimed keystroke
     * into a mirrored PAIR of events: the model was left behind the buffer, so the next
     * save mismatched too. Run T1, then save again with no interleaved edit.
     */
    fun testToleratedRaceDoesNotPerpetuateIntoTheNextSave() {
        val vf = vfFor("hw.py", BASE)
        val rel = relativePathOf(vf, wsRoot)!!
        val reg = ExpectedContentRegistry(listOf(rel))
        reg.getOrCreate(rel, BASE)
        install(reg)

        // --- T1 again.
        append(reg, rel, TYPED)
        diskContent = BASE + TYPED
        saveEditor(vf, TYPED)
        append(reg, rel, RACED)
        drain()
        assertEquals(0, emitted.size)

        // --- A second, entirely clean save: the raced keystroke reaches disk, no new edit.
        diskContent = BASE + TYPED + RACED
        saveEditor(vf, RACED)
        drain()

        assertEquals("the second save of the mirrored pair must also be silent", 0, emitted.size)
        assertEquals(BASE + TYPED + RACED, reg.get(rel)!!.content)
    }
}
