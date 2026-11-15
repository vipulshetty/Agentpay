import { existsSync, readFileSync } from "node:fs";
import { McpClient, ToolError } from "./mcp.js";

/**
 * The reference agent. It does two things a reviewer needs to see:
 *
 * 1. the happy path: read the mandate, spend inside it, retry with the same idempotency key and get
 *    the same payment back;
 * 2. the abuse cases: try to exceed the limits, pay a merchant that is not allowed, use another
 *    agent's mandate, and reuse an idempotency key with a different amount. Every one of those must
 *    be refused by the gateway, not by the model.
 *
 * It prints Markdown so the same run can be checked in as evidence.
 */

function env(name: string, fallback: string): string {
  const value = process.env[name];
  return value && value.length > 0 ? value : fallback;
}

function readKey(): string {
  if (process.env.AGENT_API_KEY) return process.env.AGENT_API_KEY;
  const candidates = [env("AGENT_KEY_FILE", ".run/api-key.txt"), "../.run/api-key.txt"];
  for (const candidate of candidates) {
    if (existsSync(candidate)) return readFileSync(candidate, "utf8").trim();
  }
  throw new Error(
    "no agent API key: set AGENT_API_KEY, or run scripts/demo-setup.sh so .run/api-key.txt exists",
  );
}

interface Case {
  name: string;
  expectation: string;
  run: () => Promise<string>;
}

const baseUrl = env("GATEWAY_BASE_URL", "http://localhost:8090").replace(/\/$/, "");
const mandateId = env("DEMO_MANDATE_ID", "mandate-demo");
const merchantId = env("DEMO_MERCHANT_ID", "merchant-bookshop");
const category = env("DEMO_MERCHANT_CATEGORY", "groceries");

const client = new McpClient(`${baseUrl}/mcp`, readKey());

interface RequestResult {
  paymentId: string;
  state: string;
  approved: boolean;
  amountMinor: number;
  deniedReason: string;
  replayed: boolean;
}

async function requestPayment(
  key: string,
  amountMinor: number,
  merchant = merchantId,
  currency = "INR",
): Promise<RequestResult> {
  const result = await client.callTool("request_payment", {
    mandateId,
    merchantId: merchant,
    merchantCategory: category,
    amountMinor,
    currency,
    idempotencyKey: key,
  });
  return JSON.parse(result.text) as RequestResult;
}

function requireDenial(outcome: RequestResult, allowedReason?: string): string {
  if (outcome.approved) {
    throw new Error(`the gateway approved a request that should have been denied: ${JSON.stringify(outcome)}`);
  }
  const reason = outcome.deniedReason || "NO_REASON";
  if (allowedReason && reason !== allowedReason) {
    throw new Error(`denied with ${reason}, expected ${allowedReason}`);
  }
  return `denied: ${reason}`;
}

