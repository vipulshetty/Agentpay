#!/usr/bin/env bash
# Top-level gate for AgentPayGate. Runs every component's checks and exits non-zero if any
# component fails or if any test self-skipped (a skipped Docker or browser test is a FAIL, never
# a pass). Components that do not exist yet are reported as ABSENT, not as passing.
set -uo pipefail

cd "$(dirname "$0")/.." || exit 1
ROOT="$(pwd)"
FAIL=0
declare -a SUMMARY=()

note() { SUMMARY+=("$1"); echo "$1"; }
fail_component() { FAIL=1; note "FAIL  $1"; }

# ---------------------------------------------------------------- Java (all modules)
echo
echo "=== Java: ./mvnw -B verify ==="
JAVA_LOG="$(mktemp)"
./mvnw -B verify 2>&1 | tee "$JAVA_LOG"
JAVA_STATUS=${PIPESTATUS[0]}

JAVA_TESTS=0
JAVA_FAILED=0
JAVA_SKIPPED=0
if compgen -G "*/target/surefire-reports/*.xml" > /dev/null; then
  read -r JAVA_TESTS JAVA_FAILED JAVA_SKIPPED < <(python3 - "$ROOT" <<'PY'
import glob, sys, xml.etree.ElementTree as ET
root = sys.argv[1]
tests = failures = skipped = 0
for path in glob.glob(f"{root}/*/target/surefire-reports/*.xml"):
    try:
        suite = ET.parse(path).getroot()
    except ET.ParseError:
        continue
    tests += int(suite.get("tests", 0))
    failures += int(suite.get("failures", 0)) + int(suite.get("errors", 0))
    skipped += int(suite.get("skipped", 0))
print(tests, failures, skipped)
PY
)
fi

if [ "$JAVA_STATUS" -ne 0 ]; then
  fail_component "Java build: exit $JAVA_STATUS (tests: $JAVA_TESTS run, $JAVA_FAILED failed, $JAVA_SKIPPED skipped)"
else
  note "PASS  Java build: exit 0 (tests: $JAVA_TESTS run, $JAVA_FAILED failed, $JAVA_SKIPPED skipped)"
fi
if [ "$JAVA_SKIPPED" -ne 0 ]; then
  fail_component "Java: $JAVA_SKIPPED self-skipped tests (skipped tests are a FAIL)"
fi

# ---------------------------------------------------------------- Go edge proxy
if [ -d edge-proxy ]; then
  echo
  echo "=== Go: go vet ./... && go test -race ./... (edge-proxy) ==="
  ( cd edge-proxy && go vet ./... && go test -race ./... )
  GO_STATUS=$?
  if [ "$GO_STATUS" -ne 0 ]; then
    fail_component "Go edge-proxy: exit $GO_STATUS"
  else
    note "PASS  Go edge-proxy: go vet + go test -race exit 0"
  fi
else
  note "ABSENT edge-proxy (not built yet)"
fi

# ---------------------------------------------------------------- TypeScript components
for component in console demo-agent; do
  if [ -d "$component" ] && [ -f "$component/package.json" ]; then
    echo
    echo "=== TypeScript: $component npm run check ==="
    ( cd "$component" && npm run --silent check )
    TS_STATUS=$?
    if [ "$TS_STATUS" -ne 0 ]; then
      fail_component "TypeScript $component: exit $TS_STATUS"
    else
      note "PASS  TypeScript $component"
    fi
  else
    note "ABSENT $component (not built yet)"
  fi
done

# ---------------------------------------------------------------- Summary
echo
echo "================ VERIFY-ALL SUMMARY ================"
for line in "${SUMMARY[@]}"; do
  echo "  $line"
done
if [ "$FAIL" -ne 0 ]; then
  echo "  RESULT: FAIL"
  echo "==================================================="
  exit 1
fi
echo "  RESULT: PASS (components marked ABSENT are not verified)"
echo "==================================================="
exit 0
