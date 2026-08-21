#!/usr/bin/env node
// THE CONSUMER HALF OF THE CROSS-IMPLEMENTATION GATE.
//
// Loads an archive produced by this repo's JetBrains recorder into the REAL
// Provenance monorepo's `analysis-core` — the unmodified `loadBundle` +
// `runValidation` the analyzer and the server both run — and requires:
//
//   * loadBundle succeeds, and
//   * every one of the eight PRD §5.4 checks reports 'pass'
//     (equivalently: report.overall === 'pass', since `overall` is 'warn' as
//     soon as any check is skipped and 'fail' as soon as any fails).
//
// Nothing here reimplements a check, reads a vector, or compares against a
// recorded expectation. The point of this file is that a SECOND implementation
// of the format is judged by the FIRST one's reader, which is the only test
// class that can catch a divergence both sides agree on.
//
// Usage: node scripts/e2e/verify-bundle-with-analyzer.mjs <archive.zip> [--shape classic|rolling] [--root-pubkey <hex>]
// Env:   PROVENANCE_MONOREPO (default: ../provenance, resolved from this repo)
//
// Exit codes: 0 pass · 2 could not run (bad usage / unimportable analysis-core) · 3 gate failed.

import fs from 'node:fs';
import path from 'node:path';
import url from 'node:url';

const args = process.argv.slice(2);
const zipPath = args.find((a) => !a.startsWith('--'));
const shapeIdx = args.indexOf('--shape');
const shape = shapeIdx === -1 ? undefined : args[shapeIdx + 1];
const rootIdx = args.indexOf('--root-pubkey');
const rootPubkeyHex = rootIdx === -1 ? undefined : args[rootIdx + 1];

if (!zipPath) {
  console.error(
    'usage: verify-bundle-with-analyzer.mjs <archive.zip> [--shape classic|rolling] [--root-pubkey <hex>]',
  );
  process.exit(2);
}

const here = path.dirname(url.fileURLToPath(import.meta.url));
const repoRoot = path.resolve(here, '../..');
const monorepo = process.env.PROVENANCE_MONOREPO || path.resolve(repoRoot, '../provenance');
const analysisCoreEntry = path.join(monorepo, 'packages/analysis-core/dist/index.js');

let loadBundle;
let runValidation;
// The REAL peer-witnessing reader. Imported from the same build as the validator so the
// witnesses this recorder writes are judged by the consumer that will actually read them —
// the one thing this repository's own suite structurally cannot do.
let reconcileWitnesses;
let isWitnessAlterationEvidence;
try {
  const mod = await import(url.pathToFileURL(analysisCoreEntry).href);
  ({ loadBundle, runValidation, reconcileWitnesses, isWitnessAlterationEvidence } = mod);
} catch (e) {
  console.error(`Failed to import analysis-core from ${analysisCoreEntry}:`);
  console.error(e);
  process.exit(2);
}

if (typeof loadBundle !== 'function' || typeof runValidation !== 'function') {
  console.error('analysis-core did not export loadBundle/runValidation as expected.');
  process.exit(2);
}
if (typeof reconcileWitnesses !== 'function' || typeof isWitnessAlterationEvidence !== 'function') {
  console.error('analysis-core did not export reconcileWitnesses/isWitnessAlterationEvidence.');
  process.exit(2);
}

const bytes = fs.readFileSync(zipPath);
const arrayBuffer = bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength);

// A FIXED "now". The loader takes it as a parameter precisely so a bundle reads
// the same today and in an adjudication years from now; passing Date.now() here
// would make this gate's verdict depend on when it ran.
const loaded = await loadBundle(arrayBuffer, path.basename(zipPath), () => '2026-05-19T00:00:00.000Z');

if (!loaded.ok) {
  console.error(`loadBundle FAILED for ${path.basename(zipPath)}:`);
  console.error(JSON.stringify(loaded.error, null, 2));
  process.exit(3);
}

const bundle = loaded.value;
// The root public key is a PARAMETER of the real validator, never a constant in
// analysis-core — one deployment's root is not another's. Check 2 walks the
// Manifest 2.0 trust chain only when it is supplied, and reports 'skipped'
// otherwise; a gate that let that skip through would never exercise the chain.
const report = await runValidation(bundle, rootPubkeyHex ? { rootPubkeyHex } : {});

const line = (c) => `  ${c.status === 'pass' ? 'ok  ' : c.status.padEnd(4)} ${c.id}${c.detail ? ` — ${c.detail}` : ''}`;
console.log(`${path.basename(zipPath)}: format_version=${bundle.manifest.format_version} sessions=${bundle.sessions.length}`);
for (const c of report.checks) console.log(line(c));
for (const c of report.bundleDetections ?? []) console.log(`  [detection] ${c.status} ${c.id}${c.detail ? ` — ${c.detail}` : ''}`);

