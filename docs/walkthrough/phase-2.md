# Phase 2 — Payment state machine, idempotency, provider events and reconciliation

## The state machine

Twelve states, one table of legal moves (`PaymentTransitions`). Anything not in the table throws.
That is deliberate: a payment that silently accepts an impossible transition is a payment nobody
can reason about later.

The interesting states are the two "unknown" ones. When a provider call times out, we genuinely do
not know whether the money moved. So the payment stops in `AUTHORIZATION_UNKNOWN` or
`CAPTURE_UNKNOWN` and waits. There are exactly two ways out: replay the call with **the same**
provider idempotency key, or let reconciliation match the provider statement.

`Payment.providerIdempotencyKey` is set when the payment is created and never changes — including
across retries and across a gateway restart, because it is stored with the payment. A retry with a
fresh key is the classic double-charge bug, and this design makes it impossible to write by
accident.

## Idempotency at the API boundary

`IdempotencyRegistry` (the pure reference; the gateway uses a Postgres unique constraint) answers
three ways:

| Case | Outcome | Why |
|---|---|---|
| key never seen | `NEW` | first request, proceed |
| same key, same payload hash | `REPLAY` | return the stored payment, change nothing |
| same key, different payload | `CONFLICT` | reject: the client is not retrying, it is asking for something else |

The payload hash covers agent, merchant, amount and currency — the fields that decide what money
moves. A retry that changes the amount is not a retry.

## Provider events: duplicated, delayed, out of order

Webhooks arrive more than once and in any order. The reducer has three guards:

1. **Event id memory.** A repeat is a no-op (`DUPLICATE`).
2. **Progress ordering.** Each state has a happy-path index; an event targeting an earlier index
   is ignored (`STALE`), so a late "authorised" cannot undo a "captured".
3. **Transition table.** Everything else goes through `Payment.apply`, which throws instead of
   guessing.

The test `outOfOrderSequenceEndsInTheSameStateAsInOrderSequence` drives two different arrival
orders and asserts they converge on the same state and captured amount.

## Refunds

Refunds are cumulative: `sum(refunds) <= captured`. The check lives in `Payment.apply` for
`REFUND_SUCCEEDED`, so it cannot be bypassed by a caller, and the constructor re-checks the
invariant on every new instance. Partial refunds leave the payment in `REFUNDED` and a further
refund transitions `REFUNDED -> REFUNDING -> REFUNDED`. A refund that would exceed the capture
throws.

## Reconciliation

`Reconciler.reconcile(statementLines, payments)` returns every difference, sorted, with a type:

- `MISSING_INTERNAL` — the provider charged something we have no payment for;
- `MISSING_PROVIDER` — we think we captured, the statement says nothing;
- `AMOUNT_MISMATCH` — both know the charge, the amounts disagree;
- `STATUS_MISMATCH` — both know the charge, the statuses disagree.

Only one class auto-resolves: a payment in `CAPTURE_UNKNOWN` that the provider confirms as
captured for exactly the expected amount. That resolution needs no new money movement. Everything
else goes to manual review, and nothing is ever dropped.

## Properties

`PaymentProperties` drives random payment counts and amounts through a real `Ledger` and asserts
conservation, no negative non-external balances, no double capture and refunds bounded by
captures. `PaymentStateMachineTest` walks the whole 12×12 state matrix: every allowed pair must be
accepted and every other pair must throw.

## Mutation checks

`scripts/mutation-payments.sh` disables the stale guard, then removes the payload comparison. Both
mutants are killed by existing tests. Recorded output: `docs/evidence/mutation-payments.txt`.

## What you should be able to explain

- Why "unknown" is a state, not an error: an error invites a retry, and a retry after a timeout
  is how you charge twice.
- Why the provider key is stored on the payment: a process that crashes between the provider call
  and the local commit must be able to retry with the same key after restart.
- Why replays with a different payload are rejected rather than treated as a new request: that
  would let a bug (or an attacker) turn a 100-rupee retry into a 100,000-rupee payment.
- Why reconciliation flags instead of fixing: automatic repair of an amount mismatch would hide
  exactly the bug you need to see.
