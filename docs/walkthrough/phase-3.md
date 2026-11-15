# Phase 3 — Gateway, provider simulator and the MCP surface

This is the phase where the pure cores meet the real world: Postgres, HTTP, a second service, and
concurrency. The rule for the phase was that every failure the tests found gets fixed in the
product, not in the assertion.

## What exists now

`gateway-app` is a Spring Boot service with a Flyway-managed schema:

| Table | Purpose |
|---|---|
| `agents`, `issuer_keys`, `mandate_nonces` | identity and mandate trust anchors; API keys stored as SHA-256 |
| `mandates`, `mandate_spend` | the mandate as uploaded, plus the spend/reserve counters the policy needs |
| `payments`, `payment_events` | payment state, provider reference, provider idempotency key, event log |
| `idempotency_keys` | agent-scoped key → payment, with a payload hash per key |
| `ledger_postings` | double-entry postings, one business event per balanced set |
| `outbox` | at-least-once work queue for provider calls, claimed with `for update skip locked` |
| `webhook_inbox`, `reconciliation_discrepancies`, `audit_log`, `audit_chain_head` | inbound events, reconciliation results, hash-chained audit |

Two credentials, deliberately different: agents use `Authorization: Bearer apg_...` (hashed lookup,
`ROLE_AGENT`, MCP only) and operators use `X-Admin-Token` (constant-time compare, admin API only).
Neither can act as the other.

`psp-sim` is a separate Spring Boot service pretending to be the provider. It owns charges,
refunds, a statement, configurable failure modes, HMAC-signed webhooks, and it deduplicates on the
idempotency key exactly like a real provider would.

## The money path

```
agent --MCP--> gateway: policy decision (pure) -> reserve -> insert payment + outbox row -> 200
outbox worker --> provider: charge with the stored idempotency key
provider --> gateway: 200 (synchronous result) and/or signed webhook
gateway --> ledger: balanced postings, once per business event
```

Nothing in that path waits on the provider inside the request. A payment that is approved but not
yet captured is a normal, visible state (`AUTHORIZING`), not an error.

## Bugs the integration tests found (and how they were fixed)

These are the reason the phase took the time it did. Each one was a product change.

1. **The audit hash chain forked under concurrency.** Two appends read the same predecessor and
   both wrote it, so `verify()` failed afterwards. The original fix locked the whole table
   (`lock table audit_log in exclusive mode`), which cannot run outside a transaction block — the
   crash-recovery path called it with no transaction and got SQLSTATE 25P01. Replaced with a
   single-row `audit_chain_head` that is locked with `select ... for update` inside a
   `TransactionTemplate`; appends serialise, the chain stays linear, and the lock joins the
   caller's transaction so an audit row for a rolled-back action rolls back with it.
2. **The provider's "timeout after charge" failure mode was wrong.** It returned 504, which tells
   the gateway "the provider failed" — not "we do not know". It now holds the response past the
   gateway's read timeout, still charges, and sends no webhook: the honest shape of a lost
   response.
3. **A read timeout was not classified as a timeout.** It surfaced as a
   `RestClientException` wrapping `SocketTimeoutException` while the body was being consumed, not
   as `ResourceAccessException`, so the worker took the generic-error branch and retried forever
   instead of parking the payment in `CAPTURE_UNKNOWN`. `PspClient` now walks the cause chain for
   a socket/timeout exception and maps it to `PspTimeout`.
4. **A crash between the provider call and the local commit was not recoverable.** The payment
   stayed `AUTHORIZING` while its outbox row was already `SENT`, so nothing would ever retry it.
   `OutboxWorker.recoverStuck()` now re-enqueues any `AUTHORIZING` payment that has no queued work,
   with its stored provider idempotency key; the provider returns the original charge for a
   repeated key, and the ledger's business-event id stops a second posting. The test asserts one
   charge at the provider, one capture in the ledger, and a merchant balance that did not move.
5. **An unknown charge could not be reconciled.** A payment whose provider call timed out never
   learned its provider reference, and reconciliation matched on that reference — so the real
   charge looked like "missing internally" forever. The provider statement now carries the
   payment id the gateway sent, and reconciliation binds the reference from the statement before
   comparing. The timeout test then converges to `CAPTURED` with one discrepancy resolved
   automatically.

## Two Spring traps worth recording

- **`/mcp` did not exist.** Spring AI 1.0.0's MCP server auto-configuration for WebMVC only
  registers the SSE transport; Streamable HTTP is not in that jar at all, so
  `spring.ai.mcp.server.protocol=STREAMABLE` silently produced a server whose `/mcp` returned 404
  while `/mcp/sse` and `/mcp/message` worked. Fixed by moving to Spring AI 1.1.8, which has
  `McpServerStreamableHttpWebMvcAutoConfiguration` (see ADR-003).
- **Every error became a 401.** `anyRequest().denyAll()` also covered the container's `/error`
  dispatch, so any failure inside the MCP handler was rewritten as an empty 401 and the cause was
  invisible. `/error` is now permitted, which is why the 404 above was finally visible.

## Gate evidence

| Command | Result |
|---|---|
| `./mvnw -B verify` | exit 0 — 91 tests, 0 failures, 0 errors, 0 skipped (`docs/evidence/phase-3-verify.txt`) |
| `node scripts/mcp-smoke.mjs` | exit 0 — 5 tools, a real `request_payment`, status read back (`docs/evidence/phase-3-mcp-smoke.txt`) |
| `curl -s localhost:8090/v3/api-docs -o docs/api/openapi.json` | 13 paths, OpenAPI 3.1.0 (`docs/api/openapi.json`) |
| `./scripts/demo-setup.sh` | exit 0 — agent, issuer key, mandate, funding |

Test counts by module: ledger 14, policy 33, payments 28, sim 4, gateway IT 12. The 12 integration
tests run against a real Postgres container and a real `psp-sim` container; a Docker test that does
not run fails the build rather than skipping.

The MCP test drives the endpoint the way a client does — `initialize`, `tools/list`, `tools/call`
`request_payment`, then `get_payment_status` — and asserts the money landed in the merchant's
ledger account, so a transport regression cannot pass unnoticed.

## Honest gaps

- All 12 integration tests share one Postgres container and one Spring context; they are ordered and
  use a distinct agent and merchant per test, but they are not isolated databases.
- OpenAPI documents the admin REST API and the webhook endpoints. The MCP tool surface is described
  by `tools/list` and by the tool classes, not by OpenAPI.
- `GatewayIT` needs `psp-sim`'s jar built first (`./mvnw -pl psp-sim -am package -DskipTests`); the
  full reactor build does that in order.
- Nothing here is production software and nothing claims to be: the provider is simulated, money is
  simulated, and the ledger is a demonstration of double-entry bookkeeping.
- The CI workflow is still UNVERIFIED: the repository has no remote, so no runner has executed it.
