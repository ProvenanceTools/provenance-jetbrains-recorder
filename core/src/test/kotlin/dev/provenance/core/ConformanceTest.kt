package dev.provenance.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * The consolidated cross-language conformance gate. Every crypto/format primitive
 * in `core/` is checked here against golden vectors generated from the monorepo's
 * `log-core` (@noble libs, canonicalize). Because ed25519 (RFC 8032) and JCS are
 * deterministic, "matches log-core" means byte-identical — a red test here means
 * the format is wrong, never that a vector should change.
 */
class ConformanceTest {
    private fun vector(name: String): JsonObject =
        Json.parseToJsonElement(
            this::class.java.getResource("/conformance/$name")!!.readText(),
        ).jsonObject

    private val vectors: JsonObject by lazy { vector("vectors.json") }

    @Test
    fun `sha256 vectors match`() {
        for (v in vectors["sha256"]!!.jsonArray) {
            val o = v.jsonObject
            assertEquals(o["hex"]!!.jsonPrimitive.content, Sha256.hex(o["input"]!!.jsonPrimitive.content))
        }
    }

    @Test
    fun `chain vectors match`() {
        for (v in vectors["chain"]!!.jsonArray) {
            val o = v.jsonObject
            val e = o["envelope"]!!.jsonObject
            val env = Envelope(
                seq = e["seq"]!!.jsonPrimitive.long,
                t = e["t"]!!.jsonPrimitive.long,
                wall = e["wall"]!!.jsonPrimitive.content,
                kind = e["kind"]!!.jsonPrimitive.content,
                data = e["data"]!!.jsonObject,
            )
            val result = chainEntry(o["prev_hash"]!!.jsonPrimitive.content, env)
            assertEquals(o["hash"]!!.jsonPrimitive.content, result.hash)
        }
    }

    @Test
    fun `ed25519 vector matches noble`() {
        val v = vector("ed25519.json")
        val priv = Ed25519.hexToBytes(v["priv_hex"]!!.jsonPrimitive.content)
        val msg = v["msg_utf8"]!!.jsonPrimitive.content.toByteArray(Charsets.UTF_8)
        assertEquals(v["sig_hex"]!!.jsonPrimitive.content, Ed25519.bytesToHex(Ed25519.sign(msg, priv)))
        assertEquals(v["pub_hex"]!!.jsonPrimitive.content, Ed25519.bytesToHex(Ed25519.publicKeyOf(priv)))
    }

    @Test
    fun `manifest vector verifies against course pubkey`() {
        val v = vector("manifest.json")
        val m = (parseManifest(v["manifest"]!!.jsonObject.toString()) as ManifestParse.Ok).manifest
        assertTrue(verifyManifest(m, v["course_pubkey_hex"]!!.jsonPrimitive.content))
    }

    @Test
    fun `bundle manifest signing reproduces log-core canonical json and signature`() {
        val v = vector("bundle-manifest.json")
        val m = validateBundleManifestShape(v["manifest"]!!.jsonObject.toString()).getOrThrow()
        val priv = ByteArray(32) { 3 }
        val signed = signBundleManifest(m, priv)
        assertEquals(v["canonical_json"]!!.jsonPrimitive.content, signed.canonicalJson)
        assertEquals(v["signature_hex"]!!.jsonPrimitive.content, signed.signatureHex)
    }

    @Test
    fun `session privkey ciphertext matches noble and decrypts`() {
        val v = vector("session-key.json")
        fun h(k: String) = v[k]!!.jsonPrimitive.content
        val enc = encryptSessionPrivkey(
            Ed25519.hexToBytes(h("privkey_hex")),
            h("manifest_sig"),
            saltBytes = Ed25519.hexToBytes(h("salt_hex")),
            nonceBytes = Ed25519.hexToBytes(h("nonce_hex")),
        )
        assertEquals(h("ciphertext_hex"), enc.ciphertext)
        assertEquals(h("privkey_hex"), Ed25519.bytesToHex(decryptSessionPrivkey(enc, h("manifest_sig"))))
    }

    @Test
    fun `checkpoint signature matches log-core`() {
        val v = vector("checkpoint.json")
        val priv = ByteArray(32) { 4 }
        val cp = signCheckpoint(
            v["seq"]!!.jsonPrimitive.long,
            v["hash"]!!.jsonPrimitive.content,
            priv,
        )
        assertEquals(v["sig"]!!.jsonPrimitive.content, cp.sig)
        assertTrue(verifyCheckpoint(cp, v["session_pubkey_hex"]!!.jsonPrimitive.content))
    }

    @Test
    fun `golden bundle manifest conforms to the shared bundle-manifest shape`() {
        // A complete sealed bundle built by analysis-core's test-support builder and exported
        // as a sidecar. core/ has no zip loader yet, so the round-trip here validates the
        // sealed BundleManifest against the shared shape rules; full zip parsing is future work.
        val sidecar = vector("golden-bundle.json")
        val manifest = sidecar["manifest"]!!.jsonObject
        val result = validateBundleManifestShape(manifest.toString())
        assertTrue(result.isSuccess, "golden bundle manifest should validate: ${result.exceptionOrNull()?.message}")

        // The golden zip ships alongside for that future round-trip; assert it is present and
        // non-empty so it can't silently vanish from the resources.
        val zip = this::class.java.getResourceAsStream("/conformance/golden-bundle.zip")!!.readBytes()
        assertTrue(zip.isNotEmpty())
    }

    // -----------------------------------------------------------------------
    // Manifest 2.0 trust chain (program spec §2, §3, §4)
    // -----------------------------------------------------------------------

    /**
     * Rebuild a [Manifest] from raw JSON **without** validating it — the Kotlin
     * stand-in for TypeScript's `manifest as Manifest` cast.
     *
     * The chain vectors deliberately include manifests that [parseManifest] must
     * reject (`missing_course_cert`), because the property under test is that
     * `verifyManifestChain` re-validates whatever it is handed rather than
     * trusting its caller to have parsed it. Going through the parser here would
     * test the parser instead and never reach step 0b.
     */
    private fun lenientManifest(o: JsonObject): Manifest {
        fun str(key: String): String? = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return Manifest(
            assignmentId = str("assignment_id") ?: "",
            semester = str("semester") ?: "",
            issuedAt = str("issued_at") ?: "",
            filesUnderReview = (o["files_under_review"] as? JsonArray)
                ?.map { it.jsonPrimitive.content }
                ?: emptyList(),
            sig = str("sig") ?: "",
            formatVersion = str("format_version"),
            courseId = str("course_id"),
            collaboration = str("collaboration")?.let { ManifestCollaboration.fromWire(it) },
            submission = str("submission")?.let { ManifestSubmission.fromWire(it) },
            scope = str("scope")?.let { ManifestScope.fromWire(it) },
            policy = o["policy"] as? JsonObject,
            courseCert = (o["course_cert"] as? JsonObject)
                ?.let { (parseCourseCert(it) as? CourseCertParse.Ok)?.cert },
        )
    }

    private fun certOf(o: JsonObject): CourseCert =
        assertInstanceOf(CourseCertParse.Ok::class.java, parseCourseCert(o)).cert

    /** `course-cert.json` — the root → course link, its window, and the timestamp grammar. */
    @Nested
    inner class CourseCertVectors {
        private val v by lazy { vector("course-cert.json") }

        @Test
        fun `signed payload is the cert minus root_sig, canonicalized`() {
            val cert = certOf(v["valid_cert"]!!.jsonObject)
            assertEquals(
                v["canonical_json"]!!.jsonPrimitive.content,
                String(buildCourseCertSignedPayload(cert), Charsets.UTF_8),
            )
        }

        @Test
        fun `parseCourseCert round-trips every field`() {
            val raw = v["valid_cert"]!!.jsonObject
            val cert = certOf(raw)
            assertEquals(raw["course_id"]!!.jsonPrimitive.content, cert.courseId)
            assertEquals(raw["course_pubkey"]!!.jsonPrimitive.content, cert.coursePubkey)
            assertEquals(raw["valid_from"]!!.jsonPrimitive.content, cert.validFrom)
            assertEquals(raw["valid_until"]!!.jsonPrimitive.content, cert.validUntil)
            assertEquals(raw["root_sig"]!!.jsonPrimitive.content, cert.rootSig)
        }

        /**
         * Includes `bad_root_sig` (chain step 1 must fail) and
         * `root_key_is_a_parameter` — the same cert verifying under whichever root
         * actually signed it, which is what proves the root key is never a constant
         * in `core/`.
         */
        @Test
        fun `verify cases match log-core`() {
            for (case in v["verify_cases"]!!.jsonArray) {
                val o = case.jsonObject
                val name = o["name"]!!.jsonPrimitive.content
                val input = o["input"]!!.jsonObject
                val cert = certOf(input["cert"]!!.jsonObject)
                val actual = verifyCourseCert(cert, input["root_pubkey_hex"]!!.jsonPrimitive.content)
                assertEquals(o["expected"]!!.jsonObject["valid"]!!.jsonPrimitive.boolean, actual, name)
            }
        }

        /**
         * The exact accepting set of the timestamp grammar. `java.time` accepts
         * leap seconds and `24:00:00` and JS `Date` accepts neither, so this list
         * is what keeps the three ports from silently disagreeing about whether a
         * certificate window binds.
         */
        @Test
        fun `timestamp parse cases match log-core exactly`() {
            for (case in v["timestamp_parse_cases"]!!.jsonArray) {
                val o = case.jsonObject
                val input = o["input"]!!.jsonPrimitive.content
                val expected = o["expected_ms"]!!
                val actual = parseIsoInstantMs(input)
                if (expected is JsonNull) {
                    assertNull(actual, "expected '$input' to be rejected")
                } else {
                    assertEquals(expected.jsonPrimitive.long, actual, input)
                }
            }
        }

        /**
         * The window is evaluated against `issued_at`, never wall-clock now —
         * `expired_long_ago_but_contemporaneous` is the case that catches an
         * implementation reaching for `Instant.now()`.
         */
        @Test
        fun `window cases match log-core`() {
            val template = certOf(v["valid_cert"]!!.jsonObject)
            for (case in v["window_cases"]!!.jsonArray) {
                val o = case.jsonObject
                val name = o["name"]!!.jsonPrimitive.content
                val input = o["input"]!!.jsonObject
                val cert = template.copy(
                    validFrom = input["valid_from"]!!.jsonPrimitive.content,
                    validUntil = input["valid_until"]!!.jsonPrimitive.content,
                )
                val status = checkCertWindow(cert, input["issued_at"]!!.jsonPrimitive.content)
                val expected = o["expected"]!!.jsonObject
                assertEquals(expected["in_window"]!!.jsonPrimitive.boolean, status.inWindow, name)
                val expectedReason = expected["reason"]?.jsonPrimitive?.content
                if (expectedReason == null) {
                    assertInstanceOf(CertWindowStatus.InWindow::class.java, status, name)
                } else {
                    val out = assertInstanceOf(CertWindowStatus.OutOfWindow::class.java, status, name)
                    assertEquals(expectedReason, out.reason.wire, name)
                }
            }
        }
    }

    /** `capture-policy.json` — the professor-facing capture controls (program spec §4). */
    @Nested
    inner class CapturePolicyVectors {
        private val v by lazy { vector("capture-policy.json") }

        private fun expect(o: JsonObject): CapturePolicy = CapturePolicy(
            selectionChange = o["selection_change"]!!.jsonPrimitive.boolean,
            focusChange = o["focus_change"]!!.jsonPrimitive.boolean,
            terminal = o["terminal"]!!.jsonPrimitive.boolean,
            heartbeatIntervalMs = o["heartbeat_interval_ms"]!!.jsonPrimitive.long,
        )

        @Test
        fun `defaults and clamp bounds match log-core`() {
            assertEquals(expect(v["defaults"]!!.jsonObject), DEFAULT_CAPTURE_POLICY)
            val clamp = v["heartbeat_clamp"]!!.jsonObject
            assertEquals(clamp["min_ms"]!!.jsonPrimitive.long, HEARTBEAT_INTERVAL_MIN_MS)
            assertEquals(clamp["max_ms"]!!.jsonPrimitive.long, HEARTBEAT_INTERVAL_MAX_MS)
        }

        /**
         * The hard floor is enforced by the SCHEMA — a floor kind simply has no key
         * in `policy.capture`. These two lists are the assertable statement of that,
         * and they must stay disjoint or some kind has both a gate and a floor.
         */
        @Test
        fun `floor and policy-gated event kinds match log-core`() {
            assertEquals(
                v["floor_event_kinds"]!!.jsonArray.map { it.jsonPrimitive.content },
                FLOOR_EVENT_KINDS,
            )
            // Compared through `wireName`: the vector pins the on-the-wire JSON keys,
            // which is exactly what PolicyGateKey.wireName carries. The Kotlin constant
            // names are local and deliberately not part of the contract.
            assertEquals(
                v["policy_gated_event_kinds"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content },
                POLICY_GATED_EVENT_KINDS.mapValues { it.value.wireName },
            )
            // The gate keys are a CLOSED set, and it is the set the vector names. A key
            // the vector does not know, or one no event kind uses, is a contract break —
            // and since PolicyGateKey is a type, a typo cannot even be written.
            assertEquals(
                v["policy_gated_event_kinds"]!!.jsonObject.values
                    .map { it.jsonPrimitive.content }.toSortedSet(),
                PolicyGateKey.entries.map { it.wireName }.toSortedSet(),
            )
            assertTrue(FLOOR_EVENT_KINDS.none { it in POLICY_GATED_EVENT_KINDS })
        }

        /**
         * Every resolution case, including all seven heartbeat boundaries: below
         * floor, at floor, in range, at ceiling, above ceiling, zero, and
         * non-number (which falls back to the DEFAULT, not to the floor).
         */
        @Test
        fun `resolution cases match log-core`() {
            val cases = v["cases"]!!.jsonArray
            for (case in cases) {
                val o = case.jsonObject
                val name = o["name"]!!.jsonPrimitive.content
                val input = o["input"]!!.let { if (it is JsonNull) null else it }
                assertEquals(expect(o["expected"]!!.jsonObject), resolveCapturePolicy(input), name)
            }
            // Guard against the vector silently losing a boundary case.
            val names = cases.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
            assertTrue(
                names.containsAll(
                    listOf(
                        "heartbeat_below_floor",
                        "heartbeat_at_floor",
                        "heartbeat_in_range",
                        "heartbeat_at_ceiling",
                        "heartbeat_above_ceiling",
                        "heartbeat_zero",
                        "heartbeat_non_number",
                        "retired_doc_open_close_key_ignored",
                        "retired_inline_content_key_ignored",
                    ),
                ),
                "capture-policy.json lost a heartbeat clamp or retired-key case",
            )
        }

        /**
         * A manifest still carrying a RETIRED key must be inert — ignored exactly like any
         * unknown key, never an error. Forward and backward compatibility both depend on
         * that, and the two keys are retired precisely because they were load-bearing:
         * `doc.open` seeds reconstruction, and a paste's content is what lets
         * `internal_move` downgrade `large_paste`. Honouring either key would reintroduce
         * the harm it was removed for.
         */
        @Test
        fun `retired capture keys are inert and cannot suppress their old kinds`() {
            for (name in listOf("retired_doc_open_close_key_ignored", "retired_inline_content_key_ignored")) {
                val case = v["cases"]!!.jsonArray
                    .first { it.jsonObject["name"]!!.jsonPrimitive.content == name }
                val policy = resolveCapturePolicy(case.jsonObject["input"]!!)
                // Resolves to the everything-on default: the retired key changed nothing.
                assertEquals(DEFAULT_CAPTURE_POLICY, policy, name)
                // And the kinds it used to govern are now floor, so they stay captured.
                for (kind in listOf("doc.open", "doc.close", "paste", "fs.external_change")) {
                    assertTrue(isEventKindCaptured(kind, policy), "$name must not suppress $kind")
                }
            }
            // The retired keys are gone from the gate map entirely.
            assertFalse(POLICY_GATED_EVENT_KINDS.values.any { it.wireName == "doc_open_close" })
            assertFalse(POLICY_GATED_EVENT_KINDS.values.any { it.wireName == "inline_content" })
            for (kind in listOf("doc.open", "doc.close", "paste", "fs.external_change")) {
                assertTrue(kind in FLOOR_EVENT_KINDS, "$kind must be on the floor")
            }
        }

        @Test
        fun `floor kinds stay captured under an everything-off policy`() {
            val allOff = v["cases"]!!.jsonArray
                .first { it.jsonObject["name"]!!.jsonPrimitive.content == "all_off" }
                .jsonObject["input"]!!
            val policy = resolveCapturePolicy(allOff)
            for (kind in FLOOR_EVENT_KINDS) {
                assertTrue(isEventKindCaptured(kind, policy), kind)
            }
            for (kind in POLICY_GATED_EVENT_KINDS.keys) {
                assertFalse(isEventKindCaptured(kind, policy), kind)
            }
            for (kind in POLICY_GATED_EVENT_KINDS.keys) {
                assertTrue(isEventKindCaptured(kind, DEFAULT_CAPTURE_POLICY), kind)
            }
        }
    }

