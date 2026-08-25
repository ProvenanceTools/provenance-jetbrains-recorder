package dev.provenance.recorder.identity

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A GUARD ON AN API CONTRACT THE COMPILER DOES NOT ENFORCE.
 *
 * [com.intellij.openapi.actionSystem.AnAction.actionPerformed] is annotated
 * `@ApiStatus.OverrideOnly`: a plugin may override it, never invoke it. Kotlin compiles a direct
 * call to it without complaint, so the only thing that ever caught this was the IntelliJ Plugin
 * Verifier -- and that runs at RELEASE time, in `verifyPlugin`, long after the code is written.
 * 0.3.0 shipped its enrollment nudge with exactly that violation
 * (`EnrollNudgeNotifier.showEnrollmentKey` called `action.actionPerformed(e)` to reuse the
 * palette action rather than duplicate its key-derivation logic).
 *
 * The sanctioned way to run another action's body is
 * [com.intellij.openapi.actionSystem.ex.ActionUtil.performAction], which drives the same action
 * through the action system's own update/context machinery.
 *
 * This test closes the gap between writing the call and running the verifier: it reads
 * [EnrollNudgeNotifier]'s own compiled bytecode and asserts the constant pool never names
 * `actionPerformed`. A constant-pool scan is deliberately coarser than parsing `Methodref`
 * entries -- it costs nothing, and any appearance of that name in THIS class is worth a second
 * look regardless of which owner it hangs off.
 *
 * Scans the nested/lambda classes too (`EnrollNudgeNotifier$...`), since the notification actions
 * are lambdas and a future edit could just as easily put the call in one of those.
 */
class EnrollNudgeNotifierOverrideOnlyTest {

    /**
     * [EnrollNudgeNotifier]'s own compiled bytecode, plus any sibling nested/lambda classes.
     *
     * The class may sit in a plain directory or inside the instrumented jar depending on how the
     * test task was wired, so the OWN class is always read through [Class.getResourceAsStream]
     * (works in both). Sibling enumeration is only possible in the directory case and is treated
     * as a bonus: the call this guards lives in `showEnrollmentKey`, a member of the object
     * itself, so the own-class scan alone is never vacuous.
     */
    private fun notifierBytecode(): Map<String, ByteArray> {
        val own = EnrollNudgeNotifier::class.java
        val simple = own.simpleName
        val out = LinkedHashMap<String, ByteArray>()

        own.getResourceAsStream("$simple.class").use { stream ->
            val bytes = stream?.readBytes()
                ?: throw AssertionError("could not read $simple.class from the test classpath")
            out["$simple.class"] = bytes
        }

        val url = own.getResource("$simple.class")
        if (url != null && url.protocol == "file") {
            val dir = File(url.toURI()).parentFile
            dir.listFiles { f: File -> f.name.startsWith("$simple$") && f.name.endsWith(".class") }
                .orEmpty()
                .forEach { out[it.name] = it.readBytes() }
        }
        return out
    }

    @Test
    fun `the notifier never invokes the override-only actionPerformed`() {
        val classes = notifierBytecode()
        assertTrue(
            "expected EnrollNudgeNotifier.class to be found; the guard is vacuous otherwise",
            classes.containsKey("EnrollNudgeNotifier.class"),
        )

        val offenders = classes.filterValues { bytes ->
            // The constant pool stores method names as raw modified-UTF8; a plain ISO-8859-1
            // decode of the whole file is enough to spot an ASCII name inside it.
            bytes.toString(Charsets.ISO_8859_1).contains("actionPerformed")
        }.keys

        assertTrue(
            "these compiled classes name the @ApiStatus.OverrideOnly method AnAction.actionPerformed: " +
                offenders.joinToString() +
                " -- run the action through ActionUtil.performAction instead",
            offenders.isEmpty(),
        )
    }
}
