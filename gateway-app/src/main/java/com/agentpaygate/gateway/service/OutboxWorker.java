package com.agentpaygate.gateway.service;

import com.agentpaygate.gateway.config.AppProperties;
import com.agentpaygate.gateway.repo.AuditRepository;
import com.agentpaygate.gateway.repo.LedgerRepository;
import com.agentpaygate.gateway.repo.OutboxRepository;
import com.agentpaygate.gateway.repo.PaymentRepository;
import com.agentpaygate.gateway.repo.SpendRepository;
import com.agentpaygate.ledger.JournalEntry;
import com.agentpaygate.payments.Payment;
import com.agentpaygate.payments.PaymentEvent;
import com.agentpaygate.payments.PaymentEventType;
import com.agentpaygate.payments.PaymentState;
import com.agentpaygate.policy.SpendState;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Drains the outbox: calls the provider with the payment's stored idempotency key and records the
 * outcome transactionally.
 *
 * <p>Why an outbox at all: the provider call must not happen inside the transaction that created
 * the payment (a network call would hold row locks and, worse, a crash after the provider charged
 * would roll back the record that says we tried). Writing the intent to an outbox row in the same
 * transaction means a crash at any point still leaves exactly one instruction to retry, with the
 * same idempotency key.
 *
 * <p>Outcomes:
 * <ul>
 *   <li>success → payment captured, ledger posted, reservation converted to spend;</li>
 *   <li>timeout → payment becomes {@code CAPTURE_UNKNOWN}, reservation kept;</li>
 *   <li>5xx → retry with backoff and the same key; after the attempt limit, {@code CAPTURE_UNKNOWN}.</li>
 * </ul>
 */
@Component
public class OutboxWorker {

    private static final Logger log = LoggerFactory.getLogger(OutboxWorker.class);

    private final OutboxRepository outbox;
    private final PaymentRepository payments;
    private final LedgerRepository ledger;
    private final SpendRepository spend;
    private final AuditRepository audit;
    private final PspClient psp;
    private final TransactionTemplate tx;
    private final AppProperties properties;
    private final MeterRegistry meters;
    private final Clock clock;

    public OutboxWorker(OutboxRepository outbox, PaymentRepository payments, LedgerRepository ledger,
                        SpendRepository spend, AuditRepository audit, PspClient psp, TransactionTemplate tx,
                        AppProperties properties, MeterRegistry meters, Clock clock) {
        this.outbox = outbox;
        this.payments = payments;
        this.ledger = ledger;
        this.spend = spend;
        this.audit = audit;
        this.psp = psp;
        this.tx = tx;
        this.properties = properties;
        this.meters = meters;
        this.clock = clock;
        io.micrometer.core.instrument.Gauge.builder("agentpaygate.outbox.pending", outbox,
                OutboxRepository::pendingCount).register(meters);
        io.micrometer.core.instrument.Gauge.builder("agentpaygate.outbox.lag.seconds", outbox,
                OutboxRepository::lagSeconds).register(meters);
    }

    @Scheduled(fixedDelayString = "${agentpaygate.outbox.poll-interval-ms:250}")
    public void poll() {
        if (!properties.outbox().enabled()) {
            return;
        }
        for (int i = 0; i < 10; i++) {
            var item = outbox.claimNext();
            if (item.isEmpty()) {
                return;
            }
            process(item.get());
        }
    }

    /**
     * Restart recovery: any payment still {@code AUTHORIZING} with no queued work is re-enqueued
     * with its original provider idempotency key. The provider returns the original charge for a
     * repeated key, so a crash can never turn into a second charge.
     */
    public int recoverStuck() {
        int recovered = 0;
        for (OutboxRepository.StuckPayment stuck : outbox.authorizingWithoutOutbox()) {
            outbox.enqueue(stuck.paymentId(), "AUTHORIZE_AND_CAPTURE", stuck.providerIdemKey(), """
                    {"paymentId":"%s","amountMinor":%d,"currency":"%s","merchantId":"%s"}
                    """.formatted(stuck.paymentId(), stuck.amountMinor(), stuck.currency(), stuck.merchantId()));
            audit.append("gateway", "OUTBOX_RECOVERED", stuck.paymentId(),
                    "re-enqueued with the stored provider idempotency key");
            recovered++;
        }
        if (recovered > 0) {
            log.info("recovered {} payments stuck in AUTHORIZING", recovered);
            meters.counter("agentpaygate.outbox.recovered").increment(recovered);
        }
        return recovered;
    }

