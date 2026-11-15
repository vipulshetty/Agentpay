package com.agentpaygate.gateway.service;

import com.agentpaygate.gateway.repo.AuditRepository;
import com.agentpaygate.gateway.repo.IdempotencyRepository;
import com.agentpaygate.gateway.repo.IssuerKeyRepository;
import com.agentpaygate.gateway.repo.MandateNonceRepository;
import com.agentpaygate.gateway.repo.MandateRepository;
import com.agentpaygate.gateway.repo.OutboxRepository;
import com.agentpaygate.gateway.repo.PaymentRepository;
import com.agentpaygate.gateway.repo.SpendRepository;
import com.agentpaygate.gateway.support.ApiException;
import com.agentpaygate.payments.IdempotencyRegistry;
import com.agentpaygate.payments.Payment;
import com.agentpaygate.payments.PaymentEvent;
import com.agentpaygate.payments.PaymentEventType;
import com.agentpaygate.payments.PaymentState;
import com.agentpaygate.policy.Decision;
import com.agentpaygate.policy.DenyReason;
import com.agentpaygate.policy.PaymentRequest;
import com.agentpaygate.policy.PolicyContext;
import com.agentpaygate.policy.PolicyEngine;
import com.agentpaygate.policy.SignedMandate;
import com.agentpaygate.policy.SpendState;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The payment path.
 *
 * <p>One transaction does all of this or none of it: idempotency claim, policy decision, budget
 * reservation, payment row, first event, outbox row and audit entry. The provider call happens
 * later, from the outbox worker, with a key that was chosen here and stored with the payment.
 *
 * <p>The order matters: the idempotency claim comes first so a concurrent duplicate cannot create
 * a second payment; the budget row is locked before the decision so concurrent requests cannot
 * both fit into the last slice of budget.
 */
@Service
public class PaymentService {

    private final PaymentRepository payments;
    private final MandateRepository mandates;
    private final IssuerKeyRepository issuerKeys;
    private final SpendRepository spend;
    private final MandateNonceRepository nonces;
    private final IdempotencyRepository idempotency;
    private final OutboxRepository outbox;
    private final AuditRepository audit;
    private final MeterRegistry meters;
    private final Clock clock;

    public PaymentService(PaymentRepository payments, MandateRepository mandates, IssuerKeyRepository issuerKeys,
                          SpendRepository spend, MandateNonceRepository nonces, IdempotencyRepository idempotency,
                          OutboxRepository outbox, AuditRepository audit, MeterRegistry meters, Clock clock) {
        this.payments = payments;
        this.mandates = mandates;
        this.issuerKeys = issuerKeys;
        this.spend = spend;
        this.nonces = nonces;
        this.idempotency = idempotency;
        this.outbox = outbox;
        this.audit = audit;
        this.meters = meters;
        this.clock = clock;
    }

    public record RequestCommand(String mandateId, String merchantId, String merchantCategory,
                                 long amountMinor, String currency, String idempotencyKey, String nonce) {
    }

    public record PaymentResult(String paymentId, String state, String reasonCode, long amountMinor,
                                boolean replayed) {
    }

