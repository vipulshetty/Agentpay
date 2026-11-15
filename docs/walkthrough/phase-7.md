# Phase 7 — Deployment

The point of this phase is not "there is a Dockerfile". It is that four separately built components
— two JVMs, a Go binary and a static bundle — start in the right order, become healthy on their own
terms, and then serve a real MCP payment through the edge.

## What was run

```
docker compose -f deploy/docker-compose.yml config -q                       # exit 0
docker compose -f deploy/docker-compose.yml --profile ui --profile monitoring config -q  # exit 0
docker compose -f deploy/docker-compose.yml up -d --build postgres psp-sim gateway edge   # exit 0
```

Result (`docs/evidence/phase-7-compose.txt`):

```
SERVICE    STATUS                    PORTS
edge       Up (healthy)              0.0.0.0:18088->8088/tcp
gateway    Up (healthy)              0.0.0.0:18080->8080/tcp
postgres   Up (healthy)              0.0.0.0:15432->5432/tcp
psp-sim    Up (healthy)              0.0.0.0:18081->8081/tcp
```

Then, through the **edge**, against the **compose** stack: `node scripts/mcp-smoke.mjs` with
`GATEWAY_BASE_URL=http://localhost:18088` — exit 0, a payment created over MCP and captured
asynchronously.

Monitoring, same stack, `--profile monitoring`:

- Prometheus target `agentpaygate-gateway` is `up`
- `agentpaygate_payments_requested_total{application="agentpaygate-gateway"}` returns `1`
- Grafana answers `200` on `/login`

That last one matters more than it looks: it proves the gateway's metrics endpoint is scraped by a
separate process, not just exposed.

## Choices worth explaining

**Images copy jars; they do not build them.** The Maven build already runs reproducibly and its jars
are the artefact under test. Building again inside an image would be a second build with a second set
of inputs, and the failure mode would be "works in CI, differs in the image".

**Health checks are real HTTP calls.** The Java images have no `curl`, so the check opens a socket
with bash's `/dev/tcp` and looks for `200`. A container that is `Starting` and one that is
`Healthy` are different states in `docker compose ps`, and the gateway waits for Postgres *and* the
provider before it starts.

**The edge is behind a normaliser, not a TLS terminator, in this stack.** The compose file exposes
plain HTTP on 18088. The walkthrough for Phase 5 says the same thing: this is not a TLS endpoint.

**Grafana gets a datasource, not a fake dashboard.** A dashboard that queries metrics nobody emits
is decoration. The datasource is provisioned; the metric names used in the evidence are the ones the
gateway actually publishes.

## Kubernetes

`deploy/k8s/agentpaygate.yaml` contains the same stack as manifests: a namespace, a demo Secret, a
ConfigMap, a Postgres StatefulSet with a volume claim, and Deployments for the provider, the gateway
and the edge with liveness/readiness probes pointed at the actuator health groups.

**These manifests are UNVERIFIED.** `kubectl` and a cluster are not installed on this machine, so
nothing in that file has been applied. It is the deployment shape, not a tested deployment, and it
is labelled that way in `docs/PROGRESS.md` rather than implied to be done.

One deliberate choice inside it: the gateway uses the `Recreate` strategy at one replica. The outbox
worker can run concurrently — it claims rows with `for update skip locked` — but "how many gateways"
is a decision that should be made on purpose rather than by default.

## Honest gaps

- The console image builds but was not started in this run (`--profile ui` was validated by
  `config`, not by `up`).
- No TLS anywhere in this stack. A gateway that talks to a real provider would need mTLS, and this
  project does not claim it.
- No resource-limit tuning was measured; the limits in the manifests are guesses sized for a laptop.
- Kubernetes manifests are unapplied and therefore unverified.
- The compose stack was verified on one machine with limited memory; it is not a load test.
