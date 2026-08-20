#!/usr/bin/env bash
# THE CROSS-IMPLEMENTATION GATE.
#
# Produces real submission archives with this repo's JetBrains recorder — both
# shapes it can write — and hands each to the REAL Provenance monorepo's
# analysis-core (loadBundle + runValidation), requiring all eight PRD §5.4
# checks to pass.
#
# This is the test class that has caught the most defects on this project,
# because it is the only one where a SECOND implementation of the format is
# judged by the FIRST one's reader rather than by its own expectations.
#
# Each archive carries TWO sessions against one `.provenance/`: a normal one,
# and one that starts and is torn down BEFORE ITS FIRST FLUSH. The second is the
# shape this gate could not previously express, and not expressing it is what let
# an unopenable bundle ship — a zero-byte `.slog`, its `.slog.meta`, and (in the
# git shape) the rolling seal that write point 1 signs over the empty log, all
# packed, any one of which makes `analysis-core` reject the WHOLE archive. The
# producer asserts on disk, BEFORE packing, that write point 1 sealed that
# session anyway: a zero-event session must still be sealed, or a git-submitted
# repo reports `unsealed_session` against a student who did nothing wrong.
#
# Usage: scripts/e2e/run_e2e.sh
# Env:   PROVENANCE_MONOREPO  (default: ../provenance, beside this repo)
#        NODE                 (default: node)
#
# SKIPS, LOUDLY AND WITH EXIT 0, when the monorepo or node is unavailable. That
# is deliberate: making this mandatory would turn a checkout of this repository
# into a cross-repo build dependency, and a red gate that means "you do not have
# the sibling repo" trains people to ignore the gate that means "the format
# diverged". The producing half runs under `./gradlew test` regardless, so a
# skip here never leaves the recorder's written output completely unchecked.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

NODE="${NODE:-node}"
PROVENANCE_MONOREPO="${PROVENANCE_MONOREPO:-$(cd "$REPO_ROOT/.." && pwd)/provenance}"
ANALYSIS_CORE="$PROVENANCE_MONOREPO/packages/analysis-core/dist/index.js"

skip() {
  echo
  echo "SKIP: $1"
  echo
  echo "  This gate needs a BUILT Provenance monorepo beside this repo. To enable it:"
  echo "    git clone <provenance> $PROVENANCE_MONOREPO   # or set PROVENANCE_MONOREPO"
  echo "    (cd \"$PROVENANCE_MONOREPO\" && npm ci && npm run build)"
  echo
  echo "  Skipping is not a pass. Nothing has been validated against the real analyzer."
  exit 0
}

command -v "$NODE" >/dev/null 2>&1 || skip "node not found on PATH (set NODE=...)"
[ -d "$PROVENANCE_MONOREPO" ] || skip "monorepo not found at $PROVENANCE_MONOREPO"
[ -f "$ANALYSIS_CORE" ] || skip "analysis-core is not built: $ANALYSIS_CORE is missing"

echo "== Producing real bundles via the JetBrains recorder =="
./gradlew --console=plain :recorder:test \
  --tests "dev.provenance.recorder.CrossImplementationBundleTest"

OUT="$REPO_ROOT/recorder/build/e2e-cross-impl"
CLASSIC="$OUT/classic/classic-bundle.zip"
ROLLING="$OUT/rolling/rolling-bundle.zip"
ROOT_PUBKEY_FILE="$OUT/root-pubkey.txt"

for f in "$CLASSIC" "$ROLLING" "$ROOT_PUBKEY_FILE"; do
  if [ ! -f "$f" ]; then
    echo "FAIL: the producer did not leave $f"
    exit 1
  fi
done

echo
echo "== Validating against the real Provenance analyzer =="
echo "PROVENANCE_MONOREPO=$PROVENANCE_MONOREPO"
echo

# The gate's own root public key, published by the producer. analysis-core takes it
# as a parameter by design, so passing it is what lets check 2 actually WALK the
# Manifest 2.0 trust chain instead of reporting it unconfigured.
ROOT_PUBKEY="$(cat "$ROOT_PUBKEY_FILE")"

status=0
rolling_status=0
PROVENANCE_MONOREPO="$PROVENANCE_MONOREPO" \
  "$NODE" scripts/e2e/verify-bundle-with-analyzer.mjs "$CLASSIC" \
  --shape classic --root-pubkey "$ROOT_PUBKEY" || status=$?
echo
PROVENANCE_MONOREPO="$PROVENANCE_MONOREPO" \
  "$NODE" scripts/e2e/verify-bundle-with-analyzer.mjs "$ROLLING" \
  --shape rolling --root-pubkey "$ROOT_PUBKEY" || rolling_status=$?

if [ "$status" -ne 0 ] || [ "$rolling_status" -ne 0 ]; then
  echo
  echo "FAIL: the real analyzer did not accept a JetBrains-produced archive."
  exit 3
fi

echo
echo "PASS: the real analyzer accepted both JetBrains-produced archives (classic + rolling)."
