# Releasing to the JetBrains Marketplace

Marketplace is the intended primary distribution channel once a course is ready to
publish; the sideload `.zip` (see the [README](../README.md#install)) is for early
testing and self-hosted course builds in the meantime.

A production build differs from a dev build in two ways: it embeds the **real** course
public key — so the plugin trusts only manifests signed by the course's offline key —
and it ships a **signed** artifact.

The Gradle wiring is already in place (`buildProd`/`publishProd`, plus
`signPlugin`/`verifyPlugin`/`publishPlugin`); only the secrets are missing. Steps that need
a real secret, or that are a one-way decision, are marked **REQUIRES OPERATOR SECRETS**.

## `extension_hash` — the analyzer allowlist

Every submission carries an `extension_hash`. It must appear on the analyzer's allowlist
(`packages/analysis-core/src/heuristics/config/known-good-extension-hashes.json` in the
Provenance monorepo) or the submission is flagged. Compute it from the built
distribution:

```sh
./gradlew :recorder:computeExtensionHash   # → recorder/build/extension-hash.txt (64-hex)
```

It is a reproducible SHA-256 over the *extracted* plugin file tree (sorted
`<relpath>\0<bytes>` — the same algorithm the seal command uses at runtime), **not** a
hash of the `.zip` bytes.

The **dev** build (checked-in dev key, unsigned sideload) and the **prod** build (real
course key, signed) produce **different** hashes, because embedding a different course
key changes the compiled bytes. Add the value for whichever build students actually
install. The dev-build hash is already on the allowlist for local and testing installs;
a real release needs its own entry.

## One-time setup

**REQUIRES OPERATOR SECRETS.**

1. **Marketplace token.** Create or confirm a JetBrains Account and generate a Personal
   Access Token at <https://plugins.jetbrains.com/author/me/tokens>. Save it as
   `PUBLISH_TOKEN` in your CI secret store — it is shown only once.
   ([JetBrains docs](https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html))

2. **Code-signing certificate and key.** Already generated — this step is done, and is kept
   here as the recipe for renewal.
   ([JetBrains docs](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html))

   The signing identity lives in `~/cs61a-keys/` (mode `0700`), alongside the course key:

   | File | What it is |
   | --- | --- |
   | `plugin-signing-private-encrypted.pem` | RSA-4096 private key, PKCS#8 / AES-256-CBC at rest |
   | `plugin-signing-chain.crt` | Self-signed cert, `CN=Aaryan Mehta`, valid to **2036-07-12** |
   | `plugin-signing-passphrase.txt` | Passphrase for the key above |

   The cert is self-signed and JetBrains verifies nothing in it; its only job is proving
   **continuity of authorship** across versions. Losing the key means never shipping an
   update signed as the same author again — back it up off this machine.

   To regenerate (e.g. on expiry):

   ```sh
   cd ~/cs61a-keys && umask 077
   openssl rand -base64 32 | tr -d '\n' > plugin-signing-passphrase.txt
   openssl genpkey -algorithm RSA -aes-256-cbc -pass file:plugin-signing-passphrase.txt \
     -pkeyopt rsa_keygen_bits:4096 -out plugin-signing-private-encrypted.pem
   openssl req -key plugin-signing-private-encrypted.pem \
     -passin file:plugin-signing-passphrase.txt \
     -new -x509 -days 3650 -sha256 -subj "/CN=Aaryan Mehta" -out plugin-signing-chain.crt
   ```

   **`signPlugin` cannot decrypt an encrypted key — do not pass one.** The bundled
   marketplace-zip-signer does not register BouncyCastle as a JCE provider, so *every*
   encrypted format fails, with an error that names the cipher rather than the real cause:

   - PKCS#8 / PBES2 → `1.2.840.113549.1.5.13 not available: Cannot find any provider
     supporting AES/CBC/PKCS7Padding`
   - Traditional PKCS#1 (`-traditional -aes256`, `Proc-Type`/`DEK-Info`) →
     `PBKDF-OpenSSL SecretKeyFactory not available`

   This is why JetBrains' own docs decrypt to a plaintext `private.pem` first. Rather than
   leave an unencrypted key at rest, decrypt **in memory** at build time and leave
   `PRIVATE_KEY_PASSWORD` unset (the key is already decrypted; the signer must not try
   again):

   ```sh
   export CERTIFICATE_CHAIN="$(cat ~/cs61a-keys/plugin-signing-chain.crt)"
   export PRIVATE_KEY="$(openssl rsa -in ~/cs61a-keys/plugin-signing-private-encrypted.pem \
                                     -passin file:~/cs61a-keys/plugin-signing-passphrase.txt)"
   unset PRIVATE_KEY_PASSWORD
   ```

   Never commit any of these.

3. **Plugin id.** The reverse-DNS id is `com.aaryanmehta.provenance.recorder`, declared in
   two places that must stay in lockstep: `<id>` in `plugin.xml` and `RECORDER_PLUGIN_ID` in
   `RecorderSessionManager.kt`. The latter is also the producer identity written to
   `session.start.recorder.extension_id`, and `computeInstalledExtensionHash` looks the
   plugin up by it — so a mismatch between the two breaks the seal, not just the listing.
   It is a permanent identity once published: Marketplace and auto-update channels key off
   it forever, and it cannot be changed later even if plugin ownership is transferred to
   another vendor account.

4. **The first publication must be uploaded by hand** through the Marketplace web UI, per
   JetBrains' docs. `publishPlugin` automation only works for subsequent versions of an
   already-registered plugin. Build the signed zip with `./gradlew :recorder:buildProd`
   (output lands in `recorder/build/distributions/`) and upload it manually the first
   time.

## Every release

**REQUIRES OPERATOR SECRETS.**

```sh
K=~/cs61a-keys

# Course key: embedded into the build so only this course's manifests activate the plugin.
export PROVENANCE_COURSE_PUBLIC_KEY_HEX="$(python3 -c \
  "import json;print(json.load(open('$K/cs61a-fa26.json'))['public_key_hex'])")"

# Signing identity. The key is decrypted in memory only — see "Code-signing certificate
# and key" above for why an encrypted PRIVATE_KEY cannot be passed to signPlugin.
export CERTIFICATE_CHAIN="$(cat $K/plugin-signing-chain.crt)"
export PRIVATE_KEY="$(openssl rsa -in $K/plugin-signing-private-encrypted.pem \
                                  -passin file:$K/plugin-signing-passphrase.txt)"
unset PRIVATE_KEY_PASSWORD

export PUBLISH_TOKEN=<Marketplace personal access token>

# Bump `pluginVersion` in gradle.properties first — Marketplace rejects duplicates.
./gradlew :recorder:buildProd     # embed key → build → sign → hash → revert key
./gradlew :recorder:publishProd   # buildProd + verifyPlugin, then publishPlugin
```

`buildProd` always reverts `CoursePublicKey.kt` to the dev key afterward — even on
failure — so the real course key is never left in the working tree. Verify with
`git status` regardless; the guarantee is worth trusting but cheap to check.

The signed artifact is `recorder/build/distributions/recorder-signed.zip`. **Upload that
one, not `recorder.zip`** — both are produced, they sort adjacently, and only the signed
one should ever reach Marketplace. Confirm before uploading:

```sh
unzip -p recorder/build/distributions/recorder-signed.zip 'recorder/lib/recorder-*.jar' \
  > /tmp/v.jar && unzip -p /tmp/v.jar META-INF/plugin.xml | grep -m1 '<id>'
```

`extension_hash` is **signing-invariant** — signing leaves the extracted file tree
byte-identical (the signature lives in the ZIP structure, not as files), so the value
`computeExtensionHash` prints from the unsigned `buildPlugin` output is the value the
installed plugin reports. It does not need recomputing after signing.

After a release, copy `recorder/build/extension-hash.txt` into the monorepo allowlist:

```sh
cd ../provenance
node scripts/update-extension-hash-allowlist.mjs --hash <hex-from-extension-hash.txt>
```

Every release needs its own allowlist entry, or its submissions get flagged.