    @Transactional
    public PaymentResult requestPayment(String agentId, RequestCommand command) {
        MDC.put("agentId", agentId);
        String payloadHash = IdempotencyRegistry.hashOf(agentId, command.merchantId(),
                command.amountMinor(), command.currency());

        var existing = idempotency.find(agentId, command.idempotencyKey());
        if (existing.isPresent()) {
            if (!existing.get().payloadHash().equals(payloadHash)) {
                meters.counter("agentpaygate.idempotency.conflict").increment();
                throw ApiException.idempotencyConflict(existing.get().paymentId());
            }
            meters.counter("agentpaygate.idempotency.replay").increment();
            PaymentRepository.PaymentRow row = payments.find(existing.get().paymentId())
                    .orElseThrow(() -> ApiException.notFound("payment " + existing.get().paymentId()));
            return new PaymentResult(row.paymentId(), row.state().name(), row.reasonCode(),
                    row.amountMinor(), true);
        }

        SignedMandate signedMandate = mandates.find(command.mandateId())
                .orElseThrow(() -> ApiException.notFound("mandate " + command.mandateId()));
        if (!signedMandate.mandate().agentId().equals(agentId)) {
            throw ApiException.forbidden("mandate " + command.mandateId() + " was not issued to this agent");
        }

        String paymentId = "pay_" + UUID.randomUUID();
        String providerIdemKey = "prov_" + paymentId;
        long now = clock.instant().getEpochSecond();
        String requestNonce = command.nonce() == null || command.nonce().isBlank()
                ? command.idempotencyKey()
                : command.nonce();

        // Claim the idempotency key before doing any work. If another request won the race, return
        // its payment rather than creating a second one.
        if (!idempotency.insertIfAbsent(agentId, command.idempotencyKey(), payloadHash, paymentId, "IN_PROGRESS")) {
            var winner = idempotency.find(agentId, command.idempotencyKey())
                    .orElseThrow(() -> new IllegalStateException("idempotency row vanished"));
            if (!winner.payloadHash().equals(payloadHash)) {
                throw ApiException.idempotencyConflict(winner.paymentId());
            }
            PaymentRepository.PaymentRow row = payments.find(winner.paymentId())
                    .orElseThrow(() -> ApiException.notFound("payment " + winner.paymentId()));
            meters.counter("agentpaygate.idempotency.replay").increment();
            return new PaymentResult(row.paymentId(), row.state().name(), row.reasonCode(),
                    row.amountMinor(), true);
        }

        SpendState before = spend.lockAndGet(command.mandateId(), SpendState.dayKeyOf(now));
        PaymentRequest policyRequest = new PaymentRequest(paymentId, command.idempotencyKey(), agentId,
                command.merchantId(), command.merchantCategory(), command.amountMinor(),
                command.currency(), requestNonce);
        PolicyContext context = new PolicyContext(now, issuerKeys.snapshot(),
                nonces.isUsed(command.mandateId(), requestNonce));
        Decision decision = PolicyEngine.evaluate(signedMandate, policyRequest, before, context);

        if (decision.denied()) {
            meters.counter("agentpaygate.policy.denied", "reason", decision.reason().name()).increment();
            persistDenied(agentId, command, paymentId, providerIdemKey, requestNonce, decision, now);
            return new PaymentResult(paymentId, PaymentState.DENIED.name(), decision.reason().name(),
                    command.amountMinor(), false);
        }

        if (!nonces.register(command.mandateId(), requestNonce, paymentId)) {
            meters.counter("agentpaygate.policy.denied", "reason", DenyReason.REPLAYED.name()).increment();
            Decision replayed = Decision.deny(DenyReason.REPLAYED, "nonce already used");
            persistDenied(agentId, command, paymentId, providerIdemKey, requestNonce, replayed, now);
            return new PaymentResult(paymentId, PaymentState.DENIED.name(), DenyReason.REPLAYED.name(),
                    command.amountMinor(), false);
        }

        Payment payment = Payment.requested(paymentId, agentId, command.merchantId(), command.currency(),
                        command.amountMinor(), providerIdemKey, now)
                .apply(PaymentEvent.of(paymentId + "-submitted", PaymentEventType.AUTH_STARTED, 0L, now));

        payments.insert(new PaymentRepository.PaymentRow(paymentId, agentId, command.mandateId(),
                command.merchantId(), command.merchantCategory(), command.currency(), command.amountMinor(),
                payment.state(), 0L, 0L, null, providerIdemKey, null, requestNonce,
                Instant.ofEpochSecond(now), Instant.ofEpochSecond(now)));
        payments.appendEvent(paymentId, PaymentEvent.of(paymentId + "-submitted",
                PaymentEventType.AUTH_STARTED, 0L, now));

        spend.save(command.mandateId(), before.reserve(decision.reservedAmountMinor()));
        outbox.enqueue(paymentId, "AUTHORIZE_AND_CAPTURE", providerIdemKey, """
                {"paymentId":"%s","amountMinor":%d,"currency":"%s","merchantId":"%s"}
                """.formatted(paymentId, command.amountMinor(), command.currency(), command.merchantId()));
        idempotency.updateState(agentId, command.idempotencyKey(), payment.state().name());
        audit.append("agent:" + agentId, "PAYMENT_APPROVED", paymentId,
                "mandate=" + command.mandateId() + " amount=" + command.amountMinor()
                        + " reserved=yes state=" + payment.state());
        meters.counter("agentpaygate.payments.requested").increment();
        MDC.put("paymentId", paymentId);
        return new PaymentResult(paymentId, payment.state().name(), null, command.amountMinor(), false);
    }

