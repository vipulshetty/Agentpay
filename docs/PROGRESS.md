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

---

```
PHASE 4: Deterministic simulation lab  -  GATE: PASS
Commands run:
  ./mvnw -B verify                                       -> exit 0, tests: 98/0/0 (0 skipped)
  ./scripts/sim-gauntlet.sh 40 80                        -> exit 0, 6/6 planted bugs caught
  java -cp ... SimTraceMain 7 30 NONE docs/evidence/phase-4-trace-sample.jsonl
                                                         -> exit 0, traceDigest 1dfa49a9..., 42 steps
Test counts: ledger 14, policy 33, payments 28, sim 11, gateway integration 12.
Files added/changed: sim (VirtualClock already present; SimRandom, SimConfig, PlantedBug, Trace,
  Invariant, SimWorld, Simulation, Gauntlet, Shrinker, SimLabTest), mandate-cli (sim dependency,
  SimGauntletMain, SimTraceMain), scripts/sim-gauntlet.sh
Docs written: docs/walkthrough/phase-4.md, docs/evidence/phase-4-gauntlet.txt,
  docs/evidence/phase-4-verify.txt, docs/evidence/phase-4-trace-sample.jsonl
Known gaps / risks:
  - The lab models the rules, not the gateway: no database, no outbox, no concurrency. The
    integration suite covers the plumbing.
  - The gauntlet proves six known defect classes are observable within 40 seeds. It does not
    prove the invariant set catches defects nobody has thought of.
  - Two defects were found in the lab itself during this phase (an overflow in the generator, and
    a crash instead of a violation when a planted bug made a spend figure negative). Both were
    fixed in the lab's code and are described in docs/walkthrough/phase-4.md.
Decisions made (ADRs): none new.
```

---

```
PHASE 5: Go edge proxy  -  GATE: PASS
Commands run:
  (cd edge-proxy && go vet ./... && go test -race ./...)  -> exit 0, 11 tests
  ./scripts/verify-all.sh                                -> exit 0 (Java 98/0/0, Go PASS)
  GATEWAY_BASE_URL=http://localhost:8088 node scripts/mcp-smoke.mjs
                                                         -> exit 0, MCP end-to-end through the edge
  edge with EDGE_RATE_PER_SECOND=1 EDGE_RATE_BURST=5, 10 requests one credential
                                                         -> 5 x 200, 5 x 429 with Retry-After
Files added/changed: edge-proxy (go.mod, config.go, proxy.go, ratelimit.go, main.go, proxy_test.go),
  scripts/verify-all.sh already ran the Go gates, .gitignore (edge-proxy/bin/)
Docs written: docs/walkthrough/phase-5.md, docs/evidence/phase-5-edge.txt,
  docs/evidence/phase-5-verify-all.txt
Known gaps / risks:
  - No TLS at the edge: it expects a TLS terminator in front, which the deployment phase provides.
  - The rate limiter is per process, so N edge instances allow N times the intended rate.
  - No metrics endpoint on the edge; it logs, the gateway measures.
  - The edge authenticates nothing by design; the gateway remains the only authority.
Decisions made (ADRs): none new.
```

---

```
PHASE 6: TypeScript console and demo agent  -  GATE: PASS
Commands run:
  (cd console && npm run --silent check && npm run build)
                                                         -> exit 0, 9.76 kB JS, 3.02 kB CSS
  (cd demo-agent && npm run --silent check && npm run build)
                                                         -> exit 0
  node demo-agent/dist/main.js (ABUSE_OUTPUT=docs/ABUSE-RESULTS.md)
                                                         -> exit 0, 7/7 abuse cases refused
  ./scripts/verify-all.sh                                -> exit 0, all four components PASS
Files added/changed: console (Vite + TypeScript, four views, admin API client), demo-agent
  (MCP client, control case, seven abuse cases), scripts/demo-setup.sh (merchant registration),
  gateway-app: V3__merchant_directory.sql, MerchantRepository, POST/GET /admin/merchants,
  PaymentService resolves the category server-side, GatewayIT registers merchants
Docs written: docs/walkthrough/phase-6.md, docs/ABUSE-RESULTS.md,
  docs/evidence/phase-6-verify-all.txt
Known gaps / risks:
  - The demo agent found a real defect: category rules were enforced against the category the
    agent claimed, so a category allowlist could be bypassed by any agent that typed the allowed
    category. Fixed with a server-side merchant directory; the abuse case now denies. The first
    run of the suite is what surfaced it, which is why the suite exists.
  - The console has no automated tests: it is type-checked and built, and was loaded by hand.
  - The abuse suite is seven fixed cases, not a fuzzer.
Decisions made (ADRs): none new; the merchant-directory requirement is recorded here and in
  docs/walkthrough/phase-6.md.
```

