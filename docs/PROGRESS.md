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

---

```
PHASE 2: Payment state machine, idempotency, provider events and reconciliation  -  GATE: PASS
Commands run:
  ./mvnw -B verify                                       -> exit 0, tests: 79/0/0
  ./scripts/mutation-payments.sh                         -> exit 0, both mutants killed
Files added/changed: payments-core (Payment, PaymentState, PaymentTransitions, IdempotencyRegistry,
  ProviderEventReducer, Reconciler and their tests), docs/walkthrough/phase-2.md
Docs written: docs/walkthrough/phase-2.md, docs/evidence/mutation-payments.txt
Known gaps / risks:
  - PaymentTransitions uses Map.ofEntries: 12 states exceed Map.of's arity, and an immutable
    complete table is what the invariants rely on.
  - A retry with a fresh provider idempotency key is the classic double-charge bug. The key is
    stored with the payment and never regenerated; the mutation script kills a mutant that
    regenerates it.
Decisions made (ADRs): none new; ADR-002 still governs mandate signing.
```

---

```
PHASE 3: Gateway, provider simulator and the MCP surface  -  GATE: PASS
Commands run:
  ./mvnw -B verify                                       -> exit 0, tests: 91/0/0 (0 skipped)
  node scripts/mcp-smoke.mjs                             -> exit 0, 5 tools, real payment captured
  curl -s localhost:8090/v3/api-docs -o docs/api/openapi.json
                                                         -> 13 paths, OpenAPI 3.1.0
  ./scripts/demo-setup.sh                                -> exit 0, agent + mandate + funding
Test counts: ledger 14, policy 33, payments 28, sim 4, gateway integration 12.
Files added/changed: gateway-app (Flyway V1/V2, repositories, PaymentService, OutboxWorker,
  WebhookService, ReconciliationService, AdminService, admin REST API, MCP tools, security),
  psp-sim (charges, refunds, statement, failure modes, HMAC webhooks), pom.xml (spring-ai 1.1.8,
  psp-sim module), scripts/demo-setup.sh, scripts/mcp-smoke.mjs, scripts/verify-all.sh,
  docs/api/openapi.json, .gitignore
Docs written: docs/walkthrough/phase-3.md, docs/decisions/ADR-003-mcp-transport.md,
  docs/evidence/phase-3-verify.txt, docs/evidence/phase-3-mcp-smoke.txt
Known gaps / risks:
  - The integration suite needs the psp-sim jar built first; the full reactor build does that in
    order, a gateway-only build does not.
  - OpenAPI covers the admin API and webhooks. The MCP surface is described by tools/list.
  - The integration tests share one Postgres container and one Spring context, ordered, with a
    distinct agent and merchant per test. They are not isolated databases.
  - Phase 2 and Phase 3 were committed as two commits after both gates passed, because the
    working tree carried both phases during the Phase 3 debugging. Recorded rather than
    pretended otherwise.
Decisions made (ADRs): ADR-003 (MCP over Streamable HTTP; Spring AI 1.1.8 pin).
```
