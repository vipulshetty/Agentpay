import { money, type AdminApi, type MandateRow } from "./api";
import { el, empty, moneyCell, pill, table } from "./dom";

function stat(label: string, value: Node | string): HTMLElement {
  return el("div", { className: "card" }, [
    el("div", { className: "label", text: label }),
    el("div", { className: "value" }, [typeof value === "string" ? document.createTextNode(value) : value]),
  ]);
}

function timestamp(epochSeconds: number): string {
  if (!Number.isFinite(epochSeconds) || epochSeconds <= 0) return "—";
  return new Date(epochSeconds * 1000).toISOString().replace("T", " ").replace(".000Z", "Z");
}

export async function overviewView(api: AdminApi): Promise<HTMLElement> {
  const overview = await api.overview();
  const cards = el("div", { className: "grid" }, [
    stat("open payments", String(overview.openPayments)),
    stat("captured (net)", money(overview.totalCapturedMinor, "INR")),
    stat("agents", String(overview.agents)),
    stat("unresolved discrepancies", String(overview.unresolvedDiscrepancies)),
    stat("ledger conserves", pill(overview.ledgerConserves ? "valid" : "invalid")),
    stat("no negative balances", pill(overview.noNegativeBalances ? "valid" : "invalid")),
  ]);

  const states = Object.entries(overview.paymentsByState).sort(([a], [b]) => a.localeCompare(b));
  const stateTable =
    states.length === 0
      ? empty("No payments yet. Run the demo agent or the MCP smoke script to create one.")
      : table(
          ["state", "payments"],
          states.map(([state, count]) => [pill(state), String(count)]),
        );

  const resultLine = el("div", { className: "hint" });
  const button = el("button", {
    className: "primary",
    text: "Run reconciliation",
    onClick: async () => {
      button.disabled = true;
      resultLine.textContent = "Running…";
      try {
        const run = await api.runReconciliation();
        resultLine.textContent =
          `run ${run.runId}: ${run.discrepancies} discrepancies, ` +
          `${run.autoResolved} auto-resolved, ${run.manualReview} for manual review`;
      } catch (error) {
        resultLine.textContent = error instanceof Error ? error.message : String(error);
      } finally {
        button.disabled = false;
      }
    },
  }) as HTMLButtonElement;

  return el("div", {}, [
    el("h2", { text: "Overview" }),
    cards,
    el("h2", { text: "Payments by state" }),
    stateTable,
    el("div", { className: "card" }, [button, resultLine]),
  ]);
}

export async function paymentsView(api: AdminApi, paymentId: string): Promise<HTMLElement> {
  if (paymentId) {
    return paymentDetail(api, paymentId);
  }
  const payments = await api.payments(100);
  if (payments.length === 0) {
    return el("div", {}, [el("h2", { text: "Payments" }), empty("No payments yet.")]);
  }
  const rows = payments.map((payment) => [
    el("a", { className: "mono", href: `#/payments/${payment.paymentId}`, text: payment.paymentId }),
    pill(payment.state),
    moneyCell(payment.amountMinor, payment.currency),
    payment.merchantId,
    payment.agentId,
    payment.reasonCode ? el("span", { className: "mono", text: payment.reasonCode }) : "—",
    payment.createdAt.replace("T", " ").slice(0, 19),
  ]);
  return el("div", {}, [
    el("h2", { text: `Payments (${payments.length})` }),
    el("p", { className: "hint", text: "Click a payment id for its event timeline and ledger postings." }),
    table(["payment", "state", "amount", "merchant", "agent", "reason", "created"], rows),
  ]);
}