    // -----------------------------------------------------------------------
    // S2 identity layer (program spec §S2)
    // -----------------------------------------------------------------------

    private fun enrollmentCertOf(o: JsonObject): EnrollmentCert {
        fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""
        return EnrollmentCert(
            formatVersion = str("format_version"),
            courseId = str("course_id"),
            enrollmentPubkey = str("enrollment_pubkey"),
            validFrom = str("valid_from"),
            validUntil = str("valid_until"),
            courseSig = str("course_sig"),
        )
    }

    private fun enrollmentTokenOf(o: JsonObject): EnrollmentToken {
        fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""
        return EnrollmentToken(
            formatVersion = str("format_version"),
            studentRef = str("student_ref"),
            courseId = str("course_id"),
            studentPubkey = str("student_pubkey"),
            issuedAt = str("issued_at"),
            expiresAt = str("expires_at"),
            enrollmentSig = str("enrollment_sig"),
        )
    }

    /**
     * Rebuild a [SessionIdentity] from raw JSON WITHOUT validating it — the Kotlin
     * stand-in for TypeScript's structural cast. The chain vectors deliberately include
     * artifacts the parsers would reject, because the property under test is that
     * `verifyIdentityChain` gates and re-validates whatever it is handed.
     */
    private fun identityOf(o: JsonObject): SessionIdentity = SessionIdentity(
        enrollment = enrollmentTokenOf(o["enrollment"]!!.jsonObject),
        enrollmentCert = enrollmentCertOf(o["enrollment_cert"]!!.jsonObject),
        sessionPubkeySig = o["session_pubkey_sig"]!!.jsonPrimitive.content,
    )

    /**
     * The 2.0 artifacts out of a [SessionIdentity], whose slots are now typed as the
     * sealed [IdentityCert] / [IdentityCredential] so they can also carry 2.1
     * artifacts. Every use below is on an identity this file built from
     * `enrollment.json`, so the cast is a statement about the fixture, not an
     * assumption about untrusted input.
     */
    private val SessionIdentity.cert20: EnrollmentCert get() = enrollmentCert as EnrollmentCert

    private val SessionIdentity.token20: EnrollmentToken get() = enrollment as EnrollmentToken

    /** `git-event.json` — the commit graph, its canonical bytes, and its chain hashes. */
    @Nested
    inner class GitEventVectors {
        private val v by lazy { vector("git-event.json") }

        private fun payloadOf(o: JsonObject): GitEventPayload = GitEventPayload(
            operation = o["operation"]!!.jsonPrimitive.content,
            commitSha = (o["commit_sha"] as? JsonPrimitive)?.content,
            sha = (o["sha"] as? JsonPrimitive)?.content,
            // Absent stays null; `[]` stays an empty list. Collapsing them here would make
            // the empty-vs-absent cases below vacuous.
            parents = (o["parents"] as? JsonArray)?.map { it.jsonPrimitive.content },
            branch = (o["branch"] as? JsonPrimitive)?.content,
            // The D12 repository discriminator. `as? JsonPrimitive` is null for BOTH an absent
            // key and an explicit JsonNull, which is the writer rule (OMIT, never null) —
            // and the round-trip assertion below is what makes that load-bearing: the
            // `root_commit_sha_null_is_not_absent` case carries `null` in its `data`, so a
            // rebuilt payload that omitted it would no longer equal the vector.
            rootCommitSha = (o[REPOSITORY_DISCRIMINATOR_FIELD] as? JsonPrimitive)
                ?.takeIf { it.isString }?.content,
        )

        /**
         * Every case, pinned twice: the JCS canonical bytes AND the resulting chain hash. A
         * port that orders keys differently, sorts `parents`, or collapses `[]` into absent
         * fails here rather than producing a log whose hashes silently disagree with every
         * other recorder's.
         */
        @Test
        fun `git event cases reproduce log-core canonical json and chain hashes`() {
            val cases = v["cases"]!!.jsonArray
            for (case in cases) {
                val o = case.jsonObject
                val name = o["name"]!!.jsonPrimitive.content
                val data = o["data"]!!.jsonObject

                // `root_commit_sha: null` is a READER case, not a writer one: the writer rule
                // is OMIT, never null, and [GitEventPayload] enforces that structurally, so
                // this payload is deliberately not expressible through it. Its canonical bytes
                // and hash are pinned in `a writer omits the discriminator rather than
                // spelling it null` instead — where they are asserted to DIFFER from what this
                // port can emit, which is the actual claim the case makes.
                if (data[REPOSITORY_DISCRIMINATOR_FIELD] is JsonNull) continue

                // Round-trip through the typed payload: what a recorder would actually emit.
                val rebuilt = payloadOf(data).toJsonObject()
                assertEquals(data, rebuilt, name)
                assertEquals(
                    o["canonical_json"]!!.jsonPrimitive.content,
                    Canonical.canonicalize(rebuilt.toString()),
                    name,
                )

                val e = o["envelope"]!!.jsonObject
                val chained = chainEntry(
                    o["prev_hash"]!!.jsonPrimitive.content,
                    Envelope(
                        seq = e["seq"]!!.jsonPrimitive.long,
                        t = e["t"]!!.jsonPrimitive.long,
                        wall = e["wall"]!!.jsonPrimitive.content,
                        kind = e["kind"]!!.jsonPrimitive.content,
                        data = rebuilt,
                    ),
                )
                assertEquals(o["hash"]!!.jsonPrimitive.content, chained.hash, name)
            }
            val names = cases.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
            assertTrue(
                names.containsAll(
                    listOf(
                        "legacy_1x",
                        "operation_only",
                        "root_commit",
                        "unknown_parents",
                        "ordinary_commit",
                        "merge_commit",
                        "merge_commit_parents_flipped",
                        "detached_head",
                        "branch_with_slash",
                        "branch_non_ascii",
                        "octopus_merge",
                        // D12, the repository discriminator. Nine cases, and the guard names
                        // every one: a truncated fixture must fail loudly rather than shrink
                        // the reachable test space, which is the fixture rule the decision log
                        // draws from bug 10.
                        "root_commit_sha_recorded",
                        "root_commit_sha_sha256_repository",
                        "root_commit_sha_is_the_root_itself",
                        "root_commit_sha_absent_shallow_clone",
                        "root_commit_sha_null_is_not_absent",
                        "root_commit_sha_repository_path_rejected",
                        "root_commit_sha_uppercase_rejected",
                        "root_commit_sha_abbreviated_rejected",
                        "root_commit_sha_empty_rejected",
                    ),
                ),
                "git-event.json lost a mandatory case",
            )
        }

        /**
         * THE D12 NARROWING, case by case: every vector's `discriminator` verdict, reproduced
         * by [readRepositoryDiscriminator].
         *
         * `absent` and `malformed` both fold the observation into the unlabelled repository,
         * so a port that collapsed them would still place every commit correctly — and would
         * silently stop COUNTING the recorders that said something wrong. They are asserted
         * apart for that reason. Neither is ever a finding.
         *
         * The eleven pre-D12 cases carry no `discriminator` key and must read `absent`: an
         * unlabelled payload is exactly the pre-D12 world, which is the compatibility
         * guarantee this whole field rests on.
         */
        @Test
        fun `the repository discriminator narrows exactly as log-core says`() {
            var recorded = 0
            var malformed = 0
            for (case in v["cases"]!!.jsonArray) {
                val o = case.jsonObject
                val name = o["name"]!!.jsonPrimitive.content
                val read = readRepositoryDiscriminator(o["data"]!!.jsonObject)
                val expected = o["discriminator"]?.jsonObject
                if (expected == null) {
                    assertEquals(RepositoryDiscriminatorRead.Absent, read, name)
                    continue
                }
                assertEquals(expected["kind"]!!.jsonPrimitive.content, read.kind, name)
                when (read) {
                    is RepositoryDiscriminatorRead.Recorded -> {
                        recorded++
                        assertEquals(
                            expected["rootCommitSha"]!!.jsonPrimitive.content,
                            read.rootCommitSha,
                            name,
                        )
                    }
                    is RepositoryDiscriminatorRead.Malformed -> {
                        malformed++
                        assertEquals(
                            expected["problem"]!!.jsonPrimitive.content,
                            read.problem.wire,
                            name,
                        )
                    }
                    is RepositoryDiscriminatorRead.Absent -> Unit
                }
            }
            // Guard against a fixture that lost its positive or its negative half. A vector
            // with only accepted cases proves a reader that accepts everything.
            assertEquals(3, recorded, "git-event.json lost a `recorded` case")
            assertEquals(4, malformed, "git-event.json lost a `malformed` case")
        }

        /**
         * MANDATORY, and the writer half of rule 6: this port CANNOT spell the unknown
         * discriminator as `null`.
         *
         * Omission and `null` canonicalize differently and therefore chain to different
         * hashes, exactly as `parents: []` and an absent `parents` do. A `null`-emitting writer
         * would produce a log whose entries hash differently from every other recorder's for
         * the identical observation — a silent, permanent cross-implementation divergence.
         *
         * The claim is made by CONSTRUCTION: `rootCommitSha = null` reproduces the
         * shallow-clone case byte for byte, and can never reproduce the `null` case's bytes.
         */
        @Test
        fun `a writer omits the discriminator rather than spelling it null`() {
            val byName = v["cases"]!!.jsonArray.associateBy {
                it.jsonObject["name"]!!.jsonPrimitive.content
            }
            val absent = byName["root_commit_sha_absent_shallow_clone"]!!.jsonObject
            val nulled = byName["root_commit_sha_null_is_not_absent"]!!.jsonObject

            // The two vectors really are the same payload plus/minus the spelling.
            assertFalse(REPOSITORY_DISCRIMINATOR_FIELD in absent["data"]!!.jsonObject)
            assertTrue(nulled["data"]!!.jsonObject[REPOSITORY_DISCRIMINATOR_FIELD] is JsonNull)
            assertNotEquals(
                absent["hash"]!!.jsonPrimitive.content,
                nulled["hash"]!!.jsonPrimitive.content,
            )

            val emitted = payloadOf(absent["data"]!!.jsonObject).copy(rootCommitSha = null)
                .toJsonObject()
            assertFalse(
                REPOSITORY_DISCRIMINATOR_FIELD in emitted,
                "a writer must never put the key there at all",
            )
            val canonical = Canonical.canonicalize(emitted.toString())
            assertEquals(absent["canonical_json"]!!.jsonPrimitive.content, canonical)
            assertNotEquals(nulled["canonical_json"]!!.jsonPrimitive.content, canonical)
        }

        /**
         * A 64-hex sha-256 root and a 40-hex sha-1 root are both legal object names, and a
         * port that hard-coded 40 would silently unlabel every sha-256 repository — which
         * degrades to lost correlation, never to a finding, and is therefore exactly the kind
         * of defect nothing else would report.
         */
        @Test
        fun `both git object formats are accepted as discriminators`() {
            val byName = v["cases"]!!.jsonArray.associateBy {
                it.jsonObject["name"]!!.jsonPrimitive.content
            }
            for (name in listOf("root_commit_sha_recorded", "root_commit_sha_sha256_repository")) {
                val data = byName[name]!!.jsonObject["data"]!!.jsonObject
                val read = readRepositoryDiscriminator(data)
                assertInstanceOf(RepositoryDiscriminatorRead.Recorded::class.java, read, name)
            }
            assertEquals(
                40,
                (
                    readRepositoryDiscriminator(
                        byName["root_commit_sha_recorded"]!!.jsonObject["data"]!!.jsonObject,
                    ) as RepositoryDiscriminatorRead.Recorded
                    ).rootCommitSha.length,
            )
            assertEquals(
                64,
                (
                    readRepositoryDiscriminator(
                        byName["root_commit_sha_sha256_repository"]!!.jsonObject["data"]!!.jsonObject,
                    ) as RepositoryDiscriminatorRead.Recorded
                    ).rootCommitSha.length,
            )
        }

        /**
         * MANDATORY. `parents[0]` is the branch merged INTO, so flipping the order is a
         * different claim — and the vector pins it to a DIFFERENT hash. A port that sorts
         * `parents` would make these two collide.
         */
        @Test
        fun `flipping parent order changes the chain hash`() {
            val byName = v["cases"]!!.jsonArray.associateBy {
                it.jsonObject["name"]!!.jsonPrimitive.content
            }
            val straight = byName["merge_commit"]!!.jsonObject
            val flipped = byName["merge_commit_parents_flipped"]!!.jsonObject
            assertNotEquals(
                straight["hash"]!!.jsonPrimitive.content,
                flipped["hash"]!!.jsonPrimitive.content,
            )
            // The payloads differ ONLY in parent order.
            assertEquals(
                straight["data"]!!.jsonObject["parents"]!!.jsonArray.toSet(),
                flipped["data"]!!.jsonObject["parents"]!!.jsonArray.toSet(),
            )
        }

        /** MANDATORY. `[]` (root commit) and absent (unreadable) are different bytes. */
        @Test
        fun `an empty parents array and an absent one are different cases`() {
            val byName = v["cases"]!!.jsonArray.associateBy {
                it.jsonObject["name"]!!.jsonPrimitive.content
            }
            val rootCommit = byName["root_commit"]!!.jsonObject
            val unknown = byName["unknown_parents"]!!.jsonObject
            assertTrue("parents" in rootCommit["data"]!!.jsonObject)
            assertFalse("parents" in unknown["data"]!!.jsonObject)
            assertNotEquals(
                rootCommit["hash"]!!.jsonPrimitive.content,
                unknown["hash"]!!.jsonPrimitive.content,
            )
        }

        /**
         * NO AUTHOR IDENTITY, anywhere in the vector family. The approved CPHS protocol
         * treats a new category of identifier as requiring a filed modification BEFORE
         * implementation, so a port that adds an author field is out of protocol — and the
         * vector file says so in `no_author_identity_note`.
         */
        @Test
        fun `no case carries git author identity`() {
            assertTrue(
                v["no_author_identity_note"]!!.jsonPrimitive.content.isNotEmpty(),
                "the vector must keep stating the constraint",
            )
            // `root_commit_sha` joins the STRUCTURAL set, and only because it is structural:
            // it names a repository by its root commit, which is a graph fact. It is
            // deliberately NOT the repository path and NOT a remote URL — a path is arguably
            // an identifier and a remote URL embeds the org and often the student's own
            // username (S14(b)) — and `readRepositoryDiscriminator` rejecting both is what
            // keeps that true for a nonconforming writer as well as a conforming one.
            val allowed = setOf(
                "operation",
                "commit_sha",
                "sha",
                "parents",
                "branch",
                REPOSITORY_DISCRIMINATOR_FIELD,
            )
            for (case in v["cases"]!!.jsonArray) {
                val o = case.jsonObject
                val name = o["name"]!!.jsonPrimitive.content
                assertTrue(
                    allowed.containsAll(o["data"]!!.jsonObject.keys),
                    "$name carries a key outside the structural set: ${o["data"]!!.jsonObject.keys}",
                )
            }
        }

        /**
         * `git.event` is a FLOOR kind and adding fields to its payload does not change that.
         * The commit graph is the exculpatory evidence that a large insert was a merge or a
         * checkout rather than a paste — a course must not be able to switch that off.
         */
        @Test
        fun `git event is on the floor and has no policy gate`() {
            assertTrue("git.event" in FLOOR_EVENT_KINDS)
            assertFalse("git.event" in POLICY_GATED_EVENT_KINDS)
            assertTrue(isEventKindCaptured("git.event", DEFAULT_CAPTURE_POLICY))
            assertTrue(v["floor_note"]!!.jsonPrimitive.content.isNotEmpty())
        }
    }

