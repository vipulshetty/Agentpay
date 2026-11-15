#!/usr/bin/env bash
# Runs the simulation gauntlet and prints the detection matrix.
#
#   ./scripts/sim-gauntlet.sh [seeds] [steps]
#
# The report is the evidence that the invariant set notices each planted defect. A bug listed as
# NOT CAUGHT is a hole in the invariants, and the script exits non-zero for it.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

SEEDS="${1:-40}"
STEPS="${2:-80}"

./mvnw -B -q -pl sim,mandate-cli install -DskipTests || exit 1

CP="mandate-cli/target/classes:sim/target/classes:payments-core/target/classes:policy-core/target/classes:ledger-core/target/classes"
REPORT="$(java -cp "$CP" com.agentpaygate.tools.SimGauntletMain "$SEEDS" "$STEPS")"
STATUS=$?
printf '%s\n' "$REPORT"
if [ "$STATUS" -ne 0 ]; then
  exit "$STATUS"
fi
if printf '%s' "$REPORT" | grep -q "NOT CAUGHT"; then
  echo "gauntlet: FAIL, at least one planted bug was not caught" >&2
  exit 1
fi
exit 0
