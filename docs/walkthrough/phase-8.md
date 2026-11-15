# Phase 8 — Load, and the documentation set

## Load

k6 is not installed on the build machine, so `load/load.mjs` is a small closed-loop Node client
instead. It drives real `request_payment` calls over MCP with unique idempotency keys, so each request
exercises the edge (when pointed at it), the HTTP layer, policy evaluation, the Postgres transaction,
the outbox enqueue and the MCP response.

Two runs, same 200 requests at concurrency 8, on one laptop with a shared Postgres container:

| Target | Result |
|---|---|
| edge (`:18088`, `EDGE_RATE_BURST=40`) | 75 × 200, **125 × 429**, exit 1 |
| gateway direct (`:18080`) | **200 × 200**, 81 req/s, p50 96 ms, p95 158 ms, p99 186 ms, exit 0 |

The first run is not a failure of the gateway; it is the edge doing its job. With a burst of 40 and a
refill of 20/s, eight concurrent clients saturate the bucket in under a second. The evidence file
records both runs with that explanation, because a number without its configuration is a rumour.

What this does not measure: the provider call and the webhook path, which happen asynchronously in
the outbox worker. It is a demonstration of behaviour under concurrency, not a capacity claim.

## The documentation set

| Document | Answers |
|---|---|
| `README.md` | what this is, how to run it, what was verified |
| `docs/architecture.md` | components, request path, data model, why the cores are pure |
| `docs/threat-model.md` | assets, trust boundaries, 20 threats with mitigations and residual risk |
| `docs/invariants.md` | every invariant, where it is enforced, how it is tested |
| `docs/fault-model.md` | what fails, what the system does, what is *not* covered |
| `docs/determinism.md` | the purity rules, how they are proven to fire, how the lab stays reproducible |
| `docs/ABUSE-RESULTS.md` | the generated abuse run |
| `docs/PROGRESS.md` | per phase: commands, exit codes, files, known gaps |

Every document distinguishes what was run from what was not. The Kubernetes manifests, for example,
are labelled UNVERIFIED because no cluster exists on this machine.

## Final verification

`./scripts/verify-all.sh` — exit 0:

```
PASS  Java build: exit 0 (tests: 98 run, 0 failed, 0 skipped)
PASS  Go edge-proxy: go vet ./... && go test -race ./...
PASS  TypeScript console
PASS  TypeScript demo-agent
RESULT: PASS
```

Plus the things it does not run, each with its own evidence file: the simulator gauntlet, the MCP
smoke over the real stack, the compose stack through the edge, and the load runs.

## Honest gaps across the project

- Kubernetes manifests unapplied; no cluster available.
- No TLS anywhere in the demo stack.
- The console has no automated tests; it is type-checked, built, and was loaded by hand.
- Postgres failure injection is argued from transaction boundaries, not tested.
- Multi-instance gateway operation is designed for (row locks, skip-locked claims) but never run
  with more than one instance.
- The audit chain detects edits to the table; it does not detect a rewrite of the table *and* the
  head row, because there is no external anchor.
- All performance numbers come from one laptop with a shared Postgres container.
