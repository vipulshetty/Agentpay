#!/usr/bin/env bash
# Phase 0 canary: prove the ArchUnit purity rule actually fires.
# Temporarily adds a class that reads the wall clock to policy-core, runs the module's tests,
# expects a FAILURE, then reverts. Exit status: 0 when the canary behaved as required.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

PROBE="policy-core/src/main/java/com/agentpaygate/policy/ClockProbe.java"
OUT="docs/evidence/canary-phase0.txt"
mkdir -p docs/evidence

cleanup() { rm -f "$PROBE"; }
trap cleanup EXIT

cat > "$PROBE" <<'JAVA'
package com.agentpaygate.policy;

/** Deliberate violation used by scripts/canary-phase0.sh, never committed. */
public final class ClockProbe {
    private ClockProbe() {
    }

    public static long now() {
        return java.time.Instant.now().getEpochSecond();
    }
}
JAVA

{
  echo "# Phase 0 canary: does the purity rule fire?"
  echo "# command: ./scripts/canary-phase0.sh"
  echo "# probe added: $PROBE reads the wall clock"
  echo
} > "$OUT"

./mvnw -B -pl policy-core test >> "$OUT" 2>&1
STATUS=$?
{
  echo
  echo "exit code: $STATUS (expected non-zero)"
} >> "$OUT"

rm -f "$PROBE"

if [ "$STATUS" -eq 0 ]; then
  echo "CANARY FAILED: the purity rule did not fire (see $OUT)"
  exit 1
fi
echo "canary OK: policy-core rejected the wall-clock probe, see $OUT"
