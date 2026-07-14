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

2. **Code-signing certificate and key.**
   ([JetBrains docs](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html))

   ```sh
   openssl genpkey -aes-256-cbc -algorithm RSA -out private_encrypted.pem -pkeyopt rsa_keygen_bits:4096
   openssl rsa -in private_encrypted.pem -out private.pem
   openssl req -key private.pem -new -x509 -days 365 -out chain.crt
   ```

   Store the contents of `chain.crt` as `CERTIFICATE_CHAIN`, the contents of
   `private.pem` as `PRIVATE_KEY`, and the passphrase as `PRIVATE_KEY_PASSWORD`. Never
   commit these.

3. **Plugin id.** The reverse-DNS id declared in `plugin.xml` (currently
   `com.provenance.recorder`) is a permanent identity once published — Marketplace and
   auto-update channels key off it forever. Confirm it with the course before the first
   publish.

4. **The first publication must be uploaded by hand** through the Marketplace web UI, per
   JetBrains' docs. `publishPlugin` automation only works for subsequent versions of an
   already-registered plugin. Build the signed zip with `./gradlew :recorder:buildProd`
   (output lands in `recorder/build/distributions/`) and upload it manually the first
   time.

## Every release

**REQUIRES OPERATOR SECRETS.**

```sh
export PROVENANCE_COURSE_PUBLIC_KEY_HEX=<64-hex production course public key>
export CERTIFICATE_CHAIN="$(cat chain.crt)"
export PRIVATE_KEY="$(cat private.pem)"
export PRIVATE_KEY_PASSWORD=<passphrase>
export PUBLISH_TOKEN=<Marketplace personal access token>

# Bump `pluginVersion` in gradle.properties first — Marketplace rejects duplicates.
./gradlew :recorder:buildProd     # embed key → build → sign → hash → revert key
./gradlew :recorder:publishProd   # buildProd + verifyPlugin, then publishPlugin
```

`buildProd` always reverts `CoursePublicKey.kt` to the dev key afterward — even on
failure — so the real course key is never left in the working tree.

After a release, copy `recorder/build/extension-hash.txt` into the monorepo allowlist:

```sh
cd ../provenance
node scripts/update-extension-hash-allowlist.mjs --hash <hex-from-extension-hash.txt>
```

Every release needs its own allowlist entry, or its submissions get flagged.
