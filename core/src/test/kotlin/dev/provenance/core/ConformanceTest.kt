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
            assertEquals(
                v["policy_gated_event_kinds"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content },
                POLICY_GATED_EVENT_KINDS,
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
            assertFalse(POLICY_GATED_EVENT_KINDS.values.contains("doc_open_close"))
            assertFalse(POLICY_GATED_EVENT_KINDS.values.contains("inline_content"))
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
                    ),
                ),
                "git-event.json lost a mandatory case",
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
            val allowed = setOf("operation", "commit_sha", "sha", "parents", "branch")
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
                    val ok = assertInstanceOf(IdentityChain.Ok::class.java, actual, name)
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
                    if (err is IdentityChain.NotEnrollment20) {
                        assertEquals(expectedErr["artifact"]!!.jsonPrimitive.content, err.artifact, name)
                        assertEquals(
                            expectedErr["format_version"]!!.jsonPrimitive.content,
                            err.formatVersion,
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
            assertTrue(verifyEnrollmentCert(identity.enrollmentCert, courseCert.coursePubkey))
            assertTrue(
                verifyEnrollmentToken(identity.enrollment, identity.enrollmentCert.enrollmentPubkey),
            )
            // The cert and token agree with each other — only the course_cert disagrees.
            assertEquals(identity.enrollment.courseId, identity.enrollmentCert.courseId)
            assertNotEquals(identity.enrollmentCert.courseId, courseCert.courseId)

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
                    identity.copy(enrollmentCert = identity.enrollmentCert.copy(courseId = "")),
                    sessionPubkey,
                    courseCert,
                    startedAt,
                ),
            )
            assertInstanceOf(
                IdentityChain.InvalidTokenShape::class.java,
                verifyIdentityChain(
                    identity.copy(enrollment = identity.enrollment.copy(studentRef = "")),
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
                        enrollmentCert = identity.enrollmentCert.copy(validUntil = "2020-01-01"),
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
