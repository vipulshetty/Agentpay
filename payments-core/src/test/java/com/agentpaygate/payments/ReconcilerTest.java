package com.agentpaygate.payments;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ReconcilerTest {

    private static final long NOW = 1_700_000_000L;

    private static Payment captured(String id, String providerRef, long amount) {
        Payment p = Payment.requested(id, "agent-1", "merchant-1", "INR", amount, "key-" + id, NOW);
        p = p.apply(PaymentEvent.of("a1", PaymentEventType.AUTH_STARTED, 0L, NOW));
        p = p.apply(PaymentEvent.of("a2", PaymentEventType.AUTH_SUCCEEDED, 0L, NOW).withProviderRef(providerRef));
        p = p.apply(PaymentEvent.of("a3", PaymentEventType.CAPTURE_STARTED, 0L, NOW));
        return p.apply(PaymentEvent.of("a4", PaymentEventType.CAPTURE_SUCCEEDED, amount, NOW));
    }

    @Test
    void cleanReconciliationProducesNoDiscrepancies() {
        List<Discrepancy> found = Reconciler.reconcile(
                List.of(new ProviderStatementLine("prov-1", 50_000L, StatementStatus.CAPTURED)),
                List.of(captured("pay-1", "prov-1", 50_000L)));
        assertThat(found).isEmpty();
    }

    @Test
    void chargeAtProviderWithNoInternalPaymentIsMissingInternal() {
        List<Discrepancy> found = Reconciler.reconcile(
                List.of(new ProviderStatementLine("prov-ghost", 10_000L, StatementStatus.CAPTURED)),
                List.of());
        assertThat(found).hasSize(1);
        assertThat(found.get(0).type()).isEqualTo(DiscrepancyType.MISSING_INTERNAL);
        assertThat(found.get(0).autoResolvable()).isFalse();
    }

    @Test
    void capturedPaymentWithNoStatementLineIsMissingProvider() {
        List<Discrepancy> found = Reconciler.reconcile(
                List.of(),
                List.of(captured("pay-1", "prov-1", 50_000L)));
        assertThat(found).hasSize(1);
        assertThat(found.get(0).type()).isEqualTo(DiscrepancyType.MISSING_PROVIDER);
    }

    @Test
    void differentAmountIsAmountMismatch() {
        List<Discrepancy> found = Reconciler.reconcile(
                List.of(new ProviderStatementLine("prov-1", 49_999L, StatementStatus.CAPTURED)),
                List.of(captured("pay-1", "prov-1", 50_000L)));
        assertThat(found).hasSize(1);
        assertThat(found.get(0).type()).isEqualTo(DiscrepancyType.AMOUNT_MISMATCH);
        assertThat(found.get(0).autoResolvable()).isFalse();
    }

    @Test
    void providerRefundedButWeThinkCapturedIsStatusMismatch() {
        List<Discrepancy> found = Reconciler.reconcile(
                List.of(new ProviderStatementLine("prov-1", 50_000L, StatementStatus.REFUNDED)),
                List.of(captured("pay-1", "prov-1", 50_000L)));
        assertThat(found).hasSize(1);
        assertThat(found.get(0).type()).isEqualTo(DiscrepancyType.STATUS_MISMATCH);
        assertThat(found.get(0).autoResolvable()).isFalse();
    }

    @Test
    void unknownCaptureConfirmedByProviderForTheSameAmountIsAutoResolvable() {
        Payment stuck = Payment.requested("pay-u", "agent-1", "merchant-1", "INR", 50_000L, "key-u", NOW);
        stuck = stuck.apply(PaymentEvent.of("s1", PaymentEventType.AUTH_STARTED, 0L, NOW));
        stuck = stuck.apply(PaymentEvent.of("s2", PaymentEventType.AUTH_SUCCEEDED, 0L, NOW).withProviderRef("prov-u"));
        stuck = stuck.apply(PaymentEvent.of("s3", PaymentEventType.CAPTURE_STARTED, 0L, NOW));
        stuck = stuck.apply(PaymentEvent.of("s4", PaymentEventType.CAPTURE_UNKNOWN, 0L, NOW));

        List<Discrepancy> found = Reconciler.reconcile(
                List.of(new ProviderStatementLine("prov-u", 50_000L, StatementStatus.CAPTURED)),
                List.of(stuck));

        assertThat(found).hasSize(1);
        assertThat(found.get(0).type()).isEqualTo(DiscrepancyType.STATUS_MISMATCH);
        assertThat(found.get(0).autoResolvable()).isTrue();
        assertThat(found.get(0).detail()).contains("safe to resolve");
    }

    @Test
    void discrepanciesAreSortedDeterministically() {
        List<Discrepancy> found = Reconciler.reconcile(
                List.of(
                        new ProviderStatementLine("prov-z", 1L, StatementStatus.CAPTURED),
                        new ProviderStatementLine("prov-a", 1L, StatementStatus.CAPTURED)),
                List.of());
        assertThat(found).extracting(Discrepancy::providerRef).containsExactly("prov-a", "prov-z");
    }
}
