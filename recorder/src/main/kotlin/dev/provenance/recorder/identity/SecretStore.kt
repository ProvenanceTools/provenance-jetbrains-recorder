package dev.provenance.recorder.identity

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe

/**
 * The persistence seam for the student's identity material (program spec §5a).
 *
 * Deliberately three methods and nothing else, so a unit test can supply a map without
 * an IDE (CLAUDE.md: "Mock at the seam"). Production uses [PasswordSafeSecretStore].
 *
 * Every method may throw: a keyring can be unavailable (a headless Linux box with no
 * libsecret, a locked keychain). Callers on the session-start path must treat that as
 * "no identity", never as a reason to stop recording.
 */
interface SecretStore {
    fun get(key: String): String?

    fun store(key: String, value: String)

    fun delete(key: String)
}

/**
 * [SecretStore] backed by JetBrains' **PasswordSafe** — the IDE's credential store, which
 * delegates to the OS vault (Keychain on macOS, DPAPI-backed Credential Manager on
 * Windows, libsecret/KWallet on Linux) or to an encrypted local file if the user
 * configured that.
 *
 * This is the JetBrains analogue of the VS Code recorder's `ExtensionContext.secrets`, and
 * it is chosen for the same reasons that one was:
 *
 *  - The master secret is the one value that lets someone sign as this student in *every*
 *    course, forever. Program spec §5a: it "never leaves the machine, is never sent to a
 *    server, and is never written into a log or a bundle."
 *  - **Never `PropertiesComponent` or any other plaintext IDE state**, which is a readable
 *    XML file under the config directory.
 *  - **Never a workspace file.** A dotfile in the assignment would be committed to a
 *    git-submission repo by accident, and in a shared CS 61B repo would be readable by a
 *    lab partner — handing them the ability to sign as their partner.
 *  - PasswordSafe is per-**machine and per-user**, not per-project. That is exactly the
 *    scope the secret needs: one student takes many courses, in many projects, and must
 *    present the same identity in all of them. An `@Service(Level.PROJECT)` store would
 *    silently give a student a different identity per project.
 *
 * The subsystem name is fixed and part of the student-facing contract: changing it strands
 * every existing install behind an identity they can no longer reach.
 */
class PasswordSafeSecretStore(
    private val passwordSafe: PasswordSafe = PasswordSafe.instance,
) : SecretStore {

    private fun attributes(key: String): CredentialAttributes =
        CredentialAttributes(generateServiceName(SUBSYSTEM, key), key)

    override fun get(key: String): String? =
        passwordSafe.get(attributes(key))?.getPasswordAsString()?.takeIf { it.isNotEmpty() }

    override fun store(key: String, value: String) {
        passwordSafe.set(attributes(key), Credentials(key, value))
    }

    override fun delete(key: String) {
        passwordSafe.set(attributes(key), null)
    }

    companion object {
        /**
         * PasswordSafe subsystem name. Fixed forever — it is half of the lookup key, so a
         * change orphans every stored secret and every enrollment token with it.
         */
        const val SUBSYSTEM: String = "Provenance Recorder"
    }
}
