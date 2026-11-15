# Phase 4 — The deterministic simulation lab

The gateway's integration tests prove the plumbing. They cannot prove the *rules*, because a real
Postgres and a real HTTP hop make every run slightly different and a rare interleaving is not
something you can ask for on demand. So this phase runs the same pure cores inside a world where
time, randomness and failure are all inputs.

## What makes a run reproducible

- **A virtual clock.** `VirtualClock` only moves when the event loop moves it, and it refuses to
  move backwards. Nothing in the lab reads the machine clock.
- **A seeded generator.** `SimRandom` is SplitMix64 written out in the repository — a named
  algorithm, not a library default that could change between JDK versions.
- **Ordered collections everywhere.** Payments, charges, nonces and idempotency keys live in
  `TreeMap`/`TreeSet`, so iteration never depends on hash order.
- **A trace digest.** `Trace` records every step as JSON Lines and hashes the result. Two runs of
  the same seed have the same digest; `SimLabTest` asserts it, and it also asserts that a different
  seed produces a different one, so the test cannot pass by producing nothing.

`SimWorld` composes the real `PolicyEngine`, the real `Ledger`, the real `Payment` state machine and
the real `ProviderEventReducer`/`Reconciler`. What it fakes is only the outside world: the provider
and the network.

## The world

One funded agent (1,000,000 minor units), one signed mandate (50,000 per transaction, 300,000 per
day, 400,000 lifetime), three merchants. Each step advances virtual time and does one of four
things:

| Action | What it exercises |
|---|---|
| request a payment | policy limits, merchant allowlist, idempotency (fresh / replay / conflict), provider outcome |
| deliver a webhook | duplicate and out-of-order notifications, reducer dedupe |
| refund | the reverse posting and the spend counter |
| reconcile | the `CAPTURE_UNKNOWN` → `CAPTURED` resolution path |

The provider decides each charge from the seeded generator: 78% capture, 12% "timed out after
charging" (the response is lost but the money moved), 10% decline. It deduplicates on the
idempotency key, exactly like a real provider, which is what makes a retry safe to test.

## Invariants

Eight properties are checked against the whole world after every run:

1. `LEDGER_CONSERVES` — every entry balances; all balances including EXTERNAL sum to zero.
2. `NO_NEGATIVE_BALANCES` — no wallet or payable goes negative.
3. `NO_REFUSED_OPERATIONS` — the ledger and the state machine never reject what the model does.
4. `BUDGET_WITHIN_MANDATE` — `spent + reserved` never exceeds the mandate total.
5. `RESERVATIONS_MATCH_PAYMENTS` — reserved budget equals the sum held by open payments.
6. `LEDGER_MATCHES_PAYMENTS` — merchant payable equals captures minus refunds.
7. `WALLET_MATCHES_FUNDING` — the wallet equals funding minus captures plus refunds.
8. `IDEMPOTENCY_UNIQUE` — one key never creates two payments.

6 and 7 are the cross-checks that matter: they compare the ledger against the payment records, so a
defect that moves money without moving the payment (or the reverse) cannot hide.

## The planted-bug gauntlet

Six defects can be switched on, each a real bug class:

| Planted bug | Caught by |
|---|---|
| duplicate capture notification posts the ledger again | `LEDGER_MATCHES_PAYMENTS` |
| denied request still reserves budget | `BUDGET_WITHIN_MANDATE` |
| capture never releases its reservation | `NO_REFUSED_OPERATIONS` |
| mandate limits treated as advisory | `BUDGET_WITHIN_MANDATE` |
| refund moves the ledger but not the payment | `LEDGER_MATCHES_PAYMENTS` |
| replayed idempotency key creates a second payment | `IDEMPOTENCY_UNIQUE` |

`Gauntlet.run` searches seeds until each bug is caught, and the test fails if any bug survives every
seed. That is the honest form of the claim: not "the invariants are good", but "each of these six
defects is observably wrong within forty seeds". 40/40 clean runs stay clean, so the invariants are
not simply rejecting everything.

## Shrinking

`Shrinker` binary-searches the shortest prefix of a failing run that still fails, then re-runs one
step shorter to confirm minimality instead of assuming the failure is monotone. The test asserts the
shrunk scenario reproduces and that `steps - 1` does not, so a non-monotone case would be reported
rather than silently mis-shrunk.

## Trace export

`SimTraceMain` writes a run's trace as JSON Lines. It lives in `mandate-cli`, not in `sim`, because
`sim` is not allowed to perform I/O — the lab produces a String, the adapter writes it.

```
{"at":1700000000,"seq":0,"kind":"world.start","detail":"seed=7 funding=1000000"}
{"at":1700000028,"seq":1,"kind":"request.approved","detail":"pay-1 merchant-cafe 1587"}
{"at":1700000028,"seq":2,"kind":"charge.captured","detail":"pay-1 psp_00001"}
```

A sample is checked in at `docs/evidence/phase-4-trace-sample.jsonl`.

## Two bugs the lab found in itself

1. **`Math.addExact` in the generator.** SplitMix64 advances its state modulo 2^64, so wrapping is
   the algorithm. Using `addExact` made every run crash after a few thousand draws. Fixed in the
   generator, not in the test.
2. **A crash instead of a violation.** One planted bug left the spend counters inconsistent enough
   that a later refund made a figure negative, and the pure `SpendState` threw. A refusal by the
   cores is evidence, so `step()` now records it and `NO_REFUSED_OPERATIONS` reports it. Without
   that invariant, the lab would have crashed on a bug instead of describing it.

A design correction worth recording: the first version drew idempotency keys from a pool of eight
with random payloads, so almost every request was a *conflict* and only seven or eight payments
were ever created — the budget invariant could not be reached. Keys are now unique per request, with
an explicit retry (same key, same payload → replay) and an explicit conflict (same key, new payload
→ rejected). Random traffic that never repeats is not a test of idempotency.

## Gate evidence

| Command | Result |
|---|---|
| `./mvnw -B verify` | exit 0 — 98 tests, 0 failures, 0 skipped |
| `./scripts/sim-gauntlet.sh 40 80` | exit 0 — all six planted bugs caught, 40/40 clean (`docs/evidence/phase-4-gauntlet.txt`) |
| `java -cp ... SimTraceMain 7 30 NONE docs/evidence/phase-4-trace-sample.jsonl` | exit 0, digest `1dfa49a9…` |

## Honest gaps

- The lab is not the gateway: it has no database, no outbox, no concurrency. It tests the rules, not
  the plumbing, and the integration suite is what covers the plumbing.
- One agent, one mandate, three merchants, 80 steps. It is a model, not a load test.
- The gauntlet proves six known defect classes are observable. It does not prove the invariants
  catch defects nobody thought of.
- Shrinking reduces step count only; it does not reduce the generated amounts or the merchant mix.
