# Progress log

Every entry records the commands actually run and their exit codes. Anything not run is marked
UNVERIFIED. Numbers come from the command output, never from memory.

---

```
PHASE 0: Foundations and hygiene  -  GATE: PASS
Commands run:
  git init && git rev-parse --show-toplevel              -> /run/media/vipul/New Volume/PROJECTS/AgentPayGate
  ./mvnw -B verify                                       -> exit 0, tests: 29/0/0
  ./scripts/canary-phase0.sh                             -> exit 0, canary build failed as required
  ./scripts/mutation-sanity.sh                           -> exit 0, ledger mutant killed
Files added/changed: pom.xml, mvnw, .mvn/, .gitignore, ledger-core, policy-core (skeleton),
  payments-core (skeleton), sim (skeleton), gateway-app, psp-sim, mandate-cli, scripts/,
  .github/workflows/ci.yml
Docs written: docs/walkthrough/phase-0.md, docs/decisions/ADR-001-ledger-provenance.md,
  docs/evidence/canary-phase0.txt, docs/evidence/mutation-ledger-skip-debit.txt
Known gaps / risks:
  - CI workflow is UNVERIFIED: no GitHub runner ran it (the repo has no remote).
  - The workspace is on an ntfs3 volume, not the Linux filesystem the spec asks for. The user
    explicitly chose "stay here, git init in this folder" when asked. Toolchain binaries run
    from this mount (verified with an executable probe), but file permission bits are not
    POSIX-native, so this is a recorded deviation, not a satisfied rule.
  - k6, kubectl and kind are not installed yet; those phases are not claimed.
Decisions made (ADRs): ADR-001
```

---

```
PHASE 1: policy-core and mandates  -  GATE: PASS
Commands run:
  ./mvnw -B verify                                       -> exit 0, tests: 54/0/0
  ./scripts/mutation-policy-limit.sh                     -> exit 0, boundary mutant killed
Files added/changed: policy-core (Mandate, CanonicalForm, MandateSignature, PolicyEngine,
  DenyReason, Decision, SpendState, PolicyContext, IssuerKeys, SignedMandate, PaymentRequest),
  mandate-cli, pom.xml (module added)
Docs written: docs/walkthrough/phase-1.md, docs/decisions/ADR-002-mandate-signatures.md,
  docs/evidence/mutation-policy-limit-boundary.txt
Known gaps / risks:
  - Phase 0 and Phase 1 landed in one commit because the parent POM gained the mandate-cli
    module in Phase 1; splitting the history would have produced a commit that does not build.
    Recorded rather than hidden.
  - Every deny reason has a unit test (21 PolicyEngineTest cases plus 5 jqwik properties).
Decisions made (ADRs): ADR-002
```
