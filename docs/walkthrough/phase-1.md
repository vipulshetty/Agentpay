# Phase 1 — Mandates and the policy engine

## The idea in one sentence

A human signs a small document that says what an agent may spend; the gateway checks every
payment against it and denies anything the document does not explicitly permit.

## The mandate

Mandate fields: `mandateId`, `agentId`, `issuerKeyId`, `currency` (INR or USD, no FX),
`maxPerTransaction`, `maxTotal`, `maxPerDay`, `allowedMerchants`, `allowedCategories`,
`notBefore`, `expiresAt`, `nonce`. Amounts are `long` minor units — paise or cents — never
floating point, because `0.1 + 0.2` is not money.

The format is AP2-inspired: it borrows the idea of a signed, limit-bearing authorisation, and it
is **not** AP2-compliant. Nothing in this project claims AP2, x402, ACP or MPP support.

## Canonical form and signature

A signature is only meaningful if both sides sign the same bytes. `CanonicalForm` writes a fixed
order, line-based encoding with a version tag, sorted allowlists, and no optional fields. Ids may
not contain `|` or newlines, so no value can smuggle a separator and change the parse.

Ed25519 signs those bytes (`MandateSignature`). Ed25519 was chosen over ECDSA because it is
deterministic: the same mandate and key always give the same signature bytes, so fixtures and
audit records can be compared byte for byte.

## The engine is a pure function

```
evaluate(signedMandate, request, spendState, context) -> Decision
```

`context` carries the evaluation instant (epoch seconds), the issuer key registry and whether the
nonce was already used. No clock, no database, no randomness: same inputs, same answer, forever.

Check order, and why it matters:

1. currency supported → 2. agent matches → 3. issuer key known and signature verifies → 4. request
currency matches mandate → 5. amount positive → 6. not expired → 7. not before validity →
8. nonce not reused → 9. merchant or category allowed → 10. per-transaction ceiling →
11. daily ceiling → 12. lifetime budget.

Signature is checked *before* any business limit, so a forged mandate cannot be used to probe
budgets. Every failure is a `DenyReason` code, and the tests assert on codes.

## Deny by default

`allowsMerchant` requires an explicit merchant id or an explicit category. A mandate with empty
allowlists denies everything — there is no "empty means all". That is the difference between a
policy enforcement point and a suggestion.

## Budget accounting

Approval *reserves*; capture converts reserved into spent; void releases. The lifetime check is
`spent + reserved + amount <= maxTotal`, and the daily check is the same over today's counters.
Checking only "spent" would let two concurrent approvals both fit into the last slice of budget.

The engine is pure, so it cannot hold a lock. The gateway (Phase 3) performs the same comparison
inside a `SELECT ... FOR UPDATE` transaction, and the simulation lab (Phase 4) drives the pure
engine to prove the arithmetic under interleavings.

## The demo tool

`mandate-cli` generates an Ed25519 issuer key pair under `secrets/` (git-ignored, demo-only) and
writes a signed mandate to `demo/mandates/`. Key generation lives there, not in `policy-core`,
because it needs a secure random source.

## Mutation check

`scripts/mutation-policy-limit.sh` changes the per-transaction boundary from `>` to `>=`, so a
payment at exactly the limit would be denied. `approvesExactlyAtThePerTransactionLimit` fails.
Recorded output: `docs/evidence/mutation-policy-limit-boundary.txt`.

## What you should be able to explain

- Why the limits live outside the model: a prompt-injected agent will happily ask for 99,999; the
  only defence that does not depend on the model behaving is a check the model cannot reach.
- Why `reserved + spent` and not `spent`: reservations are the fix for concurrency and for
  provider calls that have not resolved yet.
- Why the nonce is separate from the idempotency key: the idempotency key protects one agent's
  retries; the nonce protects against replaying a *different* request under a valid mandate.
- Why an empty allowlist denies everything: fail-closed defaults are the point.