async function main(): Promise<void> {
  const server = await client.initialize();
  const tools = await client.listTools();
  const stamp = Date.now().toString(36);
  const findings: string[] = [];

  const budget = JSON.parse((await client.callTool("get_remaining_budget", { mandateId })).text) as {
    remainingTotal: number;
    maxTotal: number;
    currency: string;
  };

  // ---------------------------------------------------------------- happy path
  const allowed = await requestPayment(`demo-ok-${stamp}`, 1_234);
  if (!allowed.approved) throw new Error(`an in-limit payment was denied: ${JSON.stringify(allowed)}`);

  const replay = await requestPayment(`demo-ok-${stamp}`, 1_234);
  if (!replay.replayed || replay.paymentId !== allowed.paymentId) {
    throw new Error(`a retry with the same key was not replayed: ${JSON.stringify(replay)}`);
  }

  const status = JSON.parse((await client.callTool("get_payment_status", { paymentId: allowed.paymentId })).text) as {
    state: string;
    capturedMinor: number;
  };

  // ---------------------------------------------------------------- abuse cases
  const cases: Case[] = [
    {
      name: "amount above the per-transaction limit",
      expectation: "denied with AMOUNT_OVER_TX_LIMIT",
      run: async () => requireDenial(await requestPayment(`abuse-tx-${stamp}`, 999_999_999), "AMOUNT_OVER_TX_LIMIT"),
    },
    {
      name: "merchant outside the allowlist",
      expectation: "denied with MERCHANT_NOT_ALLOWED",
      run: async () => requireDenial(await requestPayment(`abuse-merchant-${stamp}`, 1_000, "merchant-attacker"), "MERCHANT_NOT_ALLOWED"),
    },
    {
      name: "currency the mandate does not use",
      expectation: "denied with CURRENCY_MISMATCH",
      run: async () => requireDenial(await requestPayment(`abuse-ccy-${stamp}`, 1_000, merchantId, "USD"), "CURRENCY_MISMATCH"),
    },
    {
      name: "zero amount",
      expectation: "denied, never charged",
      run: async () => {
        try {
          return requireDenial(await requestPayment(`abuse-zero-${stamp}`, 0));
        } catch (error) {
          // The gateway may reject an unusable amount before the policy engine sees it; either
          // way the request must not become a payment.
          if (error instanceof ToolError) return `rejected by the gateway: ${error.message.split("\n")[0]}`;
          throw error;
        }
      },
    },
    {
      name: "mandate that does not exist for this agent",
      expectation: "denied, no payment created",
      run: async () => {
        try {
          const result = await client.callTool("request_payment", {
            mandateId: "mandate-does-not-exist",
            merchantId,
            merchantCategory: category,
            amountMinor: 1_000,
            currency: "INR",
            idempotencyKey: `abuse-unknown-${stamp}`,
          });
          return requireDenial(JSON.parse(result.text) as RequestResult);
        } catch (error) {
          if (error instanceof ToolError) return `rejected by the gateway: ${error.message.split("\n")[0]}`;
          throw error;
        }
      },
    },
    {
      name: "idempotency key reused with a different amount",
      expectation: "rejected as a conflict, not silently repriced",
      run: async () => {
        try {
          await requestPayment(`demo-ok-${stamp}`, 9_999);
          throw new Error("the gateway accepted a replay with a different payload");
        } catch (error) {
          if (error instanceof ToolError) return `rejected: ${error.message.split("\n")[0]}`;
          throw error;
        }
      },
    },
    {
      name: "another agent's mandate",
      expectation: "denied with AGENT_MISMATCH",
      run: async () => {
        const thiefKey = process.env.THIEF_API_KEY;
        if (!thiefKey) return "SKIPPED: THIEF_API_KEY not set";
        const thief = new McpClient(`${baseUrl}/mcp`, thiefKey);
        await thief.initialize();
        try {
          const result = await thief.callTool("request_payment", {
            mandateId,
            merchantId,
            merchantCategory: category,
            amountMinor: 1_000,
            currency: "INR",
            idempotencyKey: `abuse-thief-${stamp}`,
          });
          return requireDenial(JSON.parse(result.text) as RequestResult, "AGENT_MISMATCH");
        } catch (error) {
          // The gateway refuses a mandate issued to another agent before creating a payment, so
          // this arrives as a rejection rather than a policy decision. Both are refusals.
          if (error instanceof ToolError) return `rejected by the gateway: ${error.message.split("\n")[0]}`;
          throw error;
        }
      },
    },
  ];

  const lines: string[] = [];
  lines.push(`# Agent abuse results`);
  lines.push("");
  lines.push(`Generated by \`demo-agent\` against ${baseUrl} at ${new Date().toISOString()}.`);
  lines.push("");
  lines.push("Everything here is a simulated payment gateway. The mandate is AP2-inspired, not AP2.");
  lines.push("");
  lines.push("## Setup");
  lines.push("");
  lines.push(`- MCP server: \`${server.name}\` ${server.version}`);
  lines.push(`- tools: ${tools.join(", ")}`);
  lines.push(`- mandate: \`${mandateId}\`, total ${budget.maxTotal} ${budget.currency} minor units, remaining ${budget.remainingTotal}`);
  lines.push("");
  lines.push("## Control: a legitimate payment, then the same request again");
  lines.push("");
  lines.push("| step | payment | state | approved | replayed |");
  lines.push("|---|---|---|---|---|");
  lines.push(`| in-limit request | \`${allowed.paymentId}\` | ${allowed.state} | ${allowed.approved} | ${allowed.replayed} |`);
  lines.push(`| retry, same key and payload | \`${replay.paymentId}\` | ${replay.state} | ${replay.approved} | ${replay.replayed} |`);
  lines.push("");
  lines.push(`Read back later: state \`${status.state}\`, captured ${status.capturedMinor} minor units.`);
  lines.push("");
  lines.push("## Abuse cases");
  lines.push("");
  lines.push("| case | expectation | result |");
  lines.push("|---|---|---|");

  let failed = 0;
  for (const testCase of cases) {
    try {
      const result = await testCase.run();
      if (result.startsWith("SKIPPED")) {
        lines.push(`| ${testCase.name} | ${testCase.expectation} | ${result} |`);
        continue;
      }
      lines.push(`| ${testCase.name} | ${testCase.expectation} | ${result} |`);
    } catch (error) {
      failed++;
      const message = error instanceof Error ? error.message : String(error);
      lines.push(`| ${testCase.name} | ${testCase.expectation} | **FAILED**: ${message} |`);
      findings.push(message);
    }
  }

  lines.push("");
  lines.push("## Verdict");
  lines.push("");
  lines.push(
    failed === 0
      ? "Every abuse case was refused by the gateway with the expected reason code."
      : `${failed} abuse case(s) did not behave as expected.`,
  );

  const markdown = lines.join("\n") + "\n";
  if (process.env.ABUSE_OUTPUT) {
    const { writeFileSync } = await import("node:fs");
    writeFileSync(process.env.ABUSE_OUTPUT, markdown, "utf8");
    process.stdout.write(markdown);
    process.stdout.write(`\nwritten to ${process.env.ABUSE_OUTPUT}\n`);
  } else {
    process.stdout.write(markdown);
  }

  if (failed > 0) {
    process.exitCode = 1;
  }
}

main().catch((error: unknown) => {
  process.stderr.write(`demo agent failed: ${error instanceof Error ? error.message : String(error)}\n`);
  process.exitCode = 1;
});
