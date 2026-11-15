package com.agentpaygate.gateway.service;

import com.agentpaygate.gateway.repo.AuditRepository;
import com.agentpaygate.gateway.repo.LedgerRepository;
import com.agentpaygate.gateway.repo.PaymentRepository;
import com.agentpaygate.gateway.repo.ReconciliationRepository;
import com.agentpaygate.gateway.repo.SpendRepository;
import com.agentpaygate.ledger.JournalEntry;
import com.agentpaygate.payments.Discrepancy;
import com.agentpaygate.payments.Payment;
import com.agentpaygate.payments.PaymentState;
import com.agentpaygate.payments.ProviderStatementLine;
import com.agentpaygate.payments.Reconciler;
import com.agentpaygate.payments.StatementStatus;
import com.agentpaygate.policy.SpendState;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Compares the provider statement with our payments.
 *
 * <p>Safe discrepancies are resolved here — the only such class is an unknown capture the provider
 * confirms for the expected amount, which needs no new money movement. Everything else is stored
 * and left visible in the console's manual-review queue. Nothing is dropped.
 */
@Service
public class ReconciliationService {

    private final PspClient psp;
    private final PaymentRepository payments;
    private final LedgerRepository ledger;
    private final SpendRepository spend;
    private final ReconciliationRepository repository;
    private final AuditRepository audit;
    private final MeterRegistry meters;
    private final Clock clock;

    public ReconciliationService(PspClient psp, PaymentRepository payments, LedgerRepository ledger,
                                 SpendRepository spend, ReconciliationRepository repository, AuditRepository audit,
                                 MeterRegistry meters, Clock clock) {
        this.psp = psp;
        this.payments = payments;
        this.ledger = ledger;
        this.spend = spend;
        this.repository = repository;
        this.audit = audit;
        this.meters = meters;
        this.clock = clock;
    }

    public record RunResult(String runId, int discrepancies, int autoResolved, int manualReview) {
    }

    @Transactional
    public RunResult run() {
        String runId = "rec_" + UUID.randomUUID();
        List<PspClient.StatementLine> statement = psp.statement();

        // A payment whose provider call timed out never learned its provider reference. The
        // statement carries the payment id we sent, which is how the two sides are joined before
        // comparing them. Without this, an unknown charge would look like MISSING_INTERNAL.
        int bound = 0;
        for (PspClient.StatementLine line : statement) {
            if (line.paymentId().isBlank()) {
                continue;
            }
            var row = payments.find(line.paymentId()).orElse(null);
            if (row != null && (row.providerRef() == null || row.providerRef().isBlank())) {
                payments.attachProviderRef(row.paymentId(), line.providerRef());
                bound++;
            }
        }
        if (bound > 0) {
            audit.append("gateway", "RECONCILIATION_BOUND_PROVIDER_REFS", runId,
                    "bound=" + bound + " from provider statement");
        }

        List<ProviderStatementLine> lines = statement.stream().map(line ->
                new ProviderStatementLine(line.providerRef(), line.amountMinor(),
                        StatementStatus.valueOf(line.status()))).toList();
        List<Payment> internal = payments.allForReconciliation().stream()
                .map(PaymentRepository.PaymentRow::toPayment).toList();
        List<Discrepancy> found = Reconciler.reconcile(lines, internal);

        int resolved = 0;
        for (Discrepancy discrepancy : found) {
            boolean autoResolved = false;
            if (discrepancy.autoResolvable() && !discrepancy.paymentId().isBlank()) {
                autoResolved = resolve(discrepancy.paymentId());
                if (autoResolved) {
                    resolved++;
                    meters.counter("agentpaygate.reconciliation.auto_resolved").increment();
                }
            }
            repository.save(runId, discrepancy.type().name(), discrepancy.providerRef(),
                    discrepancy.paymentId(), discrepancy.detail(), discrepancy.autoResolvable(), autoResolved);
        }
        int manual = found.size() - resolved;
        meters.counter("agentpaygate.reconciliation.mismatches").increment(found.size());
        audit.append("gateway", "RECONCILIATION_RUN", runId,
                "discrepancies=" + found.size() + " autoResolved=" + resolved + " manual=" + manual);
        return new RunResult(runId, found.size(), resolved, manual);
    }

    /** Completes a capture the provider confirmed, with the ledger posting that goes with it. */
    private boolean resolve(String paymentId) {
        PaymentRepository.PaymentRow row = payments.find(paymentId).orElse(null);
        if (row == null || row.state() != PaymentState.CAPTURE_UNKNOWN) {
            return false;
        }
        long now = clock.instant().getEpochSecond();
        Payment captured = PaymentProgress.captured(row.toPayment(), row.amountMinor(), row.providerRef(),
                "recon-" + paymentId, now);
        payments.update(paymentId, captured, null);
        ledger.post(JournalEntry.capture("capture:" + paymentId, row.currency(), row.agentId(),
                row.merchantId(), row.amountMinor()));
        SpendState before = spend.lockAndGet(row.mandateId(), SpendState.dayKeyOf(now));
        spend.save(row.mandateId(), before.capture(row.amountMinor()));
        audit.append("gateway", "RECONCILIATION_AUTO_RESOLVED", paymentId, "capture confirmed by provider");
        return true;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        return new ArrayList<>(repository.list(100));
    }

    @Transactional(readOnly = true)
    public long unresolved() {
        return repository.unresolvedCount();
    }
}
