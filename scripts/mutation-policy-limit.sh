#!/usr/bin/env bash
# Mutation sanity for the policy limit check: turn the per-transaction boundary from
# "amount > limit denies" into "amount >= limit denies" and confirm a test fails. Reverts after.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

ENGINE="policy-core/src/main/java/com/agentpaygate/policy/PolicyEngine.java"
BACKUP="$(mktemp)"
OUT="docs/evidence/mutation-policy-limit-boundary.txt"
mkdir -p docs/evidence
cp "$ENGINE" "$BACKUP"

restore() { cp "$BACKUP" "$ENGINE"; rm -f "$BACKUP"; }
trap restore EXIT

python3 - "$ENGINE" <<'PY'
import sys
path = sys.argv[1]
source = open(path).read()
needle = "if (request.amountMinor() > mandate.maxPerTransaction()) {"
mutant = "if (request.amountMinor() >= mandate.maxPerTransaction()) { // MUTANT: boundary off by one"
if needle not in source:
    sys.exit("mutation target not found in PolicyEngine")
open(path, "w").write(source.replace(needle, mutant, 1))
PY
if [ $? -ne 0 ]; then
  echo "mutation could not be applied"
  exit 2
fi

{
  echo "# Policy mutation sanity: per-transaction boundary off by one"
  echo "# command: ./scripts/mutation-policy-limit.sh"
  echo "# mutant: 'amount > maxPerTransaction' changed to 'amount >= maxPerTransaction'"
  echo
} > "$OUT"

./mvnw -B -pl policy-core test >> "$OUT" 2>&1
STATUS=$?
{
  echo
  echo "exit code: $STATUS (expected non-zero)"
} >> "$OUT"

if [ "$STATUS" -eq 0 ]; then
  echo "MUTATION SURVIVED: no test caught the boundary change (see $OUT)"
  exit 1
fi
echo "mutation killed: the boundary change was caught, see $OUT"