---

```
PHASE 7: Deployment  -  GATE: PASS for compose, UNVERIFIED for Kubernetes
Commands run:
  docker compose -f deploy/docker-compose.yml config -q                       -> exit 0
  docker compose -f deploy/docker-compose.yml --profile ui --profile monitoring config -q
                                                                              -> exit 0
  docker compose -f deploy/docker-compose.yml up -d --build postgres psp-sim gateway edge
                                                                              -> exit 0, 4 containers healthy
  ./scripts/compose-smoke.sh                                                  -> exit 0
  GATEWAY_BASE_URL=http://localhost:18088 node scripts/mcp-smoke.mjs          -> exit 0
  prometheus target agentpaygate-gateway: up; metric scraped; grafana /login 200
  (kubectl / kind not installed: deploy/k8s/*.yaml is UNVERIFIED, never applied)
Files added/changed: deploy/Dockerfile.{gateway,psp,edge,console}, deploy/nginx.conf,
  deploy/docker-compose.yml, deploy/prometheus/prometheus.yml, grafana datasource provisioning,
  deploy/k8s/agentpaygate.yaml, deploy/k8s/kustomization.yaml, scripts/compose-smoke.sh,
  .dockerignore
Docs written: docs/walkthrough/phase-7.md, docs/evidence/phase-7-compose.txt
Known gaps / risks:
  - Kubernetes manifests are written but never applied; no cluster exists on this machine.
  - The console image was validated by `config`, not started.
  - No TLS in the stack; the edge expects a terminator in front.
  - Resource limits are laptop-sized guesses, not measured.
Decisions made (ADRs): none new.
```

---

```
PHASE 8: Load test and documentation set  -  GATE: PASS
Commands run:
  GATEWAY_BASE_URL=http://localhost:18088 REQUESTS=200 CONCURRENCY=8 node load/load.mjs
                                                         -> exit 1 (edge rate limit: 75 x 200, 125 x 429)
  GATEWAY_BASE_URL=http://localhost:18080 REQUESTS=200 CONCURRENCY=8 node load/load.mjs
                                                         -> exit 0 (200/200 approved, p50 96 ms, p95 158 ms)
Files added/changed: load/load.mjs, load/README.md, README.md, docs/architecture.md,
  docs/threat-model.md, docs/invariants.md, docs/fault-model.md, docs/determinism.md,
  docs/walkthrough/phase-8.md, .github/workflows/ci.yml (failsafe reports counted, compose smoke)
Docs written: README.md and the five design documents above, docs/evidence/phase-8-load.txt
Known gaps / risks:
  - k6 is not installed; the load generator is a Node script. It measures the MCP request path,
    not the asynchronous provider/webhook path.
  - All numbers come from one laptop with a shared Postgres container.
  - The CI workflow is still UNVERIFIED: the repository has no remote, so no runner has executed it.
Decisions made (ADRs): none new.
```

---

```
PROJECT STATUS (as of this log)

Verified by a command in this repository:
  - 98 Java tests, 0 skipped, including 12 integration tests against real Postgres and a real
    psp-sim container.
  - Go edge: go vet + go test -race.
  - TypeScript console and demo-agent: tsc --noEmit, console vite build.
  - Simulation gauntlet: 6/6 planted bugs caught, 40/40 clean runs.
  - MCP end to end: locally, through the edge, and on the compose stack.
  - Compose stack: 4 services built and healthy; Prometheus scrapes the gateway; Grafana serves.
  - Seven abuse cases refused with the expected reason codes.
  - Load: 200/200 approved at concurrency 8 on the gateway path.

Not verified, and not claimed:
  - Kubernetes manifests (no cluster).
  - CI workflow (no remote, never executed).
  - TLS, multi-instance gateways, Postgres fault injection, console UI tests, accessibility.
```
