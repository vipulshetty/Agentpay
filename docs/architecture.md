# Architecture

## Components

```
agent (MCP client)
   │  POST /mcp  (JSON-RPC, JSON or SSE responses)
   ▼
edge-proxy (Go)          streams, rate limits per credential, caps bodies, strips identity headers
   ▼
gateway-app (Java 21)    auth, mandate enforcement, Postgres, outbox, ledger, audit, admin API, MCP
   ├── Postgres 16       payments, mandates, spend counters, ledger postings, outbox, audit chain
   └── outbox worker ──► psp-sim (Java)   charges, refunds, statement, HMAC webhooks
                          │
                          └── webhook ──► gateway /webhooks/psp  (HMAC over the raw body)
console (TypeScript)     operator views over /admin/*, proxied so no CORS and no token in a URL
sim (Java)               virtual clock + seeded generator driving the same pure cores
```

## The request path, in order

1. **Edge** caps the body, applies the per-credential token bucket, removes client-supplied identity
   headers and forwards without buffering.
2. **Gateway security** authenticates `Authorization: Bearer apg_…` against a SHA-256 hash in
   `agents`. The raw key is never stored or logged. Admin endpoints need `X-Admin-Token` instead;
   neither credential can act as the other.
3. **Idempotency** claims `(agent_id, idempotency_key)` first, with a payload hash. A replay returns
   the original payment; a different payload is a conflict, not a re-price.
4. **Mandate** is looked up and checked against the authenticated agent (a mandate belongs to exactly
   one agent).
5. **Spend row is locked**, then the pure `PolicyEngine` decides. Everything it needs is a parameter:
   the mandate, the request, the spend state, the evaluation instant, the issuer key registry and
   whether the nonce was used.
6. **Approval** inserts the payment, reserves budget, enqueues an outbox row with the payment's
   provider idempotency key, and returns. No provider call happens inside the request.
7. **Outbox worker** claims a row with `for update skip locked` and calls the provider with the key
   stored on the payment — the same key on every retry, including after a restart.
8. **Provider result or webhook** moves the payment through its state machine and posts to the
   ledger. The ledger's business-event id makes a second posting a no-op.
9. **Reconciliation** compares the provider statement with internal payments; only "we were unsure
   and the provider confirms the exact amount" is auto-resolved.

## The pure cores

`ledger-core`, `policy-core`, `payments-core` and `sim` contain no clock, no threads, no `Random`,
no `UUID.randomUUID`, no I/O, no networking and no Spring. That is enforced twice: ArchUnit rules on
the compiled classes, and a canary module that deliberately violates a rule so the rules are known to
fire. The payoff is that the policy decision and the state machine are pure functions — reproducible
in a test, in the simulator, and in a review.

## Data model (the parts that matter)

| Table | Why it exists |
|---|---|
| `payments` | state, provider ref, provider idempotency key, captured/refunded amounts |
| `mandate_spend` | spent and reserved counters, locked before every decision |
| `idempotency_keys` | one row per `(agent, key)`, with a payload hash |
| `ledger_postings` | balanced postings keyed by business event |
| `outbox` | at-least-once work queue with a claim column |
| `webhook_inbox` | delivered provider events, deduplicated by event id |
| `audit_log` + `audit_chain_head` | hash chain, serialised through a single locked head row |
| `merchants` | the operator-controlled category directory |

## Money rules, in one place each

- A payment's `provider_idempotency_key` is chosen once and never regenerated.
- `captured <= authorised` and `refunded <= captured` are enforced by the `Payment` constructor and
  by `Payment.apply`, so an impossible payment cannot be constructed.
- The ledger refuses any entry that does not sum to zero and any posting that would make a
  non-external account negative.
- `spent + reserved <= maxTotal` is the policy engine's rule, checked with `Math.addExact` so
  overflow is a denial rather than a wrap-around.

## Why the gateway is not in the model

The model's job is to ask. Limits, allowlists and signatures are enforced by code the model cannot
talk to, so a prompt injection, a confused agent or a retry loop all meet the same rules. The demo
agent exists to try to break that from the client side, and `docs/ABUSE-RESULTS.md` records what
happened when it did.