const problems = [];

// The gate proper: ALL EIGHT must pass. Not "none failed" — a skipped check is a
// check that proved nothing, and a producer that omits the inputs a check needs
// would sail through a fail-only gate.
if (report.checks.length !== 8) {
  problems.push(`expected 8 checks, got ${report.checks.length}`);
}
for (const c of report.checks) {
  if (c.status !== 'pass') problems.push(`check '${c.id}' is '${c.status}'${c.detail ? ` (${c.detail})` : ''}`);
}
if (report.overall !== 'pass') problems.push(`overall = '${report.overall}'`);

// PEER WITNESSING (program spec §7 mechanism 2), judged by the REAL reader.
//
// A `git pull` in a shared repository drops a partner's `.slog` into `.provenance/`, and this
// recorder writes what it saw into its OWN signed chain. The claim under test is not that the
// entry parses — the Gradle suite covers that — it is that `reconcileWitnesses`, unmodified,
// reaches `corroborated` for it. Nothing inside this repository can assert that: the verdict is
// computed by the consumer, from the archive, and a producer that emitted a subtly wrong
// `seq_high` or `last_hash` would look perfectly healthy to its own tests and reconcile as
// `tip_mismatch` here — which is the strongest ALTERATION signal the system has, manufactured
// against an innocent student.
const witness = reconcileWitnesses(bundle);
console.log(
  `  [witness] ${JSON.stringify(witness.counts)}` +
    (witness.malformed.length > 0 ? ` malformed=${JSON.stringify(witness.malformed)}` : ''),
);
for (const w of witness.witnesses) {
  console.log(`  [witness] ${w.verdict} ${w.witness.payload.file} — ${w.detail}`);
}

// A malformed witness is never a finding, but from THIS producer it is a defect: it means the
// recorder emitted a payload its own shared narrowing rejects.
if (witness.malformed.length > 0) {
  problems.push(`the recorder emitted ${witness.malformed.length} unreadable peer.observed payload(s)`);
}
// `isWitnessAlterationEvidence` is the single gate the reader uses to decide whether a witness
// says a log was altered. An honest archive produced by one machine in one run must never trip
// it — a false positive here is a wrongful accusation, which is the bar this whole programme is
// judged against.
for (const w of witness.witnesses) {
  if (isWitnessAlterationEvidence(w)) {
    problems.push(`an honest archive produced alteration evidence: ${w.verdict} — ${w.detail}`);
  }
}

if (shape === 'rolling') {
  // The rolling archive is the shared-repository shape, and the producer plants a real second
  // contributor's log in it. At least one witness must therefore reach `corroborated`.
  //
  // Asserted as a POSITIVE count, not as "nothing failed": `absent` and `indeterminate` are
  // both blameless, so a producer that stopped witnessing altogether, or that named a log it
  // never packed, would sail through a fail-only check having proved nothing.
  if (witness.counts.corroborated < 1) {
    problems.push(
      `expected at least one corroborated peer witness in the rolling archive, got ` +
        `${JSON.stringify(witness.counts)}`,
    );
  }
  if (witness.counts.tip_mismatch > 0 || witness.counts.short > 0) {
    problems.push(
      `an honest archive must not produce tip_mismatch or short witnesses: ` +
        `${JSON.stringify(witness.counts)}`,
    );
  }
}

// Shape assertions. Without these the gate would still be green if BOTH archives
// happened to come out as the same shape — which is exactly the failure mode of
// "we added rolling coverage" that this is supposed to prevent.
if (shape === 'rolling') {
  const seals = bundle.rollingSeal?.seals ?? [];
  const defects = bundle.rollingSeal?.defects ?? [];
  if (seals.length === 0) problems.push('expected a rolling-sealed archive, but the loader found no rolling seals');
  if (defects.length > 0) problems.push(`rolling seal defects: ${JSON.stringify(defects)}`);
  if (bundle.manifest.format_version !== '1.2') {
    problems.push(`expected a synthesized 1.2 rolling manifest, got '${bundle.manifest.format_version}'`);
  }
} else if (shape === 'classic') {
  if ((bundle.rollingSeal?.seals ?? []).length > 0) {
    problems.push('expected a purely classic archive, but the loader found rolling seals');
  }
  if (bundle.manifest.format_version !== '1.1') {
    problems.push(`expected a 1.1 sealed manifest, got '${bundle.manifest.format_version}'`);
  }
}

if (problems.length > 0) {
  console.error(`\nGATE FAILED for ${path.basename(zipPath)}:`);
  for (const p of problems) console.error(`  - ${p}`);
  process.exit(3);
}

console.log(`\nGATE PASSED for ${path.basename(zipPath)}: all 8 checks pass, overall=${report.overall}.`);
process.exit(0);
