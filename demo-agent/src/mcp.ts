/**
 * Minimal MCP client over Streamable HTTP.
 *
 * Written against the protocol, not against a vendor SDK: initialise, list tools, call a tool. The
 * transport answers either with JSON or with a single SSE event, and a session id arrives in a
 * header, so both shapes are handled here.
 */

export interface JsonRpcError {
  code: number;
  message: string;
  data?: unknown;
}

export class McpError extends Error {
  constructor(readonly code: number, message: string) {
    super(message);
  }
}

export class ToolError extends Error {
  constructor(readonly tool: string, message: string) {
    super(`tool ${tool} failed: ${message}`);
  }
}

export interface ToolCallResult {
  text: string;
  structured?: unknown;
}

export class McpClient {
  private sessionId: string | null = null;
  private nextId = 1;

  constructor(
    private readonly endpoint: string,
    private readonly apiKey: string,
  ) {}

  private headers(): Record<string, string> {
    const headers: Record<string, string> = {
      "Content-Type": "application/json",
      Accept: "application/json, text/event-stream",
      Authorization: `Bearer ${this.apiKey}`,
      // One round trip per request; a reused keep-alive socket against a streaming endpoint
      // surfaces as a closed socket instead of a response.
      Connection: "close",
    };
    if (this.sessionId) headers["Mcp-Session-Id"] = this.sessionId;
    return headers;
  }

  private async parse(response: Response): Promise<unknown> {
    const session = response.headers.get("mcp-session-id");
    if (session) this.sessionId = session;
    const contentType = response.headers.get("content-type") ?? "";
    const text = await response.text();
    if (!response.ok) {
      throw new Error(`HTTP ${response.status}: ${text.slice(0, 300)}`);
    }
    if (contentType.includes("text/event-stream")) {
      const payloads = text
        .split("\n")
        .filter((line) => line.startsWith("data:"))
        .map((line) => line.slice(5).trim())
        .filter(Boolean);
      const last = payloads.at(-1);
      return last ? JSON.parse(last) : null;
    }
    return text ? JSON.parse(text) : null;
  }

  private async rpc(method: string, params: unknown): Promise<unknown> {
    const response = await fetch(this.endpoint, {
      method: "POST",
      headers: this.headers(),
      body: JSON.stringify({ jsonrpc: "2.0", id: this.nextId++, method, params }),
    });
    const message = (await this.parse(response)) as { result?: unknown; error?: JsonRpcError } | null;
    if (message?.error) {
      throw new McpError(message.error.code, message.error.message);
    }
    return message?.result;
  }

  async initialize(): Promise<{ name: string; version: string }> {
    const result = (await this.rpc("initialize", {
      protocolVersion: "2025-06-18",
      capabilities: {},
      clientInfo: { name: "agentpaygate-demo-agent", version: "0.1.0" },
    })) as { serverInfo?: { name?: string; version?: string } };
    await fetch(this.endpoint, {
      method: "POST",
      headers: this.headers(),
      body: JSON.stringify({ jsonrpc: "2.0", method: "notifications/initialized" }),
    }).catch(() => undefined);
    return {
      name: result?.serverInfo?.name ?? "unknown",
      version: result?.serverInfo?.version ?? "unknown",
    };
  }

  async listTools(): Promise<string[]> {
    const result = (await this.rpc("tools/list", {})) as { tools?: Array<{ name: string }> };
    return (result?.tools ?? []).map((tool) => tool.name);
  }

  async callTool(tool: string, args: Record<string, unknown>): Promise<ToolCallResult> {
    const result = (await this.rpc("tools/call", { name: tool, arguments: args })) as
      | { content?: Array<{ type: string; text?: string }>; structuredContent?: unknown; isError?: boolean }
      | undefined;
    const text = result?.content?.find((part) => part.type === "text")?.text ?? "";
    if (result?.isError) {
      throw new ToolError(tool, text);
    }
    return { text, structured: result?.structuredContent };
  }
}
