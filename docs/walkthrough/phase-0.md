# Phase 0 — Foundations and hygiene

## What exists now

A Maven multi-module build with four pure Java modules (`ledger-core`, `policy-core`,
`payments-core`, `sim`), two Spring Boot module skeletons (`gateway-app`, `psp-sim`) and a
demo-only tool module (`mandate-cli`). Every pure module carries the same `DeterminismTest`.

## Why the modules are split this way

The gateway, the provider simulator and the simulation lab all need the *same* rules. If the
rules lived in the Spring application, the simulator would have to re-implement them, and a
re-implementation is not evidence about the production path. So the rules are plain Java with no
framework on the classpath, and everything else depends on them:

```
ledger-core  ←  payments-core  ←  sim
policy-core  ←  sim
```

## The purity rule and how it is enforced

Pure means: no wall clock, no threads, no unseeded randomness, no file or network I/O, no Spring.
Two mechanisms enforce it, because each catches what the other misses:

1. **ArchUnit** (`DeterminismTest.noWallClockConcurrencyIoNetworkingOrFrameworkDependencies`)
   inspects bytecode dependencies: `java.time`, `java.util.concurrent`, `java.io`, `java.nio.file`,
   `java.nio.channels`, `java.net`, `java.sql`, `org.springframework`, `jakarta.persistence`.
   It also forbids touching `Random`, `SecureRandom`, `UUID`, `Thread`, `ThreadLocal`, `Runtime`.
2. **A source scan** for tokens that bytecode cannot express, or that are easy to slip in:
   `synchronized`, `new HashMap`, `new HashSet`, `System.currentTimeMillis`, `Thread.sleep`,
   and the same package names as a second line of defence.

`java.nio.charset` is deliberately allowed: the ledger hashes its state with SHA-256 over UTF-8
bytes, and that is not I/O.

## The canary

A rule nobody has seen fire is a rule you cannot trust. `scripts/canary-phase0.sh` drops a class
into `policy-core` that calls `Instant.now()`, runs the module's tests, and expects a failure.
Recorded output: `docs/evidence/canary-phase0.txt`. It failed exactly as required.

## The ledger and its mutation check

`ledger-core` posts balanced journal entries tagged with a business event id. Posting an event
twice is a no-op (`ALREADY_POSTED`), which is how "each business event posts at most once" becomes
a property of the data structure instead of a promise from a caller.

Invariants: sum of all balances including EXTERNAL is zero; no non-EXTERNAL account is negative;
overflow throws. jqwik generates random histories (fund, capture, refund) and asserts them.

`scripts/mutation-sanity.sh` makes the ledger skip every debit line, then runs the tests. They
fail — a skipped debit breaks conservation. Recorded output:
`docs/evidence/mutation-ledger-skip-debit.txt`.

## What you should be able to explain

- Why the cores have no `Instant.now()`: a simulation that reads the real clock cannot be
  replayed, and a rule that reads the clock is not a pure function of its inputs.
- Why the canary exists: it proves the enforcement mechanism actually fails closed, instead of
  silently allowing a violation because a package name was misspelled in the rule.
- Why the ledger uses sorted maps: iteration order must not depend on hash codes, or the exported
  state hash would differ between JVMs for the same history.
- Why the mutation check exists: passing tests prove the code satisfies the tests, not that the
  tests would notice a broken invariant.
