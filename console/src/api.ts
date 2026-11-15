/**
 * Typed client for the operator API.
 *
 * The admin token is supplied by the operator at runtime and kept in sessionStorage: it is a
 * demonstration console, and a token baked into a bundle would be a token in every browser cache.
 */

export interface Overview {
  paymentsByState: Record<string, number>;
  totalCapturedMinor: number;
  unresolvedDiscrepancies: number;
  ledgerConserves: boolean;
  noNegativeBalances: boolean;
  agents: number;
  openPayments: number;
}

export interface PaymentSummary {
  paymentId: string;
  agentId: string;
  mandateId: string;
  merchantId: string;
  currency: string;
  amountMinor: number;
  state: string;
  providerRef: string;
  reasonCode: string;
  createdAt: string;
}

export interface PaymentEvent {
  event_id: string;
  type: string;
  amount_minor: number;
  provider_ref: string;
  reason_code: string;
  occurred_at: string;
}

export interface LedgerPosting {
  business_event_id: string;
  posting_index: number;
  account_type: string;
  account_ref: string;
  amount_minor: number;
  currency: string;
}

export interface PaymentDetail {
  payment: PaymentSummary & {
    merchantCategory: string;
    capturedMinor: number;
    refundedMinor: number;
    updatedAt: string;
  };
  events: PaymentEvent[];
  ledger: LedgerPosting[];
}

export interface MandateRow {
  mandate_id?: string;
  agent_id?: string;
  currency?: string;
  max_total?: number;
  max_per_transaction?: number;
  max_per_day?: number;
  remainingTotal?: number;
  expires_at_epoch_second?: number;
  [key: string]: unknown;
}

export interface AuditRow {
  seq: number;
  actor: string;
  action: string;
  subject: string;
  detail: string;
  hash: string;
  prev_hash: string;
}

export interface AuditVerification {
  valid: boolean;
  entries: number;
  brokenAtSeq: string;
}

export interface Discrepancy {
  type: string;
  providerRef?: string;
  paymentId?: string;
  detail?: string;
  autoResolvable?: boolean;
  [key: string]: unknown;
}

export interface ReconciliationRun {
  runId: string;
  discrepancies: number;
  autoResolved: number;
  manualReview: number;
}

export class ApiError extends Error {
  constructor(readonly status: number, readonly code: string, message: string) {
    super(message);
  }
}

export class AdminApi {
  constructor(
    private readonly baseUrl: string,
    private readonly token: string,
  ) {}

  private async request<T>(path: string, init?: RequestInit): Promise<T> {
    const response = await fetch(`${this.baseUrl}${path}`, {
      ...init,
      headers: {
        "X-Admin-Token": this.token,
        "Content-Type": "application/json",
        ...(init?.headers ?? {}),
      },
    });
    if (!response.ok) {
      let code = "HTTP_" + response.status;
      let message = response.statusText;
      try {
        const body = (await response.json()) as { code?: string; message?: string };
        code = body.code ?? code;
        message = body.message ?? message;
      } catch {
        // Non-JSON error body: keep the status text.
      }
      throw new ApiError(response.status, code, message);
    }
    if (response.status === 204) {
      return undefined as T;
    }
    return (await response.json()) as T;
  }

  overview(): Promise<Overview> {
    return this.request<Overview>("/admin/overview");
  }

  payments(limit = 50): Promise<PaymentSummary[]> {
    return this.request<PaymentSummary[]>(`/admin/payments?limit=${limit}`);
  }

  payment(paymentId: string): Promise<PaymentDetail> {
    return this.request<PaymentDetail>(`/admin/payments/${encodeURIComponent(paymentId)}`);
  }

  mandates(): Promise<MandateRow[]> {
    return this.request<MandateRow[]>("/admin/mandates");
  }

  audit(limit = 50): Promise<AuditRow[]> {
    return this.request<AuditRow[]>(`/admin/audit?limit=${limit}`);
  }

  auditVerify(): Promise<AuditVerification> {
    return this.request<AuditVerification>("/admin/audit/verify");
  }

  reconciliation(): Promise<Discrepancy[]> {
    return this.request<Discrepancy[]>("/admin/reconciliation");
  }

  runReconciliation(): Promise<ReconciliationRun> {
    return this.request<ReconciliationRun>("/admin/reconciliation/run", { method: "POST" });
  }
}

/** Minor units to a display string. No FX: one currency per mandate. */
export function money(amountMinor: number, currency: string): string {
  const sign = amountMinor < 0 ? "-" : "";
  const absolute = Math.abs(amountMinor);
  const major = Math.trunc(absolute / 100);
  const minor = String(absolute % 100).padStart(2, "0");
  return `${sign}${currency} ${major.toLocaleString("en-IN")}.${minor}`;
}
