#!/usr/bin/env node
/**
 * Load generator for the MCP payment path.
 *
 * k6 is not installed on the build machine, so this is a small Node client instead: fixed
 * concurrency, a fixed number of requests, and latency percentiles computed from the measured
 * wall-clock times. It drives a real `request_payment` over MCP Streamable HTTP, so the measured
 * path includes the edge (when pointed at it), HTTP, policy evaluation, Postgres writes, the
 * outbox enqueue and the response.
 *
 *   GATEWAY_BASE_URL=http://localhost:18088 AGENT_API_KEY=... node load/load.mjs
 *   REQUESTS=300 CONCURRENCY=10 node load/load.mjs
 *
 * Results are printed as one JSON object and a short table. Nothing is written to disk, so a run
 * cannot leave state behind except the payments it created — which is the point: it is a write
 * path, not a read path.
 */
import { readFileSync } from "node:fs";

const baseUrl = (process.env.GATEWAY_BASE_URL ?? "http://localhost:8080").replace(/\/$/, "");
const requests = Number(process.env.REQUESTS ?? 200);
const concurrency = Number(process.env.CONCURRENCY ?? 8);
const mandateId = process.env.DEMO_MANDATE_ID ?? "mandate-demo";
const merchantId = process.env.DEMO_MERCHANT_ID ?? "merchant-bookshop";
const currency = process.env.DEMO_CURRENCY ?? "INR";
const amountMinor = Number(process.env.DEMO_AMOUNT_MINOR ?? 500);

function readKey() {
  if (process.env.AGENT_API_KEY) return process.env.AGENT_API_KEY;
  const candidates = [process.env.AGENT_KEY_FILE, ".run/api-key.txt", "../.run/api-key.txt"].filter(Boolean);
  for (const candidate of candidates) {
    try {
      return readFileSync(candidate, "utf8").trim();
    } catch {
      // try the next candidate
    }
  }
  throw new Error("no agent API key: set AGENT_API_KEY or AGENT_KEY_FILE");
}

const apiKey = readKey();

if (!Number.isFinite(requests) || requests < 1) throw new Error("REQUESTS must be a positive number");
if (!Number.isFinite(concurrency) || concurrency < 1) throw new Error("CONCURRENCY must be a positive number");

const endpoint = `${baseUrl}/mcp`;
let sessionId = null;

async function post(body) {
  const headers = {
    "Content-Type": "application/json",
    Accept: "application/json, text/event-stream",
    Authorization: `Bearer ${apiKey}`,
    Connection: "close",
  };
  if (sessionId) headers["Mcp-Session-Id"] = sessionId;
  const started = performance.now();
  const response = await fetch(endpoint, { method: "POST", headers, body: JSON.stringify(body) });
  const sid = response.headers.get("mcp-session-id");
  if (sid) sessionId = sid;
  const text = await response.text();
  const elapsed = performance.now() - started;
  if (!response.ok) {
    return { ok: false, status: response.status, elapsed, text: text.slice(0, 200) };
  }
  const json = text.trimStart().startsWith("{")
    ? JSON.parse(text)
    : (() => {
        const data = text
          .split("\n")
          .filter((line) => line.startsWith("data:"))
          .map((line) => line.slice(5).trim())
          .filter(Boolean);
        return data.length ? JSON.parse(data.at(-1)) : null;
      })();
  return { ok: true, status: response.status, elapsed, json };
}

async function initialize() {
  const result = await post({
    jsonrpc: "2.0",
    id: 1,
    method: "initialize",
    params: { protocolVersion: "2025-06-18", capabilities: {}, clientInfo: { name: "apg-load", version: "0" } },
  });
  if (!result.ok) throw new Error(`initialize failed: HTTP ${result.status} ${result.text}`);
  await post({ jsonrpc: "2.0", method: "notifications/initialized" });
  return result.json?.result?.serverInfo?.name ?? "unknown";
}

function percentile(sorted, p) {
  if (sorted.length === 0) return 0;
  const index = Math.min(sorted.length - 1, Math.max(0, Math.ceil((p / 100) * sorted.length) - 1));
  return sorted[index];
}

async function main() {
  const serverName = await initialize();
  const startedAt = performance.now();
  const latencies = [];
  const statuses = new Map();
  const errors = [];
  let approved = 0;

  let issued = 0;
  async function worker(workerIndex) {
    while (true) {
      const index = issued++;
      if (index >= requests) return;
      const id = `load-${Date.now().toString(36)}-${workerIndex}-${index}`;
      try {
        const result = await post({
          jsonrpc: "2.0",
          id: index + 2,
          method: "tools/call",
          params: {
            name: "request_payment",
            arguments: {
              mandateId,
              merchantId,
              merchantCategory: "groceries",
              amountMinor,
              currency,
              idempotencyKey: id,
            },
          },
        });
        latencies.push(result.elapsed);
        statuses.set(result.status, (statuses.get(result.status) ?? 0) + 1);
        const text = result.json?.result?.content?.find((part) => part.type === "text")?.text ?? "";
        if (text.includes('"approved":true')) approved++;
        else if (errors.length < 5) errors.push(text.slice(0, 160) || `HTTP ${result.status}`);
      } catch (error) {
        latencies.push(performance.now() - startedAt);
        statuses.set("error", (statuses.get("error") ?? 0) + 1);
        if (errors.length < 5) errors.push(String(error).slice(0, 160));
      }
    }
  }

  await Promise.all(Array.from({ length: Math.min(concurrency, requests) }, (_, index) => worker(index)));

  const totalMs = performance.now() - startedAt;
  const sorted = [...latencies].sort((a, b) => a - b);
  const summary = {
    server: serverName,
    endpoint,
    requests,
    concurrency,
    approved,
    throughputPerSecond: Number((requests / (totalMs / 1000)).toFixed(2)),
    latencyMs: {
      min: Number(sorted[0].toFixed(1)),
      p50: Number(percentile(sorted, 50).toFixed(1)),
      p95: Number(percentile(sorted, 95).toFixed(1)),
      p99: Number(percentile(sorted, 99).toFixed(1)),
      max: Number(sorted.at(-1).toFixed(1)),
    },
    statuses: Object.fromEntries([...statuses.entries()].sort()),
    errors,
  };

  console.log(JSON.stringify(summary, null, 2));
  if (approved !== requests) {
    process.exitCode = 1;
  }
}

main().catch((error) => {
  console.error(`load run failed: ${error instanceof Error ? error.message : String(error)}`);
  process.exitCode = 1;
});