    private void persistDenied(String agentId, RequestCommand command, String paymentId, String providerIdemKey,
                               String requestNonce, Decision decision, long now) {
        payments.insert(new PaymentRepository.PaymentRow(paymentId, agentId, command.mandateId(),
                command.merchantId(), command.merchantCategory(), command.currency(), command.amountMinor(),
                PaymentState.DENIED, 0L, 0L, null, providerIdemKey, decision.reason().name(), requestNonce,
                Instant.ofEpochSecond(now), Instant.ofEpochSecond(now)));
        payments.appendEvent(paymentId, PaymentEvent.of(paymentId + "-denied", PaymentEventType.POLICY_DENIED,
                0L, now).withReason(decision.reason().name()));
        idempotency.updateState(agentId, command.idempotencyKey(), PaymentState.DENIED.name());
        audit.append("agent:" + agentId, "PAYMENT_DENIED", paymentId,
                "reason=" + decision.reason() + " detail=" + decision.detail());
    }

    @Transactional(readOnly = true)
    public PaymentRepository.PaymentRow getPayment(String agentId, String paymentId) {
        PaymentRepository.PaymentRow row = payments.find(paymentId)
                .orElseThrow(() -> ApiException.notFound("payment " + paymentId));
        if (!row.agentId().equals(agentId)) {
            throw ApiException.forbidden("payment " + paymentId + " belongs to another agent");
        }
        return row;
    }

    @Transactional(readOnly = true)
    public List<PaymentRepository.PaymentRow> listForAgent(String agentId) {
        return payments.listByAgent(agentId, 50);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> remainingBudget(String agentId, String mandateId) {
        SignedMandate signedMandate = mandates.find(mandateId)
                .orElseThrow(() -> ApiException.notFound("mandate " + mandateId));
        if (!signedMandate.mandate().agentId().equals(agentId)) {
            throw ApiException.forbidden("mandate " + mandateId + " was not issued to this agent");
        }
        SpendState state = spend.current(mandateId);
        long total = signedMandate.mandate().maxTotal();
        long remainingTotal = total - state.committedTotal();
        long remainingToday = signedMandate.mandate().maxPerDay() - state.committedToday();
        return Map.of(
                "mandateId", mandateId,
                "currency", signedMandate.mandate().currency(),
                "maxTotal", total,
                "spentTotal", state.spentTotal(),
                "reservedTotal", state.reservedTotal(),
                "remainingTotal", remainingTotal,
                "remainingToday", remainingToday,
                "expiresAt", signedMandate.mandate().expiresAtEpochSecond());
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listMandatesForAgent(String agentId) {
        return mandates.listByAgent(agentId).stream()
                .map(signed -> {
                    SpendState state = spend.current(signed.mandate().mandateId());
                    Map<String, Object> row = new java.util.TreeMap<>();
                    row.put("mandateId", signed.mandate().mandateId());
                    row.put("currency", signed.mandate().currency());
                    row.put("maxTotal", signed.mandate().maxTotal());
                    row.put("remainingTotal", signed.mandate().maxTotal() - state.committedTotal());
                    row.put("expiresAt", signed.mandate().expiresAtEpochSecond());
                    return row;
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> paymentDetail(String agentId, String paymentId) {
        PaymentRepository.PaymentRow row = getPayment(agentId, paymentId);
        Map<String, Object> detail = new java.util.TreeMap<>();
        detail.put("payment", row);
        detail.put("events", payments.events(paymentId));
        return detail;
    }

    public static PaymentState stateOf(String value) {
        return PaymentState.valueOf(value);
    }

    static boolean isTerminal(PaymentState state) {
        return state.isTerminal();
    }

    @SuppressWarnings("unused")
    private static ApiException conflict(String code) {
        return new ApiException(HttpStatus.CONFLICT, code, code);
    }
}
