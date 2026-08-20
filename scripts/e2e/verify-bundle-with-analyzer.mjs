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
try {
  const mod = await import(url.pathToFileURL(analysisCoreEntry).href);
  ({ loadBundle, runValidation } = mod);
} catch (e) {
  console.error(`Failed to import analysis-core from ${analysisCoreEntry}:`);
  console.error(e);
  process.exit(2);
}

if (typeof loadBundle !== 'function' || typeof runValidation !== 'function') {
  console.error('analysis-core did not export loadBundle/runValidation as expected.');
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
