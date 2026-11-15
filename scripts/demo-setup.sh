#!/usr/bin/env bash
# Demo setup: creates the demo agent, registers the demo issuer key, signs and uploads a demo
# mandate, and funds the agent wallet. Idempotent enough to re-run against a live stack.
#
# Everything here is simulated. The keys written under secrets/ are demo keys, never real ones.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

BASE="${GATEWAY_BASE_URL:-http://localhost:8090}"
ADMIN_TOKEN="${GATEWAY_ADMIN_TOKEN:-demo-admin-token}"
AGENT_ID="${DEMO_AGENT_ID:-agent-demo}"
KEY_ID="demo-issuer"
MANDATE_ID="mandate-demo"
mkdir -p secrets demo/mandates .run

CP="mandate-cli/target/classes:policy-core/target/classes"

status() { curl -s -o /dev/null -w "%{http_code}" "$@"; }

if [ "$(status "$BASE/actuator/health")" != "200" ]; then
  echo "gateway is not healthy at $BASE - start it first" >&2
  exit 1
fi

if [ ! -f "secrets/$KEY_ID-private.key" ]; then
  java -cp "$CP" com.agentpaygate.tools.MandateToolMain keygen "$KEY_ID"
fi

existing_key="$(cat .run/api-key.txt 2>/dev/null || true)"
if [ "${existing_key#apg_}" = "$existing_key" ]; then
  agent_json=$(curl -s -X POST "$BASE/admin/agents" \
    -H "X-Admin-Token: $ADMIN_TOKEN" -H 'Content-Type: application/json' \
    -d "{\"agentId\":\"$AGENT_ID\",\"displayName\":\"Demo agent\"}")
  key=$(printf '%s' "$agent_json" | python3 -c 'import sys,json; print(json.load(sys.stdin).get("apiKey",""))')
  if [ -z "$key" ]; then
    echo "could not create agent: $agent_json" >&2
    exit 1
  fi
  printf '%s' "$key" > .run/api-key.txt
  printf '%s\n' "$agent_json" > .run/agent.json
  echo "created agent $AGENT_ID (demo API key stored in .run/api-key.txt, git-ignored)"
fi

# The gateway resolves merchant categories from its own directory, so the demo merchant has to be
# registered here: an agent must not be able to decide that it is paying a grocer.
curl -s -X POST "$BASE/admin/merchants" \
  -H "X-Admin-Token: $ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d '{"merchantId":"merchant-bookshop","category":"groceries"}' > /dev/null
curl -s -X POST "$BASE/admin/merchants" \
  -H "X-Admin-Token: $ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d '{"merchantId":"merchant-cafe","category":"restaurants"}' > /dev/null

echo "registered demo merchants"

public_b64=$(grep -v -- '-----' "secrets/$KEY_ID-public.key" | tr -d '\n')
curl -s -X POST "$BASE/admin/issuer-keys" \
  -H "X-Admin-Token: $ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d "{\"keyId\":\"$KEY_ID\",\"publicKeyBase64\":\"$public_b64\"}" > /dev/null
echo "registered issuer key $KEY_ID"

if [ ! -f "demo/mandates/$MANDATE_ID.mandate" ]; then
  java -cp "$CP" com.agentpaygate.tools.MandateToolMain sign "$KEY_ID" \
    --mandate-id "$MANDATE_ID" --agent "$AGENT_ID" --currency INR \
    --per-tx 250000 --total 2000000 --per-day 500000 \
    --merchants merchant-bookshop --categories groceries \
    --not-before 0 --expires-at 4102444800
fi

upload=$(curl -s -X POST "$BASE/admin/mandates" \
  -H "X-Admin-Token: $ADMIN_TOKEN" -H 'Content-Type: text/plain' \
  --data-binary "@demo/mandates/$MANDATE_ID.mandate")
echo "mandate upload: $upload"

funding=$(curl -s -X POST "$BASE/admin/funding" \
  -H "X-Admin-Token: $ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d "{\"agentId\":\"$AGENT_ID\",\"amountMinor\":5000000,\"currency\":\"INR\"}")
echo "wallet funding: $funding"

echo
echo "agent API key: $(cat .run/api-key.txt)"
echo "mandate id:    $MANDATE_ID"
