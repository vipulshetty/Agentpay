# ADR-003 — MCP over Streamable HTTP, and the Spring AI version that can serve it

Status: accepted (Phase 3)

## Context

The agent-facing interface is MCP: an agent lists tools and calls them, and the gateway decides
whether the request is allowed. The transport choice matters more than it looks, because a payment
gateway sits behind proxies and an agent may be a long-running process.

Two transports were available: the older HTTP+SSE transport (`GET /mcp/sse` for the stream,
`POST /mcp/message` for messages, session id in a query parameter) and Streamable HTTP (one
endpoint, `/mcp`, JSON or SSE responses, session id in a header).

## Decision

Serve MCP over **Streamable HTTP at `/mcp`**.

Reasons: it is a single endpoint so a proxy rule is one path instead of two; the session lives in a
header rather than a URL, so session ids do not end up in access logs; and it is the direction the
protocol is moving, so the edge proxy and the TypeScript client will not need rewriting.

Spring AI 1.1.8 is the pinned version because it registers the Streamable HTTP WebMVC
auto-configuration. Spring AI 1.0.0 does not contain that class at all: the property
`spring.ai.mcp.server.protocol=STREAMABLE` is accepted and ignored, the server comes up reporting
"Registered tools: 5", and `/mcp` answers 404 while the SSE endpoints answer normally. That
misconfiguration is silent, so the version is pinned deliberately in the parent POM and the
transport is covered by an integration test that performs a real tool call.

## Consequences

- The Go edge must not buffer responses on `/mcp`: streamable responses can be SSE streams.
- The container's `/error` dispatch must be permitted in Spring Security. With `denyAll()` covering
  it, any error inside the MCP handler was rewritten into an empty 401, which is how the 404 above
  stayed invisible for a while.
- Clients must accept both `application/json` and `text/event-stream` responses; the smoke script
  and the integration test both parse either.