    /**
     * `session-capabilities.json` — the three §5.6 `session.start` CAPABILITY REPORTS
     * (`git_capture`, `witness_capture`, `file_scope`), read side.
     *
     * Every case's canonical bytes and chain hash are reproduced exactly as
     * [GitEventVectors] and [PeerObservedVectors] do above, and every case's narrowing
     * verdict — for all three fields, on every case, since the three are independent and a
     * `git_capture`-only case still has an `absent` `witness_capture`/`file_scope` verdict
     * worth checking — is reproduced by this port's own [readGitCapture] / [readWitnessCapture]
     * / [readFileScope]. `absent`, `recorded` and `malformed` are asserted apart, never folded:
     * a reader that only ever returns `absent`-or-`recorded` would still pass a suite that
     * checked `kind` for equality without checking the COUNT of each kind, which is why each
     * narrowing test below also asserts the number of `recorded` and `malformed` cases it saw.
     */
    @Nested
    inner class SessionCapabilityVectors {
        private val v by lazy { vector("session-capabilities.json") }

        @Test
        fun `every case reproduces log-core canonical json and chain hash`() {
            val cases = v["cases"]!!.jsonArray
            for (case in cases) {
                val o = case.jsonObject
                val name = o["name"]!!.jsonPrimitive.content
                val data = o["data"]!!.jsonObject

                assertEquals(
                    o["canonical_json"]!!.jsonPrimitive.content,
                    Canonical.canonicalize(data.toString()),
                    name,
                )

                val e = o["envelope"]!!.jsonObject
                val chained = chainEntry(
                    o["prev_hash"]!!.jsonPrimitive.content,
                    Envelope(
                        seq = e["seq"]!!.jsonPrimitive.long,
                        t = e["t"]!!.jsonPrimitive.long,
                        wall = e["wall"]!!.jsonPrimitive.content,
                        kind = e["kind"]!!.jsonPrimitive.content,
                        data = data,
                    ),
                )
                assertEquals(o["hash"]!!.jsonPrimitive.content, chained.hash, name)
            }

            val names = cases.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
            assertEquals(cases.size, names.size, "session-capabilities.json has a duplicate case name")
            assertTrue(
                names.containsAll(
                    listOf(
                        "no_capability_reports",
                        "git_capture_available",
                        "git_capture_unavailable",
                        "git_capture_not_owned",
                        "git_capture_null_is_not_absent",
                        "git_capture_unknown_value_rejected",
                        "git_capture_uppercase_rejected",
                        "git_capture_not_a_string_rejected",
                        "witness_capture_available",
                        "witness_capture_unavailable",
                        "witness_capture_not_owned_rejected",
                        "witness_capture_null_is_not_absent",
                        "file_scope_complete",
                        "file_scope_empty_is_a_real_answer",
                        "file_scope_truncated",
                        "file_scope_missing_complete_rejected",
                        "file_scope_not_an_object_rejected",
                        "file_scope_absolute_path_rejected",
                        "file_scope_windows_path_rejected",
                        "file_scope_remote_url_rejected",
                        "file_scope_scp_remote_rejected",
                        "file_scope_parent_escape_rejected",
                        "file_scope_dotted_names_accepted",
                        "file_scope_unknown_extra_key_accepted",
                        "file_scope_null_is_not_absent",
                        "all_three_reported",
                    ),
                ),
                "session-capabilities.json lost a mandatory case",
            )
        }

        /**
         * `git_capture` narrows into three kinds ([GitCaptureRead.Absent],
         * [GitCaptureRead.Recorded], [GitCaptureRead.Malformed]) exactly as log-core says,
         * reproduced case by case — ACCEPT and REJECT both, per the class docstring.
         */
        @Test
        fun `git_capture narrows exactly as log-core says`() {
            var recorded = 0
            var malformed = 0
            for (case in v["cases"]!!.jsonArray) {
                val o = case.jsonObject
                val name = o["name"]!!.jsonPrimitive.content
                val expected = o["git_capture"]!!.jsonObject
                val read = readGitCapture(o["data"]!!.jsonObject)
                assertEquals(expected["kind"]!!.jsonPrimitive.content, read.kind, name)
                when (read) {
                    is GitCaptureRead.Recorded -> {
                        recorded++
                        assertEquals(expected["capture"]!!.jsonPrimitive.content, read.capture.wire, name)
                    }
                    is GitCaptureRead.Malformed -> {
                        malformed++
                        assertEquals(expected["problem"]!!.jsonPrimitive.content, read.problem.wire, name)
                    }
                    is GitCaptureRead.Absent -> Unit
                }
            }
            assertEquals(4, recorded, "session-capabilities.json lost a git_capture `recorded` case")
            assertEquals(3, malformed, "session-capabilities.json lost a git_capture `malformed` case")
        }

        /** @see `git_capture narrows exactly as log-core says` */
        @Test
        fun `witness_capture narrows exactly as log-core says`() {
            var recorded = 0
            var malformed = 0
            for (case in v["cases"]!!.jsonArray) {
                val o = case.jsonObject
                val name = o["name"]!!.jsonPrimitive.content
                val expected = o["witness_capture"]!!.jsonObject
                val read = readWitnessCapture(o["data"]!!.jsonObject)
                assertEquals(expected["kind"]!!.jsonPrimitive.content, read.kind, name)
                when (read) {
                    is WitnessCaptureRead.Recorded -> {
                        recorded++
                        assertEquals(expected["capture"]!!.jsonPrimitive.content, read.capture.wire, name)
                    }
                    is WitnessCaptureRead.Malformed -> {
                        malformed++
                        assertEquals(expected["problem"]!!.jsonPrimitive.content, read.problem.wire, name)
                    }
                    is WitnessCaptureRead.Absent -> Unit
                }
            }
            assertEquals(3, recorded, "session-capabilities.json lost a witness_capture `recorded` case")
            // git_capture's third value (`not_owned`) fed to witness_capture, rejected as
            // `unknown_value` — there is no witnessing analogue of `not_owned`.
            assertEquals(1, malformed, "session-capabilities.json lost a witness_capture `malformed` case")
        }

        /** @see `git_capture narrows exactly as log-core says` */
        @Test
        fun `file_scope narrows exactly as log-core says, and a bad element rejects the WHOLE set`() {
            var recorded = 0
            var malformed = 0
            for (case in v["cases"]!!.jsonArray) {
                val o = case.jsonObject
                val name = o["name"]!!.jsonPrimitive.content
                val expected = o["file_scope"]!!.jsonObject
                val read = readFileScope(o["data"]!!.jsonObject)
                assertEquals(expected["kind"]!!.jsonPrimitive.content, read.kind, name)
                when (read) {
                    is FileScopeRead.Recorded -> {
                        recorded++
                        assertEquals(expected["complete"]!!.jsonPrimitive.boolean, read.complete, name)
                        assertEquals(
                            expected["watched"]!!.jsonArray.map { it.jsonPrimitive.content },
                            read.watched,
                            name,
                        )
                    }
                    is FileScopeRead.Malformed -> {
                        malformed++
                        assertEquals(expected["problem"]!!.jsonPrimitive.content, read.problem.wire, name)
                    }
                    is FileScopeRead.Absent -> Unit
                }
            }
            assertEquals(6, recorded, "session-capabilities.json lost a file_scope `recorded` case")
            assertEquals(7, malformed, "session-capabilities.json lost a file_scope `malformed` case")
        }

        /**
         * MANDATORY, and the writer half of the rule: an absent field and an explicit `null`
         * both read as absence, and their bytes still DIFFER — exactly the D12 writer-omits-
         * null-never proof in [GitEventVectors], reproduced here for all three fields at once.
         */
        @Test
        fun `a writer omits every capability report rather than spelling it null`() {
            val byName = v["cases"]!!.jsonArray.associateBy { it.jsonObject["name"]!!.jsonPrimitive.content }
            val absent = byName["no_capability_reports"]!!.jsonObject
            for (nulledName in listOf(
                "git_capture_null_is_not_absent",
                "witness_capture_null_is_not_absent",
                "file_scope_null_is_not_absent",
            )) {
                val nulled = byName[nulledName]!!.jsonObject
                assertNotEquals(
                    absent["hash"]!!.jsonPrimitive.content,
                    nulled["hash"]!!.jsonPrimitive.content,
                    nulledName,
                )
                assertNotEquals(
                    absent["canonical_json"]!!.jsonPrimitive.content,
                    nulled["canonical_json"]!!.jsonPrimitive.content,
                    nulledName,
                )
            }
            // [SessionStartPayload.toJsonObject] cannot express a `null` capability report: its
            // three fields are typed `?` and spread with `?.let { put(...) }`. Omission is
            // therefore enforced structurally on this port's writer side, which is exercised
            // directly (not through this fixture) in RecorderContextTest.
        }

        /** None of the three fields is ever a knob: closed enums, no policy gate, no finding. */
        @Test
        fun `the three are capability reports, not capture knobs, and are never a finding`() {
            assertTrue(v["not_a_knob_note"]!!.jsonPrimitive.content.isNotEmpty())
            assertEquals(GitCaptureCapability.WIRE_VALUES, v["git_capture_values"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals(
                WitnessCaptureCapability.WIRE_VALUES,
                v["witness_capture_values"]!!.jsonArray.map { it.jsonPrimitive.content },
            )
            assertEquals(GIT_CAPTURE_FIELD, v["fields"]!!.jsonObject["git_capture"]!!.jsonPrimitive.content)
            assertEquals(WITNESS_CAPTURE_FIELD, v["fields"]!!.jsonObject["witness_capture"]!!.jsonPrimitive.content)
            assertEquals(FILE_SCOPE_FIELD, v["fields"]!!.jsonObject["file_scope"]!!.jsonPrimitive.content)
        }
    }

    /**
     * `peer-observed.json` — peer witnessing: the canonical bytes, the chain hashes, and the
     * narrowing verdict for each case.
     *
     * Every ACCEPTED case is built through this port's own emitter
     * ([PeerObservedPayload.toJsonObject]) rather than by echoing the fixture's `data` back,
     * so the assertion is that this recorder EMITS those bytes — not merely that it can read
     * them. A test that decoded the fixture and re-encoded it would pass on a writer that
     * never produced the shape at all.
     *
     * Every REJECTED case is fed to the narrowing as-is, because a nonconforming payload is by
     * definition one this port cannot construct. Asserting accept AND reject is the point:
     * a reader that accepts everything passes a happy-path-only suite.
     */
    @Nested
    inner class PeerObservedVectors {
        private val v by lazy { vector("peer-observed.json") }

        /** Rebuild a case's `data` through the typed payload — the writer path. */
        private fun emit(o: JsonObject): JsonObject = PeerObservedPayload(
            file = o["file"]!!.jsonPrimitive.content,
            sha256 = o["sha256"]!!.jsonPrimitive.content,
            bytes = o["bytes"]!!.jsonPrimitive.long,
            // `as? JsonPrimitive` is null for JsonNull, which is the "chain not read" case.
            sessionId = (o["session_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
            seqHigh = (o["seq_high"] as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString }?.long,
            lastHash = (o["last_hash"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
            state = PeerObservedState.fromWire(o["state"]!!.jsonPrimitive.content)!!,
        ).toJsonObject()

        @Test
        fun `the five states match log-core, in order`() {
            assertEquals(
                v["states"]!!.jsonArray.map { it.jsonPrimitive.content },
                PeerObservedState.WIRE_VALUES,
            )
        }

        /**
         * The accepted cases, emitted by this port and pinned twice: the JCS canonical bytes
         * AND the resulting chain hash.
         *
         * `unparseable_file` and `seq_high_zero` are what make this load-bearing. The first
         * carries all three chain fields as EXPLICIT nulls — omitting them changes the
         * canonical bytes and therefore the hash, so a port that spelled absence as an absent
         * key fails on the hash rather than shipping a silently divergent log. The second
         * carries `seq_high: 0`, the shortest honest witness there is; a truthiness check
         * anywhere in the emitter turns it into all-nulls and fails here too.
         */
        @Test
        fun `accepted cases reproduce log-core canonical json and chain hashes`() {
            var accepted = 0
            for (case in v["cases"]!!.jsonArray) {
                val o = case.jsonObject
                val name = o["name"]!!.jsonPrimitive.content
                if (!o["accepted"]!!.jsonPrimitive.boolean) continue
                val data = o["data"]!!.jsonObject
                accepted++

                // `accepted_unknown_extra_key` is a READER case: the extra key is by
                // definition one this port does not know, so it cannot be emitted. Its bytes
                // are still pinned below, against the fixture's own `data`.
                val rebuilt = if (data.keys == setOf(
                        "file", "sha256", "bytes", "session_id", "seq_high", "last_hash", "state",
                    )
                ) {
                    val emitted = emit(data)
                    assertEquals(data, emitted, name)
                    emitted
                } else {
                    data
                }

                assertEquals(
                    o["canonical_json"]!!.jsonPrimitive.content,
                    Canonical.canonicalize(rebuilt.toString()),
                    name,
                )
                val e = o["envelope"]!!.jsonObject
                val chained = chainEntry(
                    o["prev_hash"]!!.jsonPrimitive.content,
                    Envelope(
                        seq = e["seq"]!!.jsonPrimitive.long,
                        t = e["t"]!!.jsonPrimitive.long,
                        wall = e["wall"]!!.jsonPrimitive.content,
                        kind = e["kind"]!!.jsonPrimitive.content,
                        data = rebuilt,
                    ),
                )
                assertEquals(o["hash"]!!.jsonPrimitive.content, chained.hash, name)

                // ...and the narrowing accepts it, reporting the same value back.
                val parsed = validatePeerObservedPayload(rebuilt)
                assertInstanceOf(PeerObservedParse.Ok::class.java, parsed, name)
                val value = o["value"]!!.jsonObject
                val payload = (parsed as PeerObservedParse.Ok).payload
                assertEquals(value["file"]!!.jsonPrimitive.content, payload.file, name)
                assertEquals(value["sha256"]!!.jsonPrimitive.content, payload.sha256, name)
                assertEquals(value["bytes"]!!.jsonPrimitive.long, payload.bytes, name)
                assertEquals(value["state"]!!.jsonPrimitive.content, payload.state.wire, name)
                assertEquals(
                    (value["session_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
                    payload.sessionId,
                    name,
                )
                assertEquals(
                    (value["seq_high"] as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString }?.long,
                    payload.seqHigh,
                    name,
                )
                assertEquals(
                    (value["last_hash"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
                    payload.lastHash,
                    name,
                )
            }
            assertEquals(7, accepted, "peer-observed.json lost an accepted case")
        }

        /**
         * The rejected cases, each with the exact error variant.
         *
         * The variant matters and is not decoration: `partially_parsed` and
         * `unparseable_with_chain_values` are two different self-contradictions, and a port
         * that collapsed them would still refuse the payload while losing the one thing a
         * staff-facing report can say about WHY.
         */
        @Test
        fun `rejected cases are refused with log-core's own error`() {
            var rejected = 0
            for (case in v["cases"]!!.jsonArray) {
                val o = case.jsonObject
                val name = o["name"]!!.jsonPrimitive.content
                if (o["accepted"]!!.jsonPrimitive.boolean) continue
                rejected++

                val parsed = validatePeerObservedPayload(o["data"]!!.jsonObject)
                assertInstanceOf(PeerObservedParse.Err::class.java, parsed, name)
                val error = (parsed as PeerObservedParse.Err).error
                val expected = o["error"]!!.jsonObject
                assertEquals(expected["kind"]!!.jsonPrimitive.content, error.kind, name)

                when (error) {
                    is PeerObservedShapeError.PartiallyParsed -> {
                        assertEquals(
                            expected["present"]!!.jsonArray.map { it.jsonPrimitive.content },
                            error.present,
                            name,
                        )
                        assertEquals(
                            expected["absent"]!!.jsonArray.map { it.jsonPrimitive.content },
                            error.absent,
                            name,
                        )
                    }
                    is PeerObservedShapeError.UnparseableWithChainValues -> assertEquals(
                        expected["present"]!!.jsonArray.map { it.jsonPrimitive.content },
                        error.present,
                        name,
                    )
                    is PeerObservedShapeError.UnknownState -> assertEquals(
                        expected["state"]!!.jsonPrimitive.content,
                        error.state,
                        name,
                    )
                    is PeerObservedShapeError.BadField -> assertEquals(
                        expected["field"]!!.jsonPrimitive.content,
                        error.field,
                        name,
                    )
                    else -> Unit
                }

                // The rejected bytes still chain — a witness this reader declines is not a
                // corrupt log, and `parseEntries` must not reject an unknown-shaped payload.
                val e = o["envelope"]!!.jsonObject
                val chained = chainEntry(
                    o["prev_hash"]!!.jsonPrimitive.content,
                    Envelope(
                        seq = e["seq"]!!.jsonPrimitive.long,
                        t = e["t"]!!.jsonPrimitive.long,
                        wall = e["wall"]!!.jsonPrimitive.content,
                        kind = e["kind"]!!.jsonPrimitive.content,
                        data = o["data"]!!.jsonObject,
                    ),
                )
                assertEquals(o["hash"]!!.jsonPrimitive.content, chained.hash, name)
            }
            assertEquals(5, rejected, "peer-observed.json lost a rejected case")
        }

        /**
         * Guard against a truncated fixture. Every case is named, so a vector that quietly
         * lost its `seq_high: 0` or its `unparseable` case fails here rather than shrinking
         * the reachable test space — the fixture rule the decision log draws from bug 10.
         */
        @Test
        fun `the vector still carries every mandatory case`() {
            val names = v["cases"]!!.jsonArray
                .map { it.jsonObject["name"]!!.jsonPrimitive.content }
                .toSet()
            assertTrue(
                names.containsAll(
                    listOf(
                        "appeared_parsed",
                        "grew",
                        "shrank",
                        "disappeared",
                        "unparseable_file",
                        "seq_high_zero",
                        "rejected_named_session_without_tip",
                        "rejected_seq_without_hash",
                        "rejected_unparseable_with_chain_values",
                        "rejected_unknown_state",
                        "rejected_uppercase_sha256",
                        "accepted_unknown_extra_key",
                    ),
                ),
                "peer-observed.json lost a mandatory case: $names",
            )
            assertEquals(12, names.size)
        }

        /**
         * `peer.observed` is a FLOOR kind: it has no key in `policy.capture`, so "off" is not
         * expressible. Provisional — see the note in the vector and in [FLOOR_EVENT_KINDS].
         */
        @Test
        fun `peer observed is on the floor and has no policy gate`() {
            assertTrue("peer.observed" in FLOOR_EVENT_KINDS)
            assertFalse("peer.observed" in POLICY_GATED_EVENT_KINDS)
            assertTrue(isEventKindCaptured("peer.observed", DEFAULT_CAPTURE_POLICY))
            assertTrue(v["floor_note"]!!.jsonPrimitive.content.isNotEmpty())
        }

        /**
         * NO IDENTITY, anywhere in the vector family. A witness names a FILE and a CHAIN
         * POSITION and nothing else. This payload describes somebody ELSE's artifact, so the
         * CPHS constraint that keeps author identity out of `git.event` applies with more
         * force — and `file` is a BASENAME, never a path that could leak a directory layout.
         */
        @Test
        fun `no case carries identity or a path outside the provenance directory`() {
            assertTrue(v["no_identity_note"]!!.jsonPrimitive.content.isNotEmpty())
            val structural = setOf(
                "file", "sha256", "bytes", "session_id", "seq_high", "last_hash", "state",
            )
            for (case in v["cases"]!!.jsonArray) {
                val o = case.jsonObject
                val name = o["name"]!!.jsonPrimitive.content
                val data = o["data"]!!.jsonObject
                // `accepted_unknown_extra_key` deliberately carries one extra key to prove
                // forward compatibility; nothing else may.
                if (name != "accepted_unknown_extra_key") {
                    assertEquals(structural, data.keys, name)
                }
                val file = data["file"]!!.jsonPrimitive.content
                assertFalse(file.contains('/'), "$name: file must be a basename, not a path")
                assertFalse(file.contains('\\'), "$name: file must be a basename, not a path")
            }
        }
    }

    /** `student-keys.json` — the HKDF derivation, a byte-for-byte cross-language contract. */
    @Nested
    inner class StudentKeyVectors {
        private val v by lazy { vector("student-keys.json") }

        /**
         * The HKDF parameters themselves, not just their output. A port that got the salt
         * or the info prefix wrong would produce plausible-looking keys that simply never
         * match another editor's, and the failure would surface as a signature mismatch
         * that reads like tampering.
         */
        @Test
        fun `hkdf parameters match log-core`() {
            val p = v["hkdf_params"]!!.jsonObject
            assertEquals("SHA-256", p["hash"]!!.jsonPrimitive.content)
            assertEquals(
                p["salt_utf8"]!!.jsonPrimitive.content,
                STUDENT_KEY_HKDF_SALT_UTF8,
            )
            assertEquals(
                p["salt_hex"]!!.jsonPrimitive.content,
                Ed25519.bytesToHex(studentKeyHkdfSalt()),
            )
            // 25 bytes, deliberately non-empty: HKDF's absent-salt rule is a place three
            // implementations can quietly disagree.
            assertEquals(25, studentKeyHkdfSalt().size)
            assertEquals(
                p["info_prefix_utf8"]!!.jsonPrimitive.content,
                STUDENT_KEY_HKDF_INFO_PREFIX,
            )
            assertEquals(p["output_length_bytes"]!!.jsonPrimitive.int, STUDENT_KEY_SEED_BYTES)
            assertEquals(v["master_secret_bytes"]!!.jsonPrimitive.int, STUDENT_MASTER_SECRET_BYTES)
        }

        /** Every derivation case: the info string, the seed, and the derived public key. */
        @Test
        fun `derivation cases match log-core byte for byte`() {
            for (case in v["derivation_cases"]!!.jsonArray) {
                val o = case.jsonObject
                val input = o["input"]!!.jsonObject
                val expected = o["expected"]!!.jsonObject
                val master = Ed25519.hexToBytes(input["master_secret_hex"]!!.jsonPrimitive.content)
                val courseId = input["course_id"]!!.jsonPrimitive.content

                assertEquals(
                    expected["info_utf8"]!!.jsonPrimitive.content,
                    STUDENT_KEY_HKDF_INFO_PREFIX + courseId,
                    courseId,
                )
                val seed = deriveCourseKeySeed(master, courseId)
                assertEquals(expected["seed_hex"]!!.jsonPrimitive.content, Ed25519.bytesToHex(seed), courseId)

                val keypair = deriveCourseKeypair(master, courseId)
                assertEquals(expected["pubkey_hex"]!!.jsonPrimitive.content, keypair.publicKeyHex, courseId)
                // The seed IS the private key: no rejection sampling, no retry loop.
                assertArrayEquals(seed, keypair.privateKey)
            }
        }

        /**
         * MANDATORY. The `cs61b` / `cs61b-extra` pair exists specifically to catch a port
         * that concatenates the info prefix without its trailing colon separator. This
         * asserts the property directly rather than only through the pinned hexes.
         */
        @Test
        fun `a course id that is a prefix of another derives a different key`() {
            val cases = v["derivation_cases"]!!.jsonArray.associateBy {
                it.jsonObject["input"]!!.jsonObject["course_id"]!!.jsonPrimitive.content
            }
            assertTrue("cs61b" in cases, "student-keys.json lost the prefix-collision guard")
            assertTrue("cs61b-extra" in cases, "student-keys.json lost the prefix-collision guard")

            val master = Ed25519.hexToBytes(
                cases["cs61b"]!!.jsonObject["input"]!!.jsonObject["master_secret_hex"]!!.jsonPrimitive.content,
            )
            assertFalse(
                deriveCourseKeySeed(master, "cs61b").contentEquals(
                    deriveCourseKeySeed(master, "cs61b-extra"),
                ),
                "the trailing colon in the info prefix is load-bearing",
            )
            // The trailing colon is what makes that true — state it so a future edit that
            // drops it fails here with the reason, not just with a hex mismatch.
            assertTrue(STUDENT_KEY_HKDF_INFO_PREFIX.endsWith(":"))
        }

        /**
         * Same master, different course: unlinkable keys. This is the privacy claim the
         * whole derivation exists to make.
         */
        @Test
        fun `the same master secret yields unlinkable keys across courses`() {
            val master = ByteArray(32) { 0x2a }
            assertNotEquals(
                deriveCourseKeypair(master, "berkeley-cs61b").publicKeyHex,
                deriveCourseKeypair(master, "berkeley-cs61c").publicKeyHex,
            )
            // Deterministic: re-deriving on a new machine needs only the master secret,
            // which is what makes recovery-without-escrow work.
            assertEquals(
                deriveCourseKeypair(master, "berkeley-cs61b").publicKeyHex,
                deriveCourseKeypair(master, "berkeley-cs61b").publicKeyHex,
            )
        }

        @Test
        fun `derivation rejects a malformed master secret or empty course id`() {
            assertThrows(IllegalArgumentException::class.java) {
                deriveCourseKeySeed(ByteArray(31), "berkeley-cs61b")
            }
            assertThrows(IllegalArgumentException::class.java) {
                deriveCourseKeySeed(ByteArray(32), "")
            }
            assertEquals(STUDENT_MASTER_SECRET_BYTES, generateStudentMasterSecret().size)
        }
    }

    /** `enrollment.json` — the identity chain and its two non-fatal windows. */
    @Nested
    inner class EnrollmentVectors {
        private val v by lazy { vector("enrollment.json") }

        /** The exact bytes all three ports must reproduce for each of the three payloads. */
        @Test
        fun `signed payloads reproduce log-core canonical json`() {
            val canonical = v["canonical_json"]!!.jsonObject
            assertEquals(
                canonical["enrollment_cert"]!!.jsonPrimitive.content,
                String(
                    buildEnrollmentCertSignedPayload(
                        enrollmentCertOf(v["valid_enrollment_cert"]!!.jsonObject),
                    ),
                    Charsets.UTF_8,
                ),
            )
            assertEquals(
                canonical["enrollment_token"]!!.jsonPrimitive.content,
                String(
                    buildEnrollmentTokenSignedPayload(
                        enrollmentTokenOf(v["valid_enrollment_token"]!!.jsonObject),
                    ),
                    Charsets.UTF_8,
                ),
            )
            val binding = v["session_pubkey_binding"]!!.jsonObject
            assertEquals(
                canonical["session_pubkey_binding"]!!.jsonPrimitive.content,
                String(
                    buildSessionPubkeyBindingPayload(
                        SessionPubkeyBinding(
                            courseId = binding["course_id"]!!.jsonPrimitive.content,
                            studentRef = binding["student_ref"]!!.jsonPrimitive.content,
                            sessionPubkey = binding["session_pubkey"]!!.jsonPrimitive.content,
                        ),
                    ),
                    Charsets.UTF_8,
                ),
            )
            // The domain-separation tag is part of the contract, not an implementation detail.
            assertEquals(
                v["session_pubkey_binding_purpose"]!!.jsonPrimitive.content,
                SESSION_PUBKEY_BINDING_PURPOSE,
            )
            assertEquals(v["format_version"]!!.jsonPrimitive.content, ENROLLMENT_FORMAT_VERSION)
        }

        /** Each link verifies in isolation against the key the layer above vouched for. */
        @Test
        fun `each single link verifies against its own issuer key`() {
            val cert = enrollmentCertOf(v["valid_enrollment_cert"]!!.jsonObject)
            val token = enrollmentTokenOf(v["valid_enrollment_token"]!!.jsonObject)
            val binding = v["session_pubkey_binding"]!!.jsonObject

            assertTrue(verifyEnrollmentCert(cert, v["course_pubkey_hex"]!!.jsonPrimitive.content))
            assertTrue(verifyEnrollmentToken(token, v["enrollment_pubkey_hex"]!!.jsonPrimitive.content))
            assertFalse(
                verifyEnrollmentToken(token, v["wrong_enrollment_pubkey_hex"]!!.jsonPrimitive.content),
            )
            assertTrue(
                verifySessionPubkeySig(
                    SessionPubkeyBinding(
                        courseId = binding["course_id"]!!.jsonPrimitive.content,
                        studentRef = binding["student_ref"]!!.jsonPrimitive.content,
                        sessionPubkey = binding["session_pubkey"]!!.jsonPrimitive.content,
                    ),
                    binding["sig"]!!.jsonPrimitive.content,
                    v["student_pubkey_hex"]!!.jsonPrimitive.content,
                ),
            )
        }

        /**
         * The ordered chain walk.
         *
         * `cross_course_forgery` is the one that carries the security argument: 61B's
         * course key certifies an enrollment key "for 61C", that key mints a genuine 61C
         * token, and steps 1 and 2 BOTH pass because every signature really is valid. Only
         * comparing the course id across all three links catches it, which is why three
         * ids are compared and not two.
         *
         * Both expiry cases must come back `ok` — an out-of-window credential is reported,
         * never enforced, because silently refusing to record for a whole class is a worse
         * failure for an integrity tool than recording under a stale credential.
         */
        @Test
        fun `chain cases match log-core`() {
            val cases = v["chain_cases"]!!.jsonArray
            for (case in cases) {
                val o = case.jsonObject
                val name = o["name"]!!.jsonPrimitive.content
                val input = o["input"]!!.jsonObject
                val expected = o["expected"]!!.jsonObject

                val actual = verifyIdentityChain(
                    identity = identityOf(input["identity"]!!.jsonObject),
                    sessionPubkey = input["session_pubkey"]!!.jsonPrimitive.content,
                    courseCert = certOf(input["course_cert"]!!.jsonObject),
                    sessionStartedAt = input["session_started_at"]!!.jsonPrimitive.content,
                )

                if (expected["ok"]!!.jsonPrimitive.boolean) {
                    val ok = assertInstanceOf(IdentityChain.CourseOk::class.java, actual, name)
                    assertEquals(ENROLLMENT_FORMAT_VERSION, ok.identityVersion, name)
                    assertEquals("course", ok.scope, name)
                    assertEquals(expected["course_id"]!!.jsonPrimitive.content, ok.courseId, name)
                    assertEquals(expected["student_ref"]!!.jsonPrimitive.content, ok.studentRef, name)
                    assertEquals(expected["student_pubkey"]!!.jsonPrimitive.content, ok.studentPubkey, name)
                    assertEquals(
                        expected["enrollment_pubkey"]!!.jsonPrimitive.content,
                        ok.enrollmentPubkey,
                        name,
                    )
                    assertWindow(expected["cert_window"]!!.jsonObject, ok.certWindow, "$name cert_window")
                    assertWindow(expected["token_window"]!!.jsonObject, ok.tokenWindow, "$name token_window")
                } else {
                    val err = assertInstanceOf(IdentityChain.Err::class.java, actual, name)
                    val expectedErr = expected["error"]!!.jsonObject
                    assertEquals(expectedErr["kind"]!!.jsonPrimitive.content, err.kind, name)
                    // The version gate reports TWO distinct kinds, and the split is the
                    // point: an unknown version in the cert slot is refused outright,
                    // while a credential disagreeing with a known-version cert is a
                    // MISMATCH. The old single `not_enrollment_2_0` kind could not say
                    // which, because it predates 2.1 existing at all.
                    if (err is IdentityChain.UnsupportedIdentityVersion) {
                        assertEquals(
                            expectedErr["format_version"]!!.jsonPrimitive.content,
                            err.formatVersion,
                            name,
                        )
                    }
                    if (err is IdentityChain.IdentityVersionMismatch) {
                        assertEquals(
                            expectedErr["cert_version"]!!.jsonPrimitive.content,
                            err.certVersion,
                            name,
                        )
                        assertEquals(
                            expectedErr["credential_version"]!!.jsonPrimitive.content,
                            err.credentialVersion,
                            name,
                        )
                    }
                    if (err is IdentityChain.CourseIdMismatch) {
                        assertEquals(
                            expectedErr["token_course_id"]!!.jsonPrimitive.content,
                            err.tokenCourseId,
                            name,
                        )
                        assertEquals(
                            expectedErr["cert_course_id"]!!.jsonPrimitive.content,
                            err.certCourseId,
                            name,
                        )
                        assertEquals(
                            expectedErr["course_cert_course_id"]!!.jsonPrimitive.content,
                            err.courseCertCourseId,
                            name,
                        )
                    }
                }
            }
            // Guard against the vector silently losing a mandatory case.
            val names = cases.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
            assertTrue(
                names.containsAll(
                    listOf(
                        "valid",
                        "cert_not_2_0",
                        "token_not_2_0",
                        "cert_signed_by_wrong_course_key",
                        "token_signed_by_uncertified_key",
                        "cross_course_forgery",
                        "session_pubkey_sig_from_another_student",
                        "session_pubkey_not_the_countersigned_one",
                        "expired_token_is_NOT_fatal",
                        "expired_enrollment_cert_is_NOT_fatal",
                    ),
                ),
                "enrollment.json lost a mandatory chain case",
            )
        }

        /**
         * The cross-course forgery, stated as the property rather than as a vector lookup:
         * BOTH signatures are genuine, and only step 3 rejects it.
         */
        @Test
        fun `cross-course forgery is caught only by comparing every course id`() {
            val input = v["chain_cases"]!!.jsonArray
                .first { it.jsonObject["name"]!!.jsonPrimitive.content == "cross_course_forgery" }
                .jsonObject["input"]!!.jsonObject
            val identity = identityOf(input["identity"]!!.jsonObject)
            val courseCert = certOf(input["course_cert"]!!.jsonObject)

            // Steps 1 and 2 both pass: the 61B course key really did certify this enrollment
            // key, and that key really did mint this token.
            assertTrue(verifyEnrollmentCert(identity.cert20, courseCert.coursePubkey))
            assertTrue(
                verifyEnrollmentToken(identity.token20, identity.cert20.enrollmentPubkey),
            )
            // The cert and token agree with each other — only the course_cert disagrees.
            assertEquals(identity.token20.courseId, identity.cert20.courseId)
            assertNotEquals(identity.cert20.courseId, courseCert.courseId)

            val err = assertInstanceOf(
                IdentityChain.CourseIdMismatch::class.java,
                verifyIdentityChain(
                    identity,
                    input["session_pubkey"]!!.jsonPrimitive.content,
                    courseCert,
                    input["session_started_at"]!!.jsonPrimitive.content,
                ),
            )
            assertEquals(courseCert.courseId, err.courseCertCourseId)
        }

        /**
         * The token window, judged against the SESSION start time — never wall-clock now,
         * so an archived bundle still reads as in-window years later. A date-only
         * `expires_at` covers its whole day and ends at the next midnight, the same
         * asymmetric rule `course_cert.valid_until` uses.
         */
        @Test
        fun `token window cases match log-core`() {
            for (case in v["token_window_cases"]!!.jsonArray) {
                val o = case.jsonObject
                val name = o["name"]!!.jsonPrimitive.content
                val input = o["input"]!!.jsonObject
                val token = enrollmentTokenOf(v["valid_enrollment_token"]!!.jsonObject).copy(
                    issuedAt = input["issued_at"]!!.jsonPrimitive.content,
                    expiresAt = input["expires_at"]!!.jsonPrimitive.content,
                )
                assertWindow(
                    o["expected"]!!.jsonObject,
                    checkTokenWindow(token, input["at"]!!.jsonPrimitive.content),
                    name,
                )
            }
        }

        /** Shape is validated before any signature work, for both artifacts. */
        @Test
        fun `a malformed artifact is rejected before signature verification`() {
            val input = v["chain_cases"]!!.jsonArray
                .first { it.jsonObject["name"]!!.jsonPrimitive.content == "valid" }
                .jsonObject["input"]!!.jsonObject
            val identity = identityOf(input["identity"]!!.jsonObject)
            val courseCert = certOf(input["course_cert"]!!.jsonObject)
            val startedAt = input["session_started_at"]!!.jsonPrimitive.content
            val sessionPubkey = input["session_pubkey"]!!.jsonPrimitive.content

            // JCS omits an absent key, so an artifact missing a required field would sign and
            // verify cleanly while carrying nothing there. Shape must be checked first.
            assertInstanceOf(
                IdentityChain.InvalidCertShape::class.java,
                verifyIdentityChain(
                    identity.copy(enrollmentCert = identity.cert20.copy(courseId = "")),
                    sessionPubkey,
                    courseCert,
                    startedAt,
                ),
            )
            assertInstanceOf(
                IdentityChain.InvalidTokenShape::class.java,
                verifyIdentityChain(
                    identity.copy(enrollment = identity.token20.copy(studentRef = "")),
                    sessionPubkey,
                    courseCert,
                    startedAt,
                ),
            )
            // A window whose upper bound precedes its lower bound never binds, which would
            // silently remove the only offline control this scheme has.
            assertInstanceOf(
                IdentityChain.InvalidCertShape::class.java,
                verifyIdentityChain(
                    identity.copy(
                        enrollmentCert = identity.cert20.copy(validUntil = "2020-01-01"),
                    ),
                    sessionPubkey,
                    courseCert,
                    startedAt,
                ),
            )
            // A non-hex session pubkey is reported distinctly from a bad signature.
            assertInstanceOf(
                IdentityChain.InvalidSessionPubkey::class.java,
                verifyIdentityChain(identity, "not-hex", courseCert, startedAt),
            )
        }

        /** Both artifacts round-trip through their transport form. */
        @Test
        fun `enrollment artifacts round-trip through transport`() {
            val cert = enrollmentCertOf(v["valid_enrollment_cert"]!!.jsonObject)
            val token = enrollmentTokenOf(v["valid_enrollment_token"]!!.jsonObject)
            assertEquals(
                cert,
                assertInstanceOf(
                    EnrollmentParse.Ok::class.java,
                    parseEnrollmentCert(cert.toJsonObject()),
                ).value,
            )
            assertEquals(
                token,
                assertInstanceOf(
                    EnrollmentParse.Ok::class.java,
                    parseEnrollmentToken(token.toJsonObject()),
                ).value,
            )
        }
    }

    /**
     * `identity.json` — the INSTITUTION-scoped identity chain at format_version 2.1,
     * plus the archived 2.0 material that must keep verifying through the same entry
     * point.
     *
     * Three things this suite exists to pin, in descending order of how badly they
     * hurt to get wrong:
     *
     *  1. **The anchor check** (`cross_institution_forgery`). MANDATORY. Root
     *     certifies many institutions, so a genuine signature by a genuinely
     *     certified key proves who signed, never whom they were entitled to speak
     *     for. It is the one case where every signature in the bundle verifies and
     *     the chain must still be refused.
     *  2. **The version routing.** `legacy_2_0_cases` walk ARCHIVED course-scoped
     *     identity blocks through the SAME [verifyIdentityChain]. An implementation
     *     that routed on which fields are present rather than on the signed
     *     `format_version` gets these wrong.
     *  3. **The canonical bytes.** A canonicalization disagreement surfaces here as a
     *     readable string diff rather than as an inscrutable signature failure.
     *
     * Every case is driven through this module's own implementation — the fixture
     * supplies inputs and expectations only; nothing is decoded and re-emitted.
     */
    @Nested
    inner class InstitutionIdentityVectors {
        private val v by lazy { vector("identity.json") }

        /**
         * Rebuild an [InstitutionCert] from raw JSON WITHOUT validating it — the
         * Kotlin stand-in for TypeScript's structural cast. The chain vectors
         * deliberately include artifacts the parser would reject, because the
         * property under test is that [verifyIdentityChain] gates and re-validates
         * whatever it is handed.
         */
        private fun institutionCertOf(o: JsonObject): InstitutionCert {
            fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""
            return InstitutionCert(
                formatVersion = str("format_version"),
                institutionId = str("institution_id"),
                institutionPubkey = str("institution_pubkey"),
                validFrom = str("valid_from"),
                validUntil = str("valid_until"),
                rootSig = str("root_sig"),
            )
        }

        /** As [institutionCertOf], for the credential slot. */
        private fun studentCredentialOf(o: JsonObject): StudentCredential {
            fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""
            return StudentCredential(
                formatVersion = str("format_version"),
                institutionId = str("institution_id"),
                studentRef = str("student_ref"),
                studentPubkey = str("student_pubkey"),
                issuedAt = str("issued_at"),
                expiresAt = str("expires_at"),
                institutionSig = str("institution_sig"),
            )
        }

        private fun identity21Of(o: JsonObject): SessionIdentity = SessionIdentity(
            enrollment = studentCredentialOf(o["enrollment"]!!.jsonObject),
            enrollmentCert = institutionCertOf(o["enrollment_cert"]!!.jsonObject),
            sessionPubkeySig = o["session_pubkey_sig"]!!.jsonPrimitive.content,
        )

        /** The archived 2.0 slots, decoded as 2.0 artifacts. */
        private fun identity20Of(o: JsonObject): SessionIdentity = SessionIdentity(
            enrollment = enrollmentTokenOf(o["enrollment"]!!.jsonObject),
            enrollmentCert = enrollmentCertOf(o["enrollment_cert"]!!.jsonObject),
            sessionPubkeySig = o["session_pubkey_sig"]!!.jsonPrimitive.content,
        )

        private fun caseNamed(section: String, name: String): JsonObject =
            v[section]!!.jsonArray
                .first { it.jsonObject["name"]!!.jsonPrimitive.content == name }
                .jsonObject

        /**
         * The three signed payloads, byte for byte. These are the most useful
         * assertions in the file for a port: a canonicalization disagreement shows up
         * as a readable string diff instead of an inscrutable signature failure.
         */
        @Test
        fun `signed payloads reproduce log-core canonical json`() {
            val canonical = v["canonical_json"]!!.jsonObject
            assertEquals(
                canonical["institution_cert"]!!.jsonPrimitive.content,
                String(
                    buildInstitutionCertSignedPayload(
                        institutionCertOf(v["valid_institution_cert"]!!.jsonObject),
                    ),
                    Charsets.UTF_8,
                ),
            )
            assertEquals(
                canonical["student_credential"]!!.jsonPrimitive.content,
                String(
                    buildStudentCredentialSignedPayload(
                        studentCredentialOf(v["valid_student_credential"]!!.jsonObject),
                    ),
                    Charsets.UTF_8,
                ),
            )
            val binding = v["session_pubkey_binding"]!!.jsonObject
            assertEquals(
                canonical["session_pubkey_binding"]!!.jsonPrimitive.content,
                String(
                    buildStudentSessionBindingPayload(
                        StudentSessionBinding(
                            institutionId = binding["institution_id"]!!.jsonPrimitive.content,
                            studentRef = binding["student_ref"]!!.jsonPrimitive.content,
                            sessionPubkey = binding["session_pubkey"]!!.jsonPrimitive.content,
                        ),
                    ),
                    Charsets.UTF_8,
                ),
            )
            // The 2.1 domain-separation tag is part of the contract, and it MUST differ
            // from the 2.0 one — that is what stops a countersignature being replayed
            // across versions.
            assertEquals(
                v["session_pubkey_binding_purpose"]!!.jsonPrimitive.content,
                STUDENT_SESSION_BINDING_PURPOSE,
            )
            assertNotEquals(SESSION_PUBKEY_BINDING_PURPOSE, STUDENT_SESSION_BINDING_PURPOSE)
            assertEquals(
                v["format_version"]!!.jsonPrimitive.content,
                INSTITUTION_IDENTITY_FORMAT_VERSION,
            )
        }

        /**
         * The DISCRIMINATOR is `enrollment_cert.format_version`, and the vector says so
         * explicitly. A port that hard-codes the field name elsewhere, or routes on
         * field presence, disagrees with this.
         */
        @Test
        fun `the discriminator is the signed format_version in the cert slot`() {
            val d = v["discriminator"]!!.jsonObject
            assertEquals("enrollment_cert.format_version", d["field"]!!.jsonPrimitive.content)
            assertEquals(
                ENROLLMENT_FORMAT_VERSION,
                d["course_scoped"]!!.jsonPrimitive.content,
            )
            assertEquals(
                INSTITUTION_IDENTITY_FORMAT_VERSION,
                d["institution_scoped"]!!.jsonPrimitive.content,
            )
        }

        /**
         * The institution cert becomes a TRUST ANCHOR only once its `root_sig` verifies
         * against the embedded root public key. [verifyIdentityChain] deliberately does
         * NOT do this — the caller must, exactly as for `course_cert`.
         */
        @Test
        fun `root verification cases match log-core`() {
            val cases = v["root_verification_cases"]!!.jsonArray
            for (case in cases) {
                val o = case.jsonObject
                val name = o["name"]!!.jsonPrimitive.content
                val input = o["input"]!!.jsonObject
                assertEquals(
                    o["expected"]!!.jsonObject["ok"]!!.jsonPrimitive.boolean,
                    verifyInstitutionCert(
                        institutionCertOf(input["cert"]!!.jsonObject),
                        input["root_pubkey_hex"]!!.jsonPrimitive.content,
                    ),
                    name,
                )
            }
            assertEquals(2, cases.size, "identity.json lost a root-verification case")
        }

        /** Each link verifies in isolation against the key the layer above vouched for. */
        @Test
        fun `each single link verifies against its own issuer key`() {
            val cert = institutionCertOf(v["valid_institution_cert"]!!.jsonObject)
            val credential = studentCredentialOf(v["valid_student_credential"]!!.jsonObject)
            val binding = v["session_pubkey_binding"]!!.jsonObject

            assertTrue(verifyInstitutionCert(cert, v["root_pubkey_hex"]!!.jsonPrimitive.content))
            assertTrue(
                verifyStudentCredential(
                    credential,
                    v["institution_pubkey_hex"]!!.jsonPrimitive.content,
                ),
            )
            // A genuinely root-certified key belonging to ANOTHER institution must not
            // verify this credential.
            assertFalse(
                verifyStudentCredential(
                    credential,
                    v["other_institution_pubkey_hex"]!!.jsonPrimitive.content,
                ),
            )
            assertTrue(
                verifyStudentSessionBinding(
                    StudentSessionBinding(
                        institutionId = binding["institution_id"]!!.jsonPrimitive.content,
                        studentRef = binding["student_ref"]!!.jsonPrimitive.content,
                        sessionPubkey = binding["session_pubkey"]!!.jsonPrimitive.content,
                    ),
                    binding["sig"]!!.jsonPrimitive.content,
                    v["student_pubkey_hex"]!!.jsonPrimitive.content,
                ),
            )
            assertFalse(
                verifyStudentSessionBinding(
                    StudentSessionBinding(
                        institutionId = binding["institution_id"]!!.jsonPrimitive.content,
                        studentRef = binding["student_ref"]!!.jsonPrimitive.content,
                        sessionPubkey = binding["session_pubkey"]!!.jsonPrimitive.content,
                    ),
                    binding["sig"]!!.jsonPrimitive.content,
                    v["other_student_pubkey_hex"]!!.jsonPrimitive.content,
                ),
            )
        }

        /**
         * The ordered 2.1 chain walk, every case driven through [verifyIdentityChain].
         *
         * Both expiry cases must come back ok — an out-of-window credential is
         * REPORTED, never enforced, because silently refusing to record is a worse
         * failure for an integrity tool than recording under a stale credential.
         */
        @Test
        fun `chain cases match log-core`() {
            val cases = v["chain_cases"]!!.jsonArray
            for (case in cases) {
                val o = case.jsonObject
                val name = o["name"]!!.jsonPrimitive.content
                val input = o["input"]!!.jsonObject
                val expected = o["expected"]!!.jsonObject

                val actual = verifyIdentityChain(
                    identity = identity21Of(input["identity"]!!.jsonObject),
                    sessionPubkey = input["session_pubkey"]!!.jsonPrimitive.content,
                    courseCert = null,
                    sessionStartedAt = input["session_started_at"]!!.jsonPrimitive.content,
                    institutionCert = institutionCertOf(input["institution_cert"]!!.jsonObject),
                )

                if (expected["ok"]!!.jsonPrimitive.boolean) {
                    val ok = assertInstanceOf(IdentityChain.InstitutionOk::class.java, actual, name)
                    assertEquals(
                        expected["identity_version"]!!.jsonPrimitive.content,
                        ok.identityVersion,
                        name,
                    )
                    assertEquals(expected["scope"]!!.jsonPrimitive.content, ok.scope, name)
                    assertEquals(
                        expected["institution_id"]!!.jsonPrimitive.content,
                        ok.institutionId,
                        name,
                    )
                    assertEquals(expected["student_ref"]!!.jsonPrimitive.content, ok.studentRef, name)
                    assertEquals(
                        expected["student_pubkey"]!!.jsonPrimitive.content,
                        ok.studentPubkey,
                        name,
                    )
                    assertEquals(
                        expected["institution_pubkey"]!!.jsonPrimitive.content,
                        ok.institutionPubkey,
                        name,
                    )
                    assertWindow(expected["cert_window"]!!.jsonObject, ok.certWindow, "$name cert_window")
                    assertWindow(
                        expected["token_window"]!!.jsonObject,
                        ok.tokenWindow,
                        "$name token_window",
                    )
                } else {
                    val err = assertInstanceOf(IdentityChain.Err::class.java, actual, name)
                    val expectedErr = expected["error"]!!.jsonObject
                    assertEquals(expectedErr["kind"]!!.jsonPrimitive.content, err.kind, name)
                    if (err is IdentityChain.UnsupportedIdentityVersion) {
                        assertEquals(
                            expectedErr["format_version"]!!.jsonPrimitive.content,
                            err.formatVersion,
                            name,
                        )
                    }
                    if (err is IdentityChain.IdentityVersionMismatch) {
                        assertEquals(
                            expectedErr["cert_version"]!!.jsonPrimitive.content,
                            err.certVersion,
                            name,
                        )
                        assertEquals(
                            expectedErr["credential_version"]!!.jsonPrimitive.content,
                            err.credentialVersion,
                            name,
                        )
                    }
                    if (err is IdentityChain.InstitutionMismatch) {
                        assertEquals(
                            expectedErr["credential_institution_id"]!!.jsonPrimitive.content,
                            err.credentialInstitutionId,
                            name,
                        )
                        assertEquals(
                            expectedErr["cert_institution_id"]!!.jsonPrimitive.content,
                            err.certInstitutionId,
                            name,
                        )
                        assertEquals(
                            expectedErr["anchor_institution_id"]!!.jsonPrimitive.content,
                            err.anchorInstitutionId,
                            name,
                        )
                        assertEquals(
                            expectedErr["pubkey_mismatch"]!!.jsonPrimitive.boolean,
                            err.pubkeyMismatch,
                            name,
                        )
                    }
                }
            }

            // Guard against the vector silently losing a mandatory case — a truncated
            // fixture would otherwise turn this whole suite green by iterating nothing.
            val names = cases.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
            assertTrue(
                names.containsAll(
                    listOf(
                        "valid",
                        "cert_not_a_known_version",
                        "future_3_0_is_not_replayable_as_2_1",
                        "mixed_versions_are_refused",
                        "credential_signed_by_uncertified_key",
                        "cross_institution_forgery",
                        "travelling_cert_names_another_institution",
                        "travelling_cert_names_another_key",
                        "session_pubkey_sig_from_another_student",
                        "session_pubkey_not_the_countersigned_one",
                        "expired_credential_is_NOT_fatal",
                        "expired_institution_cert_is_NOT_fatal",
                    ),
                ),
                "identity.json lost a mandatory chain case",
            )
            assertEquals(12, cases.size, "identity.json chain_cases changed size")
        }

        /**
         * THE mandatory negative, stated as the property rather than as a vector
         * lookup.
         *
         * Stanford holds a genuinely ROOT-certified institution key. It mints a
         * credential naming BERKELEY and ships it with its own genuine cert. The cert
         * verifies against root; the credential verifies against exactly the key that
         * cert names; **every signature in the bundle is real**. Only comparing
         * `institution_id` across the credential, the travelling cert and the
         * root-verified anchor refuses it.
         */
        @Test
        fun `cross-institution forgery is refused even though every signature is genuine`() {
            val case = caseNamed("chain_cases", "cross_institution_forgery")
            val input = case["input"]!!.jsonObject
            val identity = identity21Of(input["identity"]!!.jsonObject)
            val anchor = institutionCertOf(input["institution_cert"]!!.jsonObject)
            val credential = identity.enrollment as StudentCredential
            val travellingCert = identity.enrollmentCert as InstitutionCert

            // Every signature really is genuine.
            assertTrue(
                verifyInstitutionCert(anchor, v["root_pubkey_hex"]!!.jsonPrimitive.content),
                "the anchor is genuinely root-certified",
            )
            assertTrue(
                verifyStudentCredential(credential, anchor.institutionPubkey),
                "the credential really was signed by the key the anchor names",
            )
            // And the travelling cert IS the anchor — the forgery is not a swapped cert.
            assertEquals(travellingCert, anchor)

            // The credential nonetheless claims another institution.
            assertNotEquals(credential.institutionId, anchor.institutionId)

            val err = assertInstanceOf(
                IdentityChain.InstitutionMismatch::class.java,
                verifyIdentityChain(
                    identity = identity,
                    sessionPubkey = input["session_pubkey"]!!.jsonPrimitive.content,
                    courseCert = null,
                    sessionStartedAt = input["session_started_at"]!!.jsonPrimitive.content,
                    institutionCert = anchor,
                ),
            )
            assertEquals("berkeley", err.credentialInstitutionId)
            assertEquals("stanford", err.certInstitutionId)
            assertEquals("stanford", err.anchorInstitutionId)
            // The keys agree; only the ids do not. A verifier that compared only keys
            // would accept this.
            assertFalse(err.pubkeyMismatch)
        }

        /**
         * Step 1 reads the institution public key from the ANCHOR, never from the
         * travelling cert. `travelling_cert_names_another_key` is the case that proves
         * it: the shipped cert names a key of the attacker's choosing, and the
         * credential is still checked against the root-verified one.
         */
        @Test
        fun `the credential is verified against the anchor's key, not the travelling cert's`() {
            val case = caseNamed("chain_cases", "travelling_cert_names_another_key")
            val input = case["input"]!!.jsonObject
            val identity = identity21Of(input["identity"]!!.jsonObject)
            val anchor = institutionCertOf(input["institution_cert"]!!.jsonObject)
            val travellingCert = identity.enrollmentCert as InstitutionCert

            assertNotEquals(travellingCert.institutionPubkey, anchor.institutionPubkey)
            val err = assertInstanceOf(
                IdentityChain.InstitutionMismatch::class.java,
                verifyIdentityChain(
                    identity = identity,
                    sessionPubkey = input["session_pubkey"]!!.jsonPrimitive.content,
                    courseCert = null,
                    sessionStartedAt = input["session_started_at"]!!.jsonPrimitive.content,
                    institutionCert = anchor,
                ),
            )
            assertTrue(err.pubkeyMismatch)
        }

        /**
         * ARCHIVED COURSE-SCOPED IDENTITY MUST KEEP VERIFYING, PERMANENTLY.
         *
         * Every bundle recorded before 2.1 carries a 2.0 identity block, and
         * adjudicating a case years after the fact is the entire justification for this
         * system. These run the SAME entry point a 2.1 bundle uses — which is only
         * possible because the router reads the signed `format_version` rather than
         * guessing from which fields exist.
         */
        @Test
        fun `archived 2_0 identity still verifies through the same entry point`() {
            val cases = v["legacy_2_0_cases"]!!.jsonArray
            for (case in cases) {
                val o = case.jsonObject
                val name = o["name"]!!.jsonPrimitive.content
                val input = o["input"]!!.jsonObject
                val expected = o["expected"]!!.jsonObject

                val actual = verifyIdentityChain(
                    identity = identity20Of(input["identity"]!!.jsonObject),
                    sessionPubkey = input["session_pubkey"]!!.jsonPrimitive.content,
                    courseCert = certOf(input["course_cert"]!!.jsonObject),
                    sessionStartedAt = input["session_started_at"]!!.jsonPrimitive.content,
                    // Deliberately supplied as well: a 2.0 block must ignore it entirely.
                    institutionCert = institutionCertOf(
                        v["valid_institution_cert"]!!.jsonObject,
                    ),
                )

                if (expected["ok"]!!.jsonPrimitive.boolean) {
                    val ok = assertInstanceOf(IdentityChain.CourseOk::class.java, actual, name)
                    assertEquals(
                        expected["identity_version"]!!.jsonPrimitive.content,
                        ok.identityVersion,
                        name,
                    )
                    assertEquals(expected["scope"]!!.jsonPrimitive.content, ok.scope, name)
                    assertEquals(expected["course_id"]!!.jsonPrimitive.content, ok.courseId, name)
                    assertEquals(expected["student_ref"]!!.jsonPrimitive.content, ok.studentRef, name)
                    assertEquals(
                        expected["student_pubkey"]!!.jsonPrimitive.content,
                        ok.studentPubkey,
                        name,
                    )
                    assertEquals(
                        expected["enrollment_pubkey"]!!.jsonPrimitive.content,
                        ok.enrollmentPubkey,
                        name,
                    )
                    assertWindow(expected["cert_window"]!!.jsonObject, ok.certWindow, "$name cert_window")
                    assertWindow(
                        expected["token_window"]!!.jsonObject,
                        ok.tokenWindow,
                        "$name token_window",
                    )
                } else {
                    val err = assertInstanceOf(IdentityChain.Err::class.java, actual, name)
                    val expectedErr = expected["error"]!!.jsonObject
                    assertEquals(expectedErr["kind"]!!.jsonPrimitive.content, err.kind, name)
                    // The 2.0 mandatory forgery check is untouched by the 2.1 addition.
                    if (err is IdentityChain.CourseIdMismatch) {
                        assertEquals(
                            expectedErr["token_course_id"]!!.jsonPrimitive.content,
                            err.tokenCourseId,
                            name,
                        )
                        assertEquals(
                            expectedErr["cert_course_id"]!!.jsonPrimitive.content,
                            err.certCourseId,
                            name,
                        )
                        assertEquals(
                            expectedErr["course_cert_course_id"]!!.jsonPrimitive.content,
                            err.courseCertCourseId,
                            name,
                        )
                    }
                }
            }
            val names = cases.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
            assertTrue(
                names.containsAll(
                    listOf(
                        "archived_course_identity_still_verifies",
                        "archived_cross_course_forgery_still_refused",
                    ),
                ),
                "identity.json lost a mandatory legacy case",
            )
            assertEquals(2, cases.size, "identity.json legacy_2_0_cases changed size")
        }

        /**
         * Routing is decided by the SIGNED version, never by which fields are present.
         *
         * Institution artifacts carry `institution_id`; enrollment artifacts carry
         * `course_id`. Flipping ONLY the declared versions — leaving every field
         * exactly where it was — must change which walk runs. A field-presence router
         * would return the same answer for all three of these.
         */
        @Test
        fun `routing follows the signed version, not which fields are present`() {
            val input = caseNamed("chain_cases", "valid")["input"]!!.jsonObject
            val identity = identity21Of(input["identity"]!!.jsonObject)
            val anchor = institutionCertOf(input["institution_cert"]!!.jsonObject)
            val sessionPubkey = input["session_pubkey"]!!.jsonPrimitive.content
            val startedAt = input["session_started_at"]!!.jsonPrimitive.content
            val cert = identity.enrollmentCert as InstitutionCert
            val credential = identity.enrollment as StudentCredential

            // As-is: the 2.1 walk runs and succeeds.
            assertInstanceOf(
                IdentityChain.InstitutionOk::class.java,
                verifyIdentityChain(identity, sessionPubkey, null, startedAt, anchor),
            )

            // Cert alone downgraded to 2.0: the pair no longer agrees, so it is refused
            // as a MISMATCH rather than quietly read under either version's rules.
            assertInstanceOf(
                IdentityChain.IdentityVersionMismatch::class.java,
                verifyIdentityChain(
                    identity.copy(enrollmentCert = cert.copy(formatVersion = "2.0")),
                    sessionPubkey,
                    null,
                    startedAt,
                    anchor,
                ),
            )

            // BOTH relabelled 2.0, fields untouched: the 2.0 walk now runs — and rejects
            // them on shape, because institution artifacts carry no `course_id`. A
            // presence-based router would still have run the 2.1 walk and returned ok.
            val courseCert = certOf(
                caseNamed("legacy_2_0_cases", "archived_course_identity_still_verifies")["input"]!!
                    .jsonObject["course_cert"]!!.jsonObject,
            )
            assertInstanceOf(
                IdentityChain.InvalidCertShape::class.java,
                verifyIdentityChain(
                    SessionIdentity(
                        enrollment = credential.copy(formatVersion = "2.0"),
                        enrollmentCert = cert.copy(formatVersion = "2.0"),
                        sessionPubkeySig = identity.sessionPubkeySig,
                    ),
                    sessionPubkey,
                    courseCert,
                    startedAt,
                    anchor,
                ),
            )
        }

        /**
         * The anchor is a REQUIRED parameter of each walk, and the walk says which one
         * it wanted. Reported as a value because which anchor is needed is only known
         * after reading the bundle.
         */
        @Test
        fun `a missing trust anchor is reported, not assumed`() {
            val input = caseNamed("chain_cases", "valid")["input"]!!.jsonObject
            val identity = identity21Of(input["identity"]!!.jsonObject)
            val sessionPubkey = input["session_pubkey"]!!.jsonPrimitive.content
            val startedAt = input["session_started_at"]!!.jsonPrimitive.content

            assertEquals(
                "institution_cert",
                assertInstanceOf(
                    IdentityChain.MissingTrustAnchor::class.java,
                    verifyIdentityChain(identity, sessionPubkey, null, startedAt, null),
                ).required,
            )

            val legacy = identity20Of(
                caseNamed("legacy_2_0_cases", "archived_course_identity_still_verifies")["input"]!!
                    .jsonObject["identity"]!!.jsonObject,
            )
            assertEquals(
                "course_cert",
                assertInstanceOf(
                    IdentityChain.MissingTrustAnchor::class.java,
                    verifyIdentityChain(legacy, sessionPubkey, null, startedAt, null),
                ).required,
            )
        }

        /** Shape is validated before any signature work, for both 2.1 artifacts. */
        @Test
        fun `a malformed 2_1 artifact is rejected before signature verification`() {
            val input = caseNamed("chain_cases", "valid")["input"]!!.jsonObject
            val identity = identity21Of(input["identity"]!!.jsonObject)
            val anchor = institutionCertOf(input["institution_cert"]!!.jsonObject)
            val sessionPubkey = input["session_pubkey"]!!.jsonPrimitive.content
            val startedAt = input["session_started_at"]!!.jsonPrimitive.content
            val cert = identity.enrollmentCert as InstitutionCert
            val credential = identity.enrollment as StudentCredential

            // JCS omits an absent key, so an artifact missing a required field would
            // sign and verify cleanly while carrying nothing there.
            assertInstanceOf(
                IdentityChain.InvalidCertShape::class.java,
                verifyIdentityChain(
                    identity.copy(enrollmentCert = cert.copy(institutionId = "")),
                    sessionPubkey,
                    null,
                    startedAt,
                    anchor,
                ),
            )
            assertInstanceOf(
                IdentityChain.InvalidTokenShape::class.java,
                verifyIdentityChain(
                    identity.copy(enrollment = credential.copy(studentRef = "")),
                    sessionPubkey,
                    null,
                    startedAt,
                    anchor,
                ),
            )
            // A window whose upper bound precedes its lower bound never binds, which
            // would silently remove the only offline control this scheme has.
            assertInstanceOf(
                IdentityChain.InvalidCertShape::class.java,
                verifyIdentityChain(
                    identity.copy(enrollmentCert = cert.copy(validUntil = "2020-01-01")),
                    sessionPubkey,
                    null,
                    startedAt,
                    anchor,
                ),
            )
            // A non-hex session pubkey is reported distinctly from a bad signature.
            assertInstanceOf(
                IdentityChain.InvalidSessionPubkey::class.java,
                verifyIdentityChain(identity, "not-hex", null, startedAt, anchor),
            )
        }

        /**
         * The credential window, judged against the SESSION start — never wall-clock
         * now, so an archived bundle still reads as in-window years later. A date-only
         * `expires_at` covers its whole day and ends at the next midnight, the same
         * asymmetric rule `course_cert.valid_until` uses.
         */
        @Test
        fun `credential window cases match log-core`() {
            val cases = v["credential_window_cases"]!!.jsonArray
            for (case in cases) {
                val o = case.jsonObject
                val name = o["name"]!!.jsonPrimitive.content
                val input = o["input"]!!.jsonObject
                val credential = studentCredentialOf(v["valid_student_credential"]!!.jsonObject)
                    .copy(
                        issuedAt = input["issued_at"]!!.jsonPrimitive.content,
                        expiresAt = input["expires_at"]!!.jsonPrimitive.content,
                    )
                assertWindow(
                    o["expected"]!!.jsonObject,
                    checkCredentialWindow(credential, input["at"]!!.jsonPrimitive.content),
                    name,
                )
            }
            assertEquals(5, cases.size, "identity.json credential_window_cases changed size")
        }

        /**
         * The CURRENT student key derivation: one master secret, ONE key, forever,
         * across every course.
         *
         * The `info` is FIXED — nothing is concatenated onto it — which is the whole
         * difference from the v1 per-course derivation in `student-keys.json`. That
         * one takes a `course_id`, and encoding it as `US_ASCII` rather than UTF-8
         * silently derives a DIFFERENT key with no error: it bit this repo once, and
         * the `berkeley-café` case over there stays live for exactly that reason. Both
         * derivations must remain implemented and must NOT collide.
         */
        @Test
        fun `global student key derivation matches log-core`() {
            val d = v["student_key_derivation"]!!.jsonObject
            val params = d["hkdf_params"]!!.jsonObject
            assertEquals(params["info_utf8"]!!.jsonPrimitive.content, STUDENT_KEY_HKDF_INFO)
            assertEquals(
                params["salt_utf8"]!!.jsonPrimitive.content,
                STUDENT_KEY_HKDF_SALT_UTF8,
            )
            assertEquals(
                params["salt_hex"]!!.jsonPrimitive.content,
                Ed25519.bytesToHex(studentKeyHkdfSalt()),
            )
            assertEquals(params["output_length_bytes"]!!.jsonPrimitive.int, STUDENT_KEY_SEED_BYTES)
            assertEquals(
                d["master_secret_bytes"]!!.jsonPrimitive.int,
                STUDENT_MASTER_SECRET_BYTES,
            )

            val cases = d["derivation_cases"]!!.jsonArray
            for (case in cases) {
                val o = case.jsonObject
                val master = Ed25519.hexToBytes(
                    o["input"]!!.jsonObject["master_secret_hex"]!!.jsonPrimitive.content,
                )
                val expected = o["expected"]!!.jsonObject
                assertEquals(
                    expected["seed_hex"]!!.jsonPrimitive.content,
                    Ed25519.bytesToHex(deriveStudentKeySeed(master)),
                )
                assertEquals(
                    expected["pubkey_hex"]!!.jsonPrimitive.content,
                    deriveStudentKeypair(master).publicKeyHex,
                )
            }
            assertEquals(2, cases.size, "identity.json derivation_cases changed size")

            // The two derivations MUST NOT collide: that is what keeps a student's
            // archived per-course keys separate from their new global one.
            val differs = d["differs_from_legacy_course_derivation"]!!.jsonObject
            val master = Ed25519.hexToBytes(differs["master_secret_hex"]!!.jsonPrimitive.content)
            val courseId = differs["legacy_course_id"]!!.jsonPrimitive.content
            assertEquals(
                differs["global_pubkey_hex"]!!.jsonPrimitive.content,
                deriveStudentKeypair(master).publicKeyHex,
            )
            assertEquals(
                differs["legacy_pubkey_hex"]!!.jsonPrimitive.content,
                deriveCourseKeypair(master, courseId).publicKeyHex,
            )
            assertNotEquals(
                deriveStudentKeypair(master).publicKeyHex,
                deriveCourseKeypair(master, courseId).publicKeyHex,
            )
        }

        /** Both 2.1 artifacts round-trip through their transport form. */
        @Test
        fun `institution artifacts round-trip through transport`() {
            val cert = institutionCertOf(v["valid_institution_cert"]!!.jsonObject)
            val credential = studentCredentialOf(v["valid_student_credential"]!!.jsonObject)
            assertEquals(
                cert,
                assertInstanceOf(
                    EnrollmentParse.Ok::class.java,
                    parseInstitutionCert(cert.toJsonObject()),
                ).value,
            )
            assertEquals(
                credential,
                assertInstanceOf(
                    EnrollmentParse.Ok::class.java,
                    parseStudentCredential(credential.toJsonObject()),
                ).value,
            )
            // And through the whole identity block, which must serialize either family
            // under the same two wire keys.
            val block = SessionIdentity(
                enrollment = credential,
                enrollmentCert = cert,
                sessionPubkeySig = v["session_pubkey_binding"]!!.jsonObject["sig"]!!
                    .jsonPrimitive.content,
            ).toJsonObject()
            assertEquals(cert.toJsonObject(), block["enrollment_cert"])
            assertEquals(credential.toJsonObject(), block["enrollment"])
        }
    }

    /** `manifest-v2.json` — the two signature scopes and the ordered chain walk. */
    @Nested
    inner class ManifestV2Vectors {
        private val v by lazy { vector("manifest-v2.json") }

        /**
         * MANDATORY. 1.x is identified by the ABSENCE of `format_version`, so a
         * missing field must default to "1.0" and parse — never reject. Rejecting
         * it would break every archived submission, which is the adjudication case
         * the whole program exists to serve.
         */
        @Test
        fun `legacy manifest with no format_version defaults to 1_0 and still verifies`() {
            val block = v["legacy_no_format_version"]!!.jsonObject
            val parsed = assertInstanceOf(
                ManifestParse.Ok::class.java,
                parseManifest(block["manifest_json"]!!.jsonPrimitive.content),
            ).manifest
            val expected = block["expected"]!!.jsonObject
            assertEquals(expected["format_version"]!!.jsonPrimitive.content, parsed.formatVersion)
            assertEquals(MANIFEST_FORMAT_VERSION_LEGACY, manifestFormatVersion(parsed))
            assertEquals(
                block["canonical_json"]!!.jsonPrimitive.content,
                String(buildSignedPayload(parsed), Charsets.UTF_8),
            )
            assertEquals(
                expected["sig_verifies"]!!.jsonPrimitive.boolean,
                verifyManifest(parsed, block["course_pubkey_hex"]!!.jsonPrimitive.content),
            )
        }

        /**
         * An explicit `"1.0"` canonicalizes to the same four legacy fields —
         * `format_version` itself is NOT in the 1.x signed payload, so the archived
         * signature still verifies.
         */
        @Test
        fun `explicit 1_0 canonicalizes to the same legacy payload`() {
            val legacy = v["legacy_no_format_version"]!!.jsonObject
            val block = v["legacy_explicit_1_0"]!!.jsonObject
            val parsed = assertInstanceOf(
                ManifestParse.Ok::class.java,
                parseManifestValue(block["manifest"]!!.jsonObject),
            ).manifest
            assertEquals(MANIFEST_FORMAT_VERSION_LEGACY, parsed.formatVersion)
            assertEquals(
                legacy["canonical_json"]!!.jsonPrimitive.content,
                String(buildSignedPayload(parsed), Charsets.UTF_8),
            )
            assertEquals(block["expected"]!!.jsonObject["sig"]!!.jsonPrimitive.content, parsed.sig)
            assertTrue(verifyManifest(parsed, legacy["course_pubkey_hex"]!!.jsonPrimitive.content))
        }

        /** The 2.0 signed payload: `sig` AND `course_cert` excluded, policy verbatim. */
        @Test
        fun `2_0 signed payload excludes sig and course_cert`() {
            val block = v["valid_2_0"]!!.jsonObject
            val parsed = assertInstanceOf(
                ManifestParse.Ok::class.java,
                parseManifestValue(block["manifest"]!!.jsonObject),
            ).manifest
            assertEquals(
                block["canonical_json"]!!.jsonPrimitive.content,
                String(buildSignedPayload(parsed), Charsets.UTF_8),
            )
            assertTrue(verifyManifest(parsed, v["course_pubkey_hex"]!!.jsonPrimitive.content))
            assertEquals(MANIFEST_FORMAT_VERSION_2, parsed.formatVersion)
            assertEquals(ManifestCollaboration.SOLO, parsed.collaboration)
            assertEquals(ManifestSubmission.BUNDLE, parsed.submission)
            assertEquals(ManifestScope.DIRECTORY, parsed.scope)
            assertEquals(DEFAULT_CAPTURE_POLICY, resolveCapturePolicy(parsed.policy))
        }

        /**
         * MANDATORY. Unknown top-level keys are ignored. Safe precisely because
         * canonicalization names its fields, so an unknown key can never move the
         * signed bytes — the signature still verifies with the extras present.
         */
        @Test
        fun `unknown top-level keys are ignored`() {
            val block = v["unknown_keys_ignored"]!!.jsonObject
            val parsed = assertInstanceOf(
                ManifestParse.Ok::class.java,
                parseManifest(block["manifest_json"]!!.jsonPrimitive.content),
            ).manifest
            val expectedChain = block["expected"]!!.jsonObject["chain"]!!.jsonObject
            val chain = assertInstanceOf(
                ManifestChain.Ok::class.java,
                verifyManifestChain(parsed, v["root_pubkey_hex"]!!.jsonPrimitive.content),
            )
            assertEquals(expectedChain["course_id"]!!.jsonPrimitive.content, chain.courseId)
            assertEquals(
                expectedChain["window"]!!.jsonObject["in_window"]!!.jsonPrimitive.boolean,
                chain.window.inWindow,
            )
        }

        /**
         * The ordered chain walk. Two cases carry the security argument:
         *
         *  - `course_id_mismatch` — both signatures are genuine; only comparing the
         *    manifest's `course_id` to the cert's catches 61B's key forging a 61C
         *    manifest.
         *  - `downgrade_1x_with_stapled_cert` — needs no private key at all: a
         *    genuinely-signed 1.x manifest plus the course's real certificate plus a
         *    matching `course_id` plus an INVENTED capture-off policy. Every
         *    signature verifies. Only step 0's `format_version == "2.0"` gate stops
         *    it, and without that gate the student gets the off switch.
         */
        @Test
        fun `chain cases match log-core`() {
            val cases = v["chain_cases"]!!.jsonArray
            for (case in cases) {
                val o = case.jsonObject
                val name = o["name"]!!.jsonPrimitive.content
                val input = o["input"]!!.jsonObject
                val expected = o["expected"]!!.jsonObject
                val actual = verifyManifestChain(
                    lenientManifest(input["manifest"]!!.jsonObject),
                    input["root_pubkey_hex"]!!.jsonPrimitive.content,
                )

                if (expected["ok"]!!.jsonPrimitive.boolean) {
                    val ok = assertInstanceOf(ManifestChain.Ok::class.java, actual, name)
                    assertEquals(expected["course_id"]!!.jsonPrimitive.content, ok.courseId, name)
                    val window = expected["window"]!!.jsonObject
                    assertEquals(window["in_window"]!!.jsonPrimitive.boolean, ok.window.inWindow, name)
                    val reason = window["reason"]?.jsonPrimitive?.content
                    if (reason != null) {
                        assertEquals(
                            reason,
                            assertInstanceOf(CertWindowStatus.OutOfWindow::class.java, ok.window, name).reason.wire,
                            name,
                        )
                    }
                } else {
                    val err = assertInstanceOf(ManifestChain.Err::class.java, actual, name)
                    assertEquals(expected["kind"]!!.jsonPrimitive.content, err.kind, name)
                    if (err is ManifestChain.NotManifest20) {
                        assertEquals(
                            expected["format_version"]!!.jsonPrimitive.content,
                            err.formatVersion,
                            name,
                        )
                    }
                    if (err is ManifestChain.CourseIdMismatch) {
                        assertEquals(
                            expected["manifest_course_id"]!!.jsonPrimitive.content,
                            err.manifestCourseId,
                            name,
                        )
                        assertEquals(
                            expected["cert_course_id"]!!.jsonPrimitive.content,
                            err.certCourseId,
                            name,
                        )
                    }
                }
            }
            // Guard against the vector silently losing a mandatory case.
            val names = cases.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
            assertTrue(
                names.containsAll(
                    listOf(
                        "valid",
                        "bad_root_sig",
                        "wrong_course_key",
                        "tampered_payload",
                        "course_id_mismatch",
                        "issued_at_after_valid_until",
                        "not_manifest_2_0",
                        "missing_course_cert",
                        "downgrade_1x_with_stapled_cert",
                    ),
                ),
                "manifest-v2.json lost a mandatory chain case",
            )
        }

        /**
         * `session.start` 2.0 carries the FULL manifest into the bundle (program spec §5),
         * so the serializer must lose nothing a verifier needs. Round-tripping the pinned
         * vector and re-checking BOTH the canonical signed bytes and the whole chain is the
         * assertion that matters: if transport dropped or reordered a signed field, the
         * analyzer would report a genuine bundle as forged.
         */
        @Test
        fun `the full manifest survives a round trip through session start transport`() {
            val block = v["valid_2_0"]!!.jsonObject
            val original = assertInstanceOf(
                ManifestParse.Ok::class.java,
                parseManifestValue(block["manifest"]!!.jsonObject),
            ).manifest

            val reparsed = assertInstanceOf(
                ManifestParse.Ok::class.java,
                parseManifestValue(original.toJsonObject()),
            ).manifest

            assertEquals(original, reparsed)
            assertEquals(
                block["canonical_json"]!!.jsonPrimitive.content,
                String(buildSignedPayload(reparsed), Charsets.UTF_8),
            )
            assertInstanceOf(
                ManifestChain.Ok::class.java,
                verifyManifestChain(reparsed, v["root_pubkey_hex"]!!.jsonPrimitive.content),
            )
        }

        /** A 1.x manifest round-trips too, and gains no 2.0 keys on the way. */
        @Test
        fun `a legacy manifest survives the same round trip`() {
            val block = v["legacy_no_format_version"]!!.jsonObject
            val original = assertInstanceOf(
                ManifestParse.Ok::class.java,
                parseManifest(block["manifest_json"]!!.jsonPrimitive.content),
            ).manifest

            val emitted = original.toJsonObject()
            for (key in listOf("course_id", "collaboration", "submission", "scope", "policy", "course_cert")) {
                assertFalse(key in emitted, "a 1.x manifest must not gain $key")
            }
            val reparsed = assertInstanceOf(
                ManifestParse.Ok::class.java,
                parseManifestValue(emitted),
            ).manifest
            assertEquals(original, reparsed)
            assertEquals(
                block["canonical_json"]!!.jsonPrimitive.content,
                String(buildSignedPayload(reparsed), Charsets.UTF_8),
            )
            assertTrue(verifyManifest(reparsed, block["course_pubkey_hex"]!!.jsonPrimitive.content))
        }

        /**
         * The downgrade case again, stated as the property rather than as a vector
         * lookup: the stapled policy really would disable capture, and the manifest
         * really is signed by the course key — so nothing but step 0 rejects it.
         */
        @Test
        fun `downgrade attempt would have disabled capture had step 0 not rejected it`() {
            val case = v["chain_cases"]!!.jsonArray
                .first { it.jsonObject["name"]!!.jsonPrimitive.content == "downgrade_1x_with_stapled_cert" }
                .jsonObject["input"]!!.jsonObject
            val raw = case["manifest"]!!.jsonObject
            val manifest = lenientManifest(raw)

            // The invented policy turns every optional signal off.
            val stapled = resolveCapturePolicy(manifest.policy)
            assertFalse(stapled.selectionChange)
            assertFalse(stapled.terminal)
            assertFalse(stapled.focusChange)

            // Steps 1-3 would all have passed: the cert is genuinely root-signed,
            // the (1.x) payload is genuinely course-signed, and course_id matches.
            val cert = certOf(raw["course_cert"]!!.jsonObject)
            assertTrue(verifyCourseCert(cert, v["root_pubkey_hex"]!!.jsonPrimitive.content))
            assertTrue(verifyManifest(manifest, cert.coursePubkey))
            assertEquals(cert.courseId, manifest.courseId)

            // Only step 0 stops it.
            val err = assertInstanceOf(
                ManifestChain.NotManifest20::class.java,
                verifyManifestChain(manifest, v["root_pubkey_hex"]!!.jsonPrimitive.content),
            )
            assertEquals(MANIFEST_FORMAT_VERSION_LEGACY, err.formatVersion)
        }
    }

    /**
     * `rolling-manifest.json` — the S3 ROLLING SEAL's write side.
     *
     * Every case here is built THROUGH the production write path
     * ([buildRollingSessionManifest] + [signBundleManifest] + [rollingManifestFilenames]) and
     * compared against the golden bytes. Nothing decodes the fixture's manifest object and
     * re-emits it: that would test kotlinx-serialization's round trip, not the recorder, and
     * would stay green through exactly the mutations this suite exists to catch.
     *
     * The canonical bytes ARE the signed message and three recorder implementations pin them,
     * so a red test here means this recorder's seal is wrong — never that a vector should move.
     */
    @Nested
    inner class RollingManifestVectors {
        private val v by lazy { vector("rolling-manifest.json") }

        /** The session key the fixture signs with: log-core's `seed(6)`. */
        private val sessionPriv = ByteArray(32) { 6 }

        private val sessionId get() = v["session_id"]!!.jsonPrimitive.content

        /**
         * The writer's INPUTS, read off the fixture — per-file path/status/sha, not shape.
         * Guarded on count so a truncated fixture cannot quietly shrink the payload under test.
         */
        private fun submissionFileInputs(): List<SubmissionFileEntry> {
            val files = v["manifest"]!!.jsonObject["submission_files"]!!.jsonArray
            assertEquals(2, files.size, "fixture must carry both a present and a missing file")
            return files.map { e ->
                val o = e.jsonObject
                SubmissionFileEntry(
                    path = o["path"]!!.jsonPrimitive.content,
                    status = o["status"]!!.jsonPrimitive.content,
                    sha256 = (o["sha256"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
                )
            }
        }

        /** Build one case through the production builder, from the fixture's field values. */
        private fun buildFromVector(isFinal: Boolean): BundleManifest {
            val session = v["manifest"]!!.jsonObject["sessions"]!!.jsonArray.single().jsonObject
            val m = v["manifest"]!!.jsonObject
            return buildRollingSessionManifest(
                sessionId = session["session_id"]!!.jsonPrimitive.content,
                prevSessionId = (session["prev_session_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
                slogSha256 = session["slog_sha256"]!!.jsonPrimitive.content,
                metaSha256 = session["meta_sha256"]!!.jsonPrimitive.content,
                assignmentId = m["assignment_id"]!!.jsonPrimitive.content,
                semester = m["semester"]!!.jsonPrimitive.content,
                extensionHash = m["extension_hash"]!!.jsonPrimitive.content,
                submissionFiles = submissionFileInputs(),
                isFinal = isFinal,
            )
        }

        @Test
        fun `the fixture's session key is the one this suite signs with`() {
            assertEquals(
                v["session_pubkey_hex"]!!.jsonPrimitive.content,
                Ed25519.bytesToHex(Ed25519.publicKeyOf(sessionPriv)),
            )
        }

        @Test
        fun `rolling manifest is emitted at format_version 1_2`() {
            assertEquals(v["format_version"]!!.jsonPrimitive.content, ROLLING_MANIFEST_FORMAT_VERSION)
            assertEquals(ROLLING_MANIFEST_FORMAT_VERSION, buildFromVector(isFinal = false).formatVersion)
        }

        @Test
        fun `filenames match log-core and round-trip through the parser`() {
            val expected = v["filenames"]!!.jsonObject
            val names = rollingManifestFilenames(sessionId)
            assertEquals(expected["json"]!!.jsonPrimitive.content, names.json)
            assertEquals(expected["sig"]!!.jsonPrimitive.content, names.sig)
            assertEquals(
                RollingManifestFile(sessionId, RollingManifestPart.JSON),
                parseRollingManifestFilename(names.json),
            )
            assertEquals(
                RollingManifestFile(sessionId, RollingManifestPart.SIG),
                parseRollingManifestFilename(names.sig),
            )
        }

        /**
         * `manifest.json` / `manifest.sig` are the CLASSIC seal. If either could be read as a
         * rolling seal, the two shapes would collide in one directory — so the parser must
         * refuse them, and by symmetry the writer can never be asked to produce them.
         */
        @Test
        fun `names that are not rolling seals are refused`() {
            val names = v["not_rolling_filenames"]!!.jsonArray
            assertEquals(5, names.size, "fixture must carry all five non-rolling names")
            for (n in names) {
                val name = n.jsonPrimitive.content
                assertNull(parseRollingManifestFilename(name), "must not parse as a rolling seal: $name")
            }
        }

        @Test
        fun `canonical json and signature match log-core`() {
            val signed = signBundleManifest(buildFromVector(isFinal = false), sessionPriv)
            assertEquals(v["canonical_json"]!!.jsonPrimitive.content, signed.canonicalJson)
            assertEquals(v["signature_hex"]!!.jsonPrimitive.content, signed.signatureHex)
        }

        /**
         * The `final` marker, both ways round.
         *
         * `non_final` is the shape EVERY roll but the last one takes: the key is ABSENT, not
         * `false`. Writing `"final":false` would change the canonical bytes of every seal a
         * student's repo has ever carried and break byte-compatibility with the other two
         * recorders, so the absence is asserted explicitly rather than left implied.
         */
        @Test
        fun `final marker vectors match log-core`() {
            val marker = v["final_marker"]!!.jsonObject
            val cases = listOf("non_final", "final")
            for (name in cases) {
                val case = marker[name]!!.jsonObject
                val isFinal = case["is_final"]!!.jsonPrimitive.boolean
                assertEquals(name == "final", isFinal, "fixture case $name")
                val signed = signBundleManifest(buildFromVector(isFinal), sessionPriv)
                assertEquals(case["canonical_json"]!!.jsonPrimitive.content, signed.canonicalJson, name)
                assertEquals(case["signature_hex"]!!.jsonPrimitive.content, signed.signatureHex, name)
                if (isFinal) {
                    assertTrue(signed.canonicalJson.contains("\"final\":true"), "final seal must say so")
                } else {
                    assertFalse(signed.canonicalJson.contains("final"), "a non-final seal must OMIT the key")
                }
            }
            // Guard against a truncated fixture silently emptying the loop above.
            assertEquals(cases.size, cases.count { marker[it] != null })
        }

        /**
         * The DOWNGRADE attempt: strip `final`, keep the final seal's signature. `final` is
         * inside the signed payload, so the canonical bytes change and the signature cannot
         * verify — which is what stops a student demoting a whole-file commitment back to a
         * prefix one without the session's private key.
         */
        @Test
        fun `stripping final invalidates the final signature`() {
            val d = v["final_marker"]!!.jsonObject["downgrade_rejects"]!!.jsonObject
            assertFalse(d["verifies"]!!.jsonPrimitive.boolean)
            // Reproduce the downgrade through the writer: the non-final bytes, the final sig.
            val nonFinal = signBundleManifest(buildFromVector(isFinal = false), sessionPriv)
            val finalSig = v["final_marker"]!!.jsonObject["final"]!!.jsonObject["signature_hex"]!!.jsonPrimitive.content
            assertEquals(d["canonical_json"]!!.jsonPrimitive.content, nonFinal.canonicalJson)
            assertEquals(d["signature_hex"]!!.jsonPrimitive.content, finalSig)
            assertFalse(
                Ed25519.verify(
                    Ed25519.hexToBytes(finalSig),
                    nonFinal.canonicalJson.toByteArray(Charsets.UTF_8),
                    Ed25519.publicKeyOf(sessionPriv),
                ),
            )
        }

        /**
         * The one-session rule, enforced by CONSTRUCTION: the builder takes one session's
         * four fields, not a list, so no caller can put two sessions (or zero) into a rolling
         * manifest file. A rolling manifest that covered several sessions would need several
         * private keys to be verifiable, and would reintroduce the shared-file merge conflict
         * per-session filenames exist to remove.
         */
        @Test
        fun `a rolling manifest covers exactly one non-null session`() {
            val m = buildFromVector(isFinal = false)
            assertEquals(1, m.sessions.size)
            assertEquals(sessionId, m.sessions.single().sessionId)
        }
    }

    private fun assertWindow(expected: JsonObject, actual: CertWindowStatus, label: String) {
        assertEquals(expected["in_window"]!!.jsonPrimitive.boolean, actual.inWindow, label)
        val reason = expected["reason"]?.jsonPrimitive?.content
        if (reason == null) {
            assertInstanceOf(CertWindowStatus.InWindow::class.java, actual, label)
        } else {
            assertEquals(
                reason,
                assertInstanceOf(CertWindowStatus.OutOfWindow::class.java, actual, label).reason.wire,
                label,
            )
        }
    }
}
