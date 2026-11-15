# Fault model

What can go wrong, what the system does about it, and how that claim is tested. The rule throughout:
**never invent an outcome**. If the system does not know whether money moved, it says so.

| Fault | Detection | Behaviour | Evidence |
|---|---|---|---|
| Provider is slow past the read timeout | `PspClient` timeout classification walks the cause chain for `SocketTimeoutException` / `ResourceAccessException` | payment stays `CAPTURE_UNKNOWN` with its reservation held; reconciliation or a same-key retry resolves it | `providerTimeoutAfterChargeLeavesUnknownThenReconcilesOnce` |
| Provider returns 5xx | `PspServerError` | payment fails, reservation released, audit records the reason | integration failure-mode test |
| Provider is unreachable | connect error | same as 5xx: the call did not land | integration failure-mode test |
| Provider accepted the charge but the response was lost | read timeout after the provider charged | unknown state, never "failed" | same timeout test; the provider holds the response 5 s and still charges |
| Webhook arrives twice | event id already in `webhook_inbox` | reducer returns DUPLICATE, no state change, no second posting | integration duplicate test, simulator `DOUBLE_CAPTURE_ON_REPLAY` |
| Webhook arrives late / out of order | target state is behind the payment's progress | reducer returns STALE and ignores it | `ProviderEventReducerTest` |
| Webhook signature invalid or stale | HMAC over `timestamp.body`, tolerance window | 401, nothing recorded as an event | integration webhook tests |
| Gateway crashes between the provider call and the commit | payment `AUTHORIZING` with an outbox row already `SENT` | on restart, `OutboxWorker.recoverStuck()` re-enqueues with the stored provider key; the provider returns the original charge; the ledger's business event id prevents a second posting | `crashBetweenProviderCallAndCommitIsRecoveredWithoutDoubleCharging` |
| Gateway restarts while the outbox has pending rows | rows still `PENDING` | worker claims them with `for update skip locked` and retries | integration tests run the real worker |
| Two identical requests race | unique `(agent, idempotency_key)` | one wins; the loser reads the winner's payment and returns `replayed: true` | integration concurrent-request test |
| Two appends to the audit log race | chain head row locked `for update` | appends serialise; the chain never forks | `auditChainFailsAfterATamperedRow` plus the concurrency test that originally forked it |
| Database goes away mid-request | JDBC error | transaction rolls back; no partial payment, no partial posting | not simulated as a fault injection; the transaction boundary is what covers it — **not separately tested** |
| Edge is flooded | token bucket per credential | 429 with `Retry-After`; other credentials unaffected | `TestRateLimitIsPerCredential`, load run showing 125 × 429 |
| Oversized request reaches the edge | declared length or `MaxBytesReader` | 413 before forwarding | `TestDeclaredOversizedBodyIsRejectedBeforeForwarding` |
| Upstream (gateway) is down | proxy error handler | 502 with a JSON body, not a reset | `TestUnreachableUpstreamBecomes502` |
| Agent retries with a different payload under the same key | payload hash mismatch | conflict, rejected, original payment untouched | integration conflict test, demo agent abuse case |
| Operator tampers with the audit log | hash mismatch on verification | `/admin/audit/verify` returns `valid: false` and the broken seq | `auditChainFailsAfterATamperedRow` |

## What is not covered

- **Postgres failure injection** (kill the database mid-transaction) is not exercised. The transaction
  boundary is the argument, and an argument is not a test.
- **Clock skew** between gateway and provider affects the webhook tolerance window; the demo stack
  runs both on one host, so this is untested.
- **Partial provider responses** (a truncated body) are not injected; the timeout path is.
- **Disk-full and OOM** behaviour is not tested.
- **Multi-instance** behaviour: the outbox and spend locking are designed to be safe with more than
  one gateway, but only one is ever run.
