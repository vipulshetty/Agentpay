# Phase 5 — The Go edge proxy

The gateway is a Java service. Putting a small Go process in front of it is not about language
variety: the edge is the one component that must stream bytes it does not understand, reject floods
before they cost a JVM thread, and stay up while the gateway restarts.

## What the edge does

| Concern | Behaviour |
|---|---|
| streaming | `FlushInterval = -1`, so an MCP response that is an SSE stream is forwarded as it arrives |
| identity | strips `X-Agent-Id`, `X-Agent`, `X-User-Id`, `X-Internal`, `X-Forwarded-User` from client requests |
| correlation | adds `X-Request-Id` when the client did not send one, preserves the client's value when it did |
| rate limiting | token bucket per credential (bearer token digest) or peer IP, with `Retry-After` on 429 |
| body size | 1 MiB default; a declared oversize is rejected with 413 before a byte is forwarded |
| health | `/-/health` is answered by the edge itself and is never rate limited, so probes work during a flood |
| upstream failure | 502 with `{"code":"UPSTREAM_UNAVAILABLE"}` instead of a bare connection reset |
| shutdown | `SIGTERM` drains in-flight requests for up to 15 s |

It authenticates nothing. The gateway is the authority on both credentials; the edge must not be
able to become a second, weaker authority by accident.

## The tests that matter

`TestStreamsResponsesWithoutBuffering` is the one worth reading. The upstream writes one SSE event,
flushes, then blocks until the test releases it. The client must read that first event *while the
upstream is still blocked*. A proxy that buffers would deadlock the test rather than fail it slowly,
which is why the read happens in a goroutine with a timeout. This is the test that keeps
`/mcp` usable, because MCP Streamable HTTP answers with either JSON or an SSE stream.

`TestRateLimiterIsSafeUnderConcurrency` fires 200 concurrent requests at a burst of 50 and asserts
that exactly 50 are granted — the token bucket is shared state, so an off-by-one or a lost update
would show up as 49 or 51.

`TestProxiesAndStripsForgeableIdentityHeaders` exists because a proxy that forwards `X-Agent-Id`
turns "the gateway decides who you are" into "whoever sends the header decides". The assertion is
that the header never reaches the upstream, not that it is overwritten.

## Rate limiting, and its honest limit

The bucket is in memory, per process. With one edge that is exactly the intended limit. With two
edge instances the effective limit doubles, because there is no shared counter — a real deployment
would need Redis or an equivalent. That is stated here rather than discovered later.

Keys are the SHA-256 prefix of the `Authorization` header (never the token itself) or the peer IP.
One agent exhausting its budget cannot slow another: the test asserts a second credential still gets
200 while the first is at 429.

`/-/health` bypasses the limiter on purpose: a health probe that can be rate limited is a health
probe that reports an outage during an attack.

## Evidence

| Command | Result |
|---|---|
| `(cd edge-proxy && go vet ./... && go test -race ./...)` | exit 0, 11 tests (`docs/evidence/phase-5-edge.txt`) |
| `GATEWAY_BASE_URL=http://localhost:8088 node scripts/mcp-smoke.mjs` | exit 0 — the full MCP flow through the edge to the real gateway |
| 10 proxied requests, one credential, `EDGE_RATE_PER_SECOND=1 EDGE_RATE_BURST=5` | 5 × 200, 5 × 429 with `Retry-After` |
| a second credential, same window | 200 — limits are per credential |
| `./scripts/verify-all.sh` | exit 0 — Java 98 tests, Go pass, TypeScript components still ABSENT |

## Honest gaps

- No TLS. The edge speaks plain HTTP and expects a TLS terminator in front of it (the compose stack
  in the deployment phase puts it behind one). A gateway handling real money would need mTLS or a
  hardened terminator, and neither is claimed here.
- No metrics endpoint on the edge; the gateway exposes Prometheus metrics, the edge only logs.
- The limiter is per process, as described above.
- No request-body inspection: the edge does not parse MCP or OpenAPI, so it cannot reject a
  malformed tool call earlier than the gateway does.