async function paymentDetail(api: AdminApi, paymentId: string): Promise<HTMLElement> {
  const detail = await api.payment(paymentId);
  const payment = detail.payment;
  const fields = el("div", { className: "grid" }, [
    stat("state", pill(payment.state)),
    stat("amount", money(payment.amountMinor, payment.currency)),
    stat("captured", money(payment.capturedMinor, payment.currency)),
    stat("refunded", money(payment.refundedMinor, payment.currency)),
    stat("merchant", payment.merchantId),
    stat("agent", payment.agentId),
    stat("mandate", payment.mandateId),
    stat("provider ref", payment.providerRef || "—"),
  ]);

  const events = table(
    ["event", "type", "amount", "provider ref", "reason", "at"],
    detail.events.map((event) => [
      el("span", { className: "mono", text: event.event_id }),
      pill(event.type),
      event.amount_minor ? moneyCell(event.amount_minor, payment.currency) : "—",
      event.provider_ref || "—",
      event.reason_code || "—",
      String(event.occurred_at).replace("T", " ").slice(0, 19),
    ]),
  );

  const ledger = table(
    ["business event", "account", "ref", "amount"],
    detail.ledger.map((posting) => [
      el("span", { className: "mono", text: posting.business_event_id }),
      posting.account_type,
      posting.account_ref,
      moneyCell(posting.amount_minor, posting.currency),
    ]),
  );

  return el("div", {}, [
    el("p", {}, [el("a", { href: "#/payments", text: "← all payments" })]),
    el("h2", { text: payment.paymentId }),
    fields,
    el("h2", { text: "Event timeline" }),
    detail.events.length === 0 ? empty("No events recorded.") : events,
    el("h2", { text: "Ledger postings" }),
    detail.ledger.length === 0 ? empty("No postings for this payment yet.") : ledger,
  ]);
}

export async function mandatesView(api: AdminApi): Promise<HTMLElement> {
  const mandates = await api.mandates();
  if (mandates.length === 0) {
    return el("div", {}, [el("h2", { text: "Mandates" }), empty("No mandates uploaded.")]);
  }
  const rows = mandates.map((mandate: MandateRow) => [
    el("span", { className: "mono", text: String(mandate.mandate_id ?? "—") }),
    String(mandate.agent_id ?? "—"),
    String(mandate.currency ?? "—"),
    money(Number(mandate.max_total ?? 0), String(mandate.currency ?? "")),
    money(Number(mandate.remainingTotal ?? 0), String(mandate.currency ?? "")),
    String(mandate.max_per_transaction ?? "—"),
    String(mandate.max_per_day ?? "—"),
    timestamp(Number(mandate.expires_at_epoch_second ?? 0)),
  ]);
  return el("div", {}, [
    el("h2", { text: `Mandates (${mandates.length})` }),
    el("p", {
      className: "hint",
      text: "A mandate is signed by an issuer key. The gateway never trusts the agent's word for any of these numbers.",
    }),
    table(
      ["mandate", "agent", "ccy", "total", "remaining", "per tx", "per day", "expires"],
      rows,
    ),
  ]);
}

export async function auditView(api: AdminApi): Promise<HTMLElement> {
  const [verification, rows] = await Promise.all([api.auditVerify(), api.audit(100)]);
  const summary = el("div", { className: "card" }, [
    el("div", { className: "label", text: "hash chain" }),
    el("div", { className: "value" }, [
      pill(verification.valid ? "valid" : "invalid"),
      el("span", {
        className: "hint",
        text: ` ${verification.entries} entries` + (verification.valid ? "" : `, broken at seq ${verification.brokenAtSeq}`),
      }),
    ]),
  ]);
  const auditTable = table(
    ["seq", "actor", "action", "subject", "detail", "hash"],
    rows.map((row) => [
      String(row.seq),
      row.actor,
      row.action,
      el("span", { className: "mono", text: row.subject }),
      row.detail,
      el("span", { className: "mono", title: row.prev_hash, text: row.hash.slice(0, 12) + "…" }),
    ]),
  );
  return el("div", {}, [
    el("h2", { text: "Audit log" }),
    summary,
    el("p", {
      className: "hint",
      text: "Each row hashes the previous row's hash with its own fields. Editing any stored field breaks that row and every link after it.",
    }),
    rows.length === 0 ? empty("No audit entries yet.") : auditTable,
  ]);
}
