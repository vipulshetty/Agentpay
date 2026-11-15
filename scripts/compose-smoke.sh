#!/usr/bin/env bash
# Smoke test for the compose stack: wait for health, seed a demo agent and mandate through the
# gateway, then drive a real payment over MCP through the edge. Exits non-zero on any failure.
#
#   docker compose -f deploy/docker-compose.yml up -d --build
#   ./scripts/compose-smoke.sh
set -euo pipefail
cd "$(dirname "$0")/.." || exit 1

BASE="${GATEWAY_BASE_URL:-http://localhost:18080}"
EDGE="${EDGE_BASE_URL:-http://localhost:18088}"
ADMIN="${GATEWAY_ADMIN_TOKEN:-compose-admin-token}"

echo "waiting for $BASE ..."
for _ in $(seq 1 60); do
  if [ "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/actuator/health" || true)" = "200" ]; then
    break
  fi
  sleep 2
done
curl -sf "$BASE/actuator/health" > /dev/null || { echo "gateway is not healthy at $BASE" >&2; exit 1; }
curl -sf "$EDGE/-/health" > /dev/null || { echo "edge is not healthy at $EDGE" >&2; exit 1; }
echo "gateway and edge are healthy"

grep -q 'apg_' .run/api-key.txt 2>/dev/null || true
GATEWAY_BASE_URL="$BASE" GATEWAY_ADMIN_TOKEN="$ADMIN" ./scripts/demo-setup.sh > /tmp/compose-setup.out
tail -3 /tmp/compose-setup.out

GATEWAY_BASE_URL="$EDGE" node scripts/mcp-smoke.mjs | tail -3
echo "compose smoke: PASS"
