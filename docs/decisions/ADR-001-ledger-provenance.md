# ADR-001 — Ledger provenance

**Status:** accepted
**Date:** 2026-11-15

## Context

The spec allows reusing a double-entry ledger from an earlier project if the human supplies one
(pure Java, `long` minor units, EXTERNAL account, idempotency keys). No such code was present in
the workspace: the folder contained only the specification.

## Decision

`ledger-core` was written fresh against section 4.3, with:

- `long` minor units and `Math.addExact` / `Math.multiplyExact` everywhere, so overflow throws;
- an `EXTERNAL` account whose negative balance is the only one allowed;
- a business-event id as the posting idempotency key, so a retried event posts at most once;
- sorted collections (`TreeMap`/`TreeSet`) so `stateHash()` is identical across JVMs;
- no clock, no I/O, no Spring, enforced by ArchUnit plus a source scan (`DeterminismTest`).

## Consequences

There is no prior test suite to preserve, so the gate is the new jqwik property suite
(conservation, non-negative non-external balances, idempotent replay, deterministic state hash)
plus the mutation check in `scripts/mutation-sanity.sh`, which proves the properties catch a
skipped debit.
