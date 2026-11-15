# Determinism

The pure cores (`ledger-core`, `policy-core`, `payments-core`, `sim`) must be reproducible: the same
inputs produce the same output, on any machine, forever. That is what makes a policy decision
reviewable, a state machine testable, and a simulator failure shrinkable to a seed.

## The rules

Forbidden in those modules, enforced by ArchUnit over the compiled classes:

- any dependency on `java.time..`, `java.util.concurrent..`, `java.io..`, `java.nio.file..`,
  `java.net..`, `java.sql..`, `javax.sql..`, `org.springframework..`, `jakarta.persistence..`
- any access to `java.util.Random`, `java.security.SecureRandom`, `java.util.UUID`, `java.lang.Thread`,
  `java.lang.ThreadLocal`, `java.lang.Runtime`

And a source-level scan for tokens that would smuggle non-determinism in: `System.currentTimeMillis`,
`System.nanoTime`, `Thread.sleep`, `Math.random`, `synchronized`, `new HashMap`, `new HashSet`.

Why `HashMap`/`HashSet` too: iteration order there depends on hash codes, which is deterministic for
strings in one JVM but is not a property worth relying on when the output is a hash of the run.

## How "the rules fire" is proven

A rule that has never failed is a rule nobody knows works. `scripts/canary-phase0.sh` compiles a
canary class that deliberately uses a clock and asserts the build fails. The output is checked in at
`docs/evidence/canary-phase0.txt`. The mutation scripts do the same for the invariants: they plant a
defect in the ledger and in the policy limit and assert the test suite kills it.

## The simulator

`SimWorld` composes the real cores with:

- `VirtualClock`: time moves only when the loop moves it, and it refuses to move backwards.
- `SimRandom`: SplitMix64 written in the repository, seeded by the run's seed. Not `java.util.Random`,
  whose algorithm is deliberately unspecified across versions.
- ordered collections only (`TreeMap`, `TreeSet`), so iteration is by key, not by hash.
- a fixed Ed25519 issuer key generated at class initialisation: the key is random per JVM but is not
  part of any digest, so it cannot affect reproducibility.

`Trace` records every step as JSON Lines and hashes the result. The test asserts:

- same seed twice → identical trace and identical digest;
- a different seed → a different digest, so the assertion cannot pass by producing nothing;
- 40 seeds of 80 steps with no planted defect → no invariant violated, and payments were actually
  created.

## Shrinking

`Shrinker` binary-searches the shortest failing prefix and then **verifies minimality** by re-running
one step shorter. If a failure were non-monotone in step count, the search could miss the true
minimum; the verification step reports that rather than silently trusting the search.

## What determinism does not cover

- The gateway is not deterministic and is not meant to be: it has a clock, threads, a database and a
  network. Its determinism claim is narrower and stronger: the *decision* it makes is computed by a
  pure function, and the ledger it writes is checked for conservation after every change.
- The simulator models one agent and three merchants. Determinism is not coverage.
