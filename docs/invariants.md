# Invariants

An invariant is a property that must hold at every observable point, not a behaviour that happens to
work in a test. Each one below is enforced in code, and covered by at least one of: a unit test, a
jqwik property, an integration test against Postgres, or the simulator.

## Ledger

| Invariant | Enforced by | Tested by |
|---|---|---|
| Every journal entry sums to zero | `JournalEntry` constructor | `LedgerTest`, `DeterminismTest`, simulator `LEDGER_CONSERVES` |
| All balances including EXTERNAL sum to exactly zero | `Ledger.post` validates on a copy first | `LedgerTest`, `/admin/overview` reports it live, simulator |
| No non-external account goes negative | `Ledger.post` throws | `LedgerTest`, simulator `NO_NEGATIVE_BALANCES` |
| A business event posts at most once | `Ledger.postedEvents` | `LedgerTest`, integration replay test |
| Balance arithmetic cannot overflow silently | `Math.addExact` in every mutation | `LedgerTest` |

## Payments

| Invariant | Enforced by | Tested by |
|---|---|---|
| Only listed transitions are legal | `PaymentTransitions` + `Payment.apply` throws | `PaymentStateMachineTest` (all 12 states), jqwik properties |
| `captured <= authorised` | `Payment` constructor and `apply` | `PaymentStateMachineTest` |
| `refunded <= captured` | `Payment` constructor and `apply` | `PaymentStateMachineTest`, simulator `REFUNDS_WITHIN_CAPTURES` |
| A payment never moves backwards | progress index + `PaymentTransitions` | `ProviderEventReducerTest` |
| The provider idempotency key never changes | assigned once in `Payment.requested`, stored in the row | integration crash-recovery test, mutation script |

## Policy

| Invariant | Enforced by | Tested by |
|---|---|---|
| Deny by default: anything not explicitly allowed is denied | `PolicyEngine` check order | `PolicyEngineTest` (one case per reason code) |
| `spent + reserved <= maxTotal` | `PolicyEngine` before approval | `PolicyEngineTest`, jqwik properties, simulator `BUDGET_WITHIN_MANDATE` |
| `committedToday + amount <= maxPerDay` | `PolicyEngine` | `PolicyEngineTest` |
| A mandate belongs to exactly one agent | service check and `AGENT_MISMATCH` | integration test, demo agent case |
| A nonce is used at most once | `mandate_nonces` unique constraint, register-then-check | integration test |
| A mandate's limits cannot be edited without invalidating the signature | canonical form + Ed25519 verify | `MandateSignatureTest` (tamper cases) |
| A category rule is evaluated against a trusted category | `MerchantRepository` lookup in `PaymentService` | integration tests, abuse case |

## Gateway and operations

| Invariant | Enforced by | Tested by |
|---|---|---|
| One idempotency key never creates two payments | unique `(agent_id, idempotency_key)` + payload hash | integration replay and conflict tests, simulator `IDEMPOTENCY_UNIQUE` |
| An audit row cannot be edited undetected | hash chain + head row lock | `auditChainFailsAfterATamperedRow` |
| A duplicate webhook cannot double-charge | reducer dedupe + ledger business-event id | integration webhook tests, simulator `DOUBLE_CAPTURE_ON_REPLAY` |
| A crashed provider call is never lost | restart re-enqueues `AUTHORIZING` payments with the stored key | `crashBetweenProviderCallAndCommitIsRecoveredWithoutDoubleCharging` |
| Money in the ledger equals money in the payment records | reconciliation + cross-checks | simulator `LEDGER_MATCHES_PAYMENTS`, `WALLET_MATCHES_FUNDING` |
| No operation is refused by the pure cores in a clean run | state machine + ledger validation | simulator `NO_REFUSED_OPERATIONS` |

## How the simulator proves the invariants are not decorative

`Gauntlet` runs six planted defects and requires each to be caught:

| Planted defect | Invariant that catches it |
|---|---|
| duplicate capture posts again | `LEDGER_MATCHES_PAYMENTS` |
| denied request reserves budget | `BUDGET_WITHIN_MANDATE` |
| capture never releases its reservation | `NO_REFUSED_OPERATIONS` |
| limits treated as advisory | `BUDGET_WITHIN_MANDATE` |
| refund moves the ledger but not the payment | `LEDGER_MATCHES_PAYMENTS` |
| replayed key creates a second payment | `IDEMPOTENCY_UNIQUE` |

If a defect survived every seed, the test fails. That is a claim about the invariant set, not about
the simulator's own correctness — see `docs/walkthrough/phase-4.md` for the two bugs the lab found in
itself.
