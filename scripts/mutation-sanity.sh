#!/usr/bin/env bash
# Mutation sanity for ledger-core: skip the debit side of every posting and confirm the
# properties catch it. Reverts afterwards. Exit 0 only when the mutant is killed.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

LEDGER="ledger-core/src/main/java/com/agentpaygate/ledger/Ledger.java"
BACKUP="$(mktemp)"
OUT="docs/evidence/mutation-ledger-skip-debit.txt"
mkdir -p docs/evidence
cp "$LEDGER" "$BACKUP"

restore() { cp "$BACKUP" "$LEDGER"; rm -f "$BACKUP"; }
trap restore EXIT

python3 - "$LEDGER" <<'PY'
import sys
path = sys.argv[1]
source = open(path).read()
needle = """            long current = candidate.getOrDefault(line.account(), 0L);
            candidate.put(line.account(), Math.addExact(current, line.amount()));"""
mutant = """            if (line.amount() < 0L) {
                continue; // MUTANT: skip the debit side (deliberate bug)
            }
            long current = candidate.getOrDefault(line.account(), 0L);
            candidate.put(line.account(), Math.addExact(current, line.amount()));"""
if needle not in source:
    sys.exit("mutation target not found: Ledger.post loop changed?")
open(path, "w").write(source.replace(needle, mutant, 1))
PY
if [ $? -ne 0 ]; then
  echo "mutation could not be applied"
  exit 2
fi

{
  echo "# Ledger mutation sanity: skip the debit side of every posting"
  echo "# command: ./scripts/mutation-sanity.sh"
  echo "# mutant: Ledger.post ignores lines with a negative amount"
  echo
} > "$OUT"

./mvnw -B -pl ledger-core test >> "$OUT" 2>&1
STATUS=$?
{
  echo
  echo "exit code: $STATUS (expected non-zero)"
} >> "$OUT"

if [ "$STATUS" -eq 0 ]; then
  echo "MUTATION SURVIVED: the ledger tests did not catch a skipped debit (see $OUT)"
  exit 1
fi
echo "mutation killed: ledger properties caught the skipped debit, see $OUT"