    @org.springframework.context.event.EventListener(
            org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void onReady() {
        if (properties.outbox().enabled()) {
            recoverStuck();
        }
    }

    /** Visible for tests: process exactly one due item if there is one. */
    public boolean pollOnce() {
        var item = outbox.claimNext();
        item.ifPresent(this::process);
        return item.isPresent();
    }

    private void process(OutboxRepository.Item item) {
        PaymentRepository.PaymentRow row = payments.find(item.paymentId()).orElse(null);
        if (row == null || row.state() != PaymentState.AUTHORIZING) {
            // Already resolved by a webhook or a previous attempt: nothing to call.
            outbox.complete(item.id());
            return;
        }
        try {
            PspClient.Charge charge = psp.charge(item.providerIdemKey(), row.amountMinor(), row.currency(),
                    row.merchantId(), row.paymentId());
            tx.executeWithoutResult(status -> applyCaptured(item, row, charge));
        } catch (PspClient.PspTimeout timeout) {
            log.warn("provider timeout for payment {}: marking capture unknown", row.paymentId());
            meters.counter("agentpaygate.provider.timeout").increment();
            tx.executeWithoutResult(status -> applyUnknown(item, row, timeout.getMessage()));
        } catch (PspClient.PspServerError error) {
            meters.counter("agentpaygate.provider.error", "status", String.valueOf(error.status())).increment();
            if (item.attempts() >= properties.outbox().maxAttempts()) {
                tx.executeWithoutResult(status -> applyUnknown(item, row, "provider error after retries: " + error.getMessage()));
            } else {
                outbox.retryLater(item.id(), error.getMessage(), Math.min(30, 1 << Math.min(item.attempts(), 5)));
            }
        } catch (RuntimeException unexpected) {
            log.error("unexpected provider failure for payment {}", row.paymentId(), unexpected);
            outbox.retryLater(item.id(), String.valueOf(unexpected.getMessage()), 5);
        }
    }

    private void applyCaptured(OutboxRepository.Item item, PaymentRepository.PaymentRow row, PspClient.Charge charge) {
        long now = clock.instant().getEpochSecond();
        Payment captured = PaymentProgress.captured(row.toPayment(), row.amountMinor(), charge.providerRef(),
                "outbox-" + item.id(), now);
        payments.update(row.paymentId(), captured, null);
        payments.appendEvent(row.paymentId(), new PaymentEvent("outbox-" + item.id() + "-cap",
                PaymentEventType.CAPTURE_SUCCEEDED, row.amountMinor(), charge.providerRef(), "", now));
        ledger.post(JournalEntry.capture("capture:" + row.paymentId(), row.currency(), row.agentId(),
                row.merchantId(), row.amountMinor()));
        SpendState before = spend.lockAndGet(row.mandateId(), SpendState.dayKeyOf(now));
        spend.save(row.mandateId(), before.capture(row.amountMinor()));
        outbox.complete(item.id());
        audit.append("gateway", "PAYMENT_CAPTURED", row.paymentId(),
                "providerRef=" + charge.providerRef() + " amount=" + row.amountMinor());
        meters.counter("agentpaygate.payments.captured").increment();
    }

    private void applyUnknown(OutboxRepository.Item item, PaymentRepository.PaymentRow row, String reason) {
        long now = clock.instant().getEpochSecond();
        Payment unknown = PaymentProgress.captureUnknown(row.toPayment(),
                row.providerRef() == null ? "" : row.providerRef(), "outbox-" + item.id(), now);
        payments.update(row.paymentId(), unknown, reason);
        payments.appendEvent(row.paymentId(), new PaymentEvent("outbox-" + item.id() + "-cap-unknown",
                PaymentEventType.CAPTURE_UNKNOWN, 0L, unknown.providerRef(), reason, now));
        outbox.markUnknown(item.id(), reason);
        audit.append("gateway", "PAYMENT_CAPTURE_UNKNOWN", row.paymentId(), "reason=" + reason);
        meters.counter("agentpaygate.payments.capture_unknown").increment();
    }
}
