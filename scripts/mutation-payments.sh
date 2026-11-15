#!/usr/bin/env bash
# Mutation sanity for payments-core, two rules:
#   1. the provider-event reducer stops ignoring stale events;
#   2. the idempotency registry ignores the payload hash on replay.
# Each mutant must be killed by the existing tests. Reverts afterwards.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

REDUCER="payments-core/src/main/java/com/agentpaygate/payments/ProviderEventReducer.java"
REGISTRY="payments-core/src/main/java/com/agentpaygate/payments/IdempotencyRegistry.java"
OUT="docs/evidence/mutation-payments.txt"
mkdir -p docs/evidence
REDUCER_BAK="$(mktemp)"
REGISTRY_BAK="$(mktemp)"
cp "$REDUCER" "$REDUCER_BAK"
cp "$REGISTRY" "$REGISTRY_BAK"
cleanup() { cp "$REDUCER_BAK" "$REDUCER"; cp "$REGISTRY_BAK" "$REGISTRY"; rm -f "$REDUCER_BAK" "$REGISTRY_BAK"; }
trap cleanup EXIT

{
  echo "# payments-core mutation sanity"
  echo "# command: ./scripts/mutation-payments.sh"
  echo
  echo "## Mutant 1: reducer no longer ignores stale (out-of-order) events"
} > "$OUT"

python3 - "$REDUCER" <<'PY'
import sys
path = sys.argv[1]
source = open(path).read()
needle = """        if (isStale(payment.state(), target)) {"""
mutant = """        if (false && isStale(payment.state(), target)) { // MUTANT: stale guard disabled"""
if needle not in source:
    sys.exit("mutation target 1 not found")
open(path, "w").write(source.replace(needle, mutant, 1))
PY
[ $? -ne 0 ] && exit 2

./mvnw -B -pl payments-core test >> "$OUT" 2>&1
STATUS1=$?
echo "exit code: $STATUS1 (expected non-zero)" >> "$OUT"
cp "$REDUCER_BAK" "$REDUCER"

{
  echo
  echo "## Mutant 2: idempotency registry returns REPLAY for any known key, ignoring the payload"
} >> "$OUT"

python3 - "$REGISTRY" <<'PY'
import sys
path = sys.argv[1]
source = open(path).read()
needle = """        if (!stored.payloadHash().equals(payloadHash)) {
            return IdempotencyCheck.conflict(stored.payloadHash(), stored.paymentId());
        }"""
mutant = """        // MUTANT: payload comparison removed
"""
if needle not in source:
    sys.exit("mutation target 2 not found")
open(path, "w").write(source.replace(needle, mutant, 1))
PY
[ $? -ne 0 ] && exit 2

./mvnw -B -pl payments-core test >> "$OUT" 2>&1
STATUS2=$?
echo "exit code: $STATUS2 (expected non-zero)" >> "$OUT"
cp "$REGISTRY_BAK" "$REGISTRY"

if [ "$STATUS1" -eq 0 ] || [ "$STATUS2" -eq 0 ]; then
  echo "MUTATION SURVIVED (stale-guard=$STATUS1, payload-check=$STATUS2), see $OUT"
  exit 1
fi
echo "both payment mutants killed, see $OUT"
