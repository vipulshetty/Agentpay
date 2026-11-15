# Threat model

The system is a **simulation**. This model is still written as if it mattered, because the point of
the project is to show how the enforcement is structured, and a threat model that assumes nobody is
trying is not a threat model.

## Assets

1. The issuer's signing key (the root of a mandate's authority).
2. Agent API keys.
3. Money: the agent's funded wallet and the merchant's payable balance.
4. Mandate budget counters.
5. Ledger and audit integrity.
6. The provider's webhook secret.

## Trust boundaries

```
untrusted:      the agent, its prompt, its tool arguments, any client-supplied header
semi-trusted:   the edge proxy (it may rate limit and sanitise, it must never authenticate)
trusted core:   gateway process, Postgres, the pure policy/ledger/state-machine code
trusted but external: the issuer (holds the private key)
```

The agent is untrusted even when it holds a valid API key: a key proves *which* agent is asking, not
that the request is within the mandate.

## Threats and mitigations

| # | Threat | Mitigation | Residual risk |
|---|---|---|---|
| 1 | Agent asks for more than the per-transaction limit | `PolicyEngine` compares before any write; denial is recorded | none known |
| 2 | Agent spends past the daily/lifetime budget, including via concurrency | spend row locked, then `spent + reserved` compared with `Math.addExact` | none known; exercised by the `IGNORE_TOTAL_LIMIT` mutant |
| 3 | Agent pays a merchant outside the allowlist | merchant id must match the mandate's list | an explicitly allowed merchant is always allowed, by design |
| 4 | Agent claims an allowed category for a disallowed merchant | category resolved from the `merchants` directory, never from the request | **this was a real bypass, found by the demo agent and fixed in Phase 6** |
| 5 | Agent reuses an idempotency key with a different payload to re-price | payload hash per key; conflict is rejected | none known |
| 6 | Network retry creates a second payment | replay returns the stored payment with `replayed: true` | none known |
| 7 | Retry after a timeout charges twice | the provider idempotency key is stored on the payment and reused; the provider deduplicates | depends on the provider honouring the key |
| 8 | An unknown outcome is silently treated as success or failure | explicit `AUTHORIZATION_UNKNOWN` / `CAPTURE_UNKNOWN` states; only reconciliation or a same-key retry resolves | operator must act on unresolved discrepancies |
| 9 | Forged mandate (edited limits) | Ed25519 signature over a canonical form; unknown issuer key is a denial | issuer key management is out of scope |
| 10 | Replayed mandate or request nonce | nonce registered per mandate, unique constraint | none known |
| 11 | Another agent uses someone else's mandate | mandate-agent binding checked in the service and again in the engine (`AGENT_MISMATCH`) | none known |
| 12 | Stolen agent API key | keys are hashed at rest and never logged; a stolen key is bounded by the mandate, not by the agent's honesty | no key rotation or revocation workflow |
| 13 | Forged webhook | HMAC over the raw body with a timestamp inside the tolerance window; event ids deduplicated in `webhook_inbox` | a leaked webhook secret allows forged events |
| 14 | Duplicate webhook double-posts | reducer dedupe by event id, ledger idempotency by business event id | none known; this is the `DOUBLE_CAPTURE_ON_REPLAY` mutant |
| 15 | Client forges an identity header through the proxy | edge strips `X-Agent-Id`, `X-Agent`, `X-User-Id`, `X-Internal`, `X-Forwarded-User` | a proxy that stops stripping them reopens this |
| 16 | One agent floods the gateway | per-credential token bucket at the edge, 429 with `Retry-After` | per process: N edges allow N times the rate |
| 17 | Oversized request body | 1 MiB cap at the edge, 413 before forwarding | none known |
| 18 | Audit log edited to hide an action | SHA-256 chain over `prevHash|actor|action|subject|detail`; verification endpoint | a full rewrite of the table and the head row is not detectable without external anchoring |
| 19 | Money created by a bad posting | ledger rejects unbalanced entries and negative non-external balances; conservation is checked in tests, the simulator and the console | none known |
| 20 | Crash between provider call and local commit | restart re-enqueues `AUTHORIZING` payments with the stored key; covered by an integration test | window is small but the mechanism is the key, not timing |

## Non-goals (stated, not implied)

- Not PCI-compliant. No card data exists here at all.
- No TLS in the demo stack; the edge expects a terminator in front.
- No multi-tenancy, no per-operator accounts: one shared admin token.
- No key rotation, revocation or HSM-backed issuer keys.
- No FX; one mandate is one currency.
- No fraud scoring, velocity rules beyond the mandate, or chargeback handling.
- No distributed rate limiting.

## The finding worth repeating

Category allowlists were evaluated against the category **the agent sent**. Any agent could type
`groceries` and pay any merchant. The integration tests all told the truth about what they were
paying, so they could not see it; the adversarial demo agent did on its first run. The fix was a
server-side merchant directory and ignoring the agent's claim for authorisation.

That is the argument for having an abuse suite at all: a test that only exercises honest clients
tests the happy path twice.
