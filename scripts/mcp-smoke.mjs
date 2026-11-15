#!/usr/bin/env node
// MCP smoke test: drives the gateway's MCP endpoint over Streamable HTTP the way an agent client
// does - initialise, list tools, then call them. Prints what it saw and exits non-zero on any
// protocol or tool failure.
//
//   node scripts/mcp-smoke.mjs            # uses .run/api-key.txt and http://localhost:8090
//   GATEWAY_BASE_URL=... AGENT_API_KEY=... node scripts/mcp-smoke.mjs
import { readFileSync } from "node:fs";

const base = process.env.GATEWAY_BASE_URL ?? "http://localhost:8090";
const apiKey = process.env.AGENT_API_KEY ?? readFileSync(".run/api-key.txt", "utf8").trim();
const endpoint = `${base}/mcp`;

let sessionId = null;
let nextId = 1;

function headers() {
  const h = {
    "Content-Type": "application/json",
    Accept: "application/json, text/event-stream",
    Authorization: `Bearer ${apiKey}`,
    // One round trip per request: reusing keep-alive sockets against a streaming endpoint makes
    // fetch surface a closed socket instead of the response.
    Connection: "close",
  };
  if (sessionId) h["Mcp-Session-Id"] = sessionId;
  return h;
}

// Streamable HTTP answers with either a JSON body or an SSE stream. Handle both.
async function parse(response) {
  const sid = response.headers.get("mcp-session-id");
  if (sid) sessionId = sid;
  const type = response.headers.get("content-type") ?? "";
  const text = await response.text();
  if (!response.ok) {
    throw new Error(`HTTP ${response.status} ${type}: ${text.slice(0, 400)}`);
  }
  if (type.includes("text/event-stream")) {
    const messages = text
      .split("\n")
      .filter((line) => line.startsWith("data:"))
      .map((line) => line.slice(5).trim())
      .filter(Boolean)
      .map((payload) => JSON.parse(payload));
    return messages.at(-1);
  }
  return text ? JSON.parse(text) : null;
}

async function rpc(method, params) {
  const id = nextId++;
  const response = await fetch(endpoint, {
    method: "POST",
    headers: headers(),
    body: JSON.stringify({ jsonrpc: "2.0", id, method, params }),
  });
  const message = await parse(response);
  if (message?.error) {
    throw new Error(`${method} failed: ${JSON.stringify(message.error)}`);
  }
  return message?.result;
}

async function notify(method, params) {
  const response = await fetch(endpoint, {
    method: "POST",
    headers: headers(),
    body: JSON.stringify({ jsonrpc: "2.0", method, params }),
  });
  const sid = response.headers.get("mcp-session-id");
  if (sid) sessionId = sid;
  // A notification is answered with 202 and no JSON-RPC body; drain so the socket is released.
  await response.text().catch(() => "");
  if (!response.ok && response.status !== 202) {
    throw new Error(`${method} rejected with HTTP ${response.status}`);
  }
}

async function callTool(name, args) {
  const result = await rpc("tools/call", { name, arguments: args });
  const text = result?.content?.find((part) => part.type === "text")?.text ?? "";
  if (result?.isError) throw new Error(`tool ${name} returned an error: ${text}`);
  return { structured: result?.structuredContent, text };
}

const init = await rpc("initialize", {
  protocolVersion: "2025-06-18",
  capabilities: {},
  clientInfo: { name: "apg-mcp-smoke", version: "0" },
});
console.log(`server: ${init?.serverInfo?.name} ${init?.serverInfo?.version} (session ${sessionId ? "established" : "none"})`);
await notify("notifications/initialized", {});

const tools = (await rpc("tools/list", {}))?.tools ?? [];
console.log(`tools (${tools.length}): ${tools.map((tool) => tool.name).join(", ")}`);
const expected = [
  "request_payment",
  "get_payment_status",
  "get_remaining_budget",
  "list_my_mandates",
  "describe_deny_reasons",
];
const missing = expected.filter((name) => !tools.some((tool) => tool.name === name));
if (missing.length) throw new Error(`missing tools: ${missing.join(", ")}`);

const mandates = await callTool("list_my_mandates", {});
console.log(`list_my_mandates: ${mandates.text}`);

const budget = await callTool("get_remaining_budget", { mandateId: "mandate-demo" });
console.log(`get_remaining_budget: ${budget.text}`);

const reasons = await callTool("describe_deny_reasons", {});
console.log(`describe_deny_reasons: ${reasons.structured?.length ?? "?"} codes`);

const payment = await callTool("request_payment", {
  mandateId: "mandate-demo",
  merchantId: "merchant-bookshop",
  merchantCategory: "groceries",
  amountMinor: 12345,
  currency: "INR",
  idempotencyKey: `smoke-${Date.now()}`,
});
const parsed = JSON.parse(payment.text);
console.log(`request_payment: ${payment.text}`);
if (!parsed.approved) throw new Error(`payment was not approved: ${payment.text}`);

const status = await callTool("get_payment_status", { paymentId: parsed.paymentId });
console.log(`get_payment_status: ${status.text}`);
if (!["AUTHORIZING", "CAPTURED", "CAPTURE_UNKNOWN"].includes(JSON.parse(status.text).state)) {
  throw new Error(`unexpected state after request: ${status.text}`);
}

console.log("\nMCP smoke test passed.");
