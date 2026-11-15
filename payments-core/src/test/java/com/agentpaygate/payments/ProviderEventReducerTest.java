package com.agentpaygate.payments;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProviderEventReducerTest {

    private static final long NOW = 1_700_000_000L;

    private static Payment capturing() {
        Payment p = Payment.requested("pay-1", "agent-1", "merchant-1", "INR", 50_000L, "prov-key-1", NOW);
        p = p.apply(PaymentEvent.of("a1", PaymentEventType.AUTH_STARTED, 0L, NOW));
        p = p.apply(PaymentEvent.of("a2", PaymentEventType.AUTH_SUCCEEDED, 0L, NOW).withProviderRef("prov-1"));
        return p.apply(PaymentEvent.of("a3", PaymentEventType.CAPTURE_STARTED, 0L, NOW));
    }

    @Test
    void captureNotificationResolvesAnUnknownOutcome() {
        ProviderEventReducer reducer = new ProviderEventReducer();
        Payment unknown = capturing().apply(PaymentEvent.of("a4", PaymentEventType.CAPTURE_UNKNOWN, 0L, NOW));

        ReduceResult result = reducer.reduce(unknown, new ProviderEvent("evt-1", "prov-1",
                ProviderEventKind.CAPTURED, 50_000L, NOW + 5));

        assertThat(result.changed()).isTrue();
        assertThat(result.payment().state()).isEqualTo(PaymentState.CAPTURED);
        assertThat(result.payment().capturedMinor()).isEqualTo(50_000L);
    }

    @Test
    void duplicateEventIdsAreIgnored() {
        ProviderEventReducer reducer = new ProviderEventReducer();
        Payment unknown = capturing().apply(PaymentEvent.of("a4", PaymentEventType.CAPTURE_UNKNOWN, 0L, NOW));
        ProviderEvent event = new ProviderEvent("evt-1", "prov-1", ProviderEventKind.CAPTURED, 50_000L, NOW);

        Payment first = reducer.reduce(unknown, event).payment();
        ReduceResult second = reducer.reduce(first, event);

        assertThat(second.outcome()).isEqualTo(ReduceResult.Outcome.DUPLICATE);
        assertThat(second.payment()).isEqualTo(first);
        assertThat(reducer.processedCount()).isEqualTo(1);
    }

    @Test
    void lateAuthorizationDoesNotMoveACapturedPaymentBackwards() {
        ProviderEventReducer reducer = new ProviderEventReducer();
        Payment captured = capturing()
                .apply(PaymentEvent.of("a4", PaymentEventType.CAPTURE_SUCCEEDED, 50_000L, NOW));

        ReduceResult late = reducer.reduce(captured, new ProviderEvent("evt-late", "prov-1",
                ProviderEventKind.AUTHORIZED, 50_000L, NOW - 100));

        assertThat(late.outcome()).isEqualTo(ReduceResult.Outcome.STALE);
        assertThat(late.payment().state()).isEqualTo(PaymentState.CAPTURED);
    }

    @Test
    void outOfOrderSequenceEndsInTheSameStateAsInOrderSequence() {
        ProviderEvent capturedEvent = new ProviderEvent("e-capture", "prov-1", ProviderEventKind.CAPTURED, 50_000L, NOW + 10);
        ProviderEvent authorizedEvent = new ProviderEvent("e-auth", "prov-1", ProviderEventKind.AUTHORIZED, 50_000L, NOW);

        ProviderEventReducer inOrder = new ProviderEventReducer();
        Payment start = capturing();
        Payment a = inOrder.reduce(start, capturedEvent).payment();

        ProviderEventReducer reversed = new ProviderEventReducer();
        Payment unknown = capturing().apply(PaymentEvent.of("x", PaymentEventType.CAPTURE_UNKNOWN, 0L, NOW));
        Payment b = reversed.reduce(reversed.reduce(unknown, authorizedEvent).payment(), capturedEvent).payment();

        assertThat(b.state()).isEqualTo(a.state());
        assertThat(b.capturedMinor()).isEqualTo(a.capturedMinor());
    }

    @Test
    void refundNotificationRecordsTheRefund() {
        ProviderEventReducer reducer = new ProviderEventReducer();
        Payment captured = capturing()
                .apply(PaymentEvent.of("a4", PaymentEventType.CAPTURE_SUCCEEDED, 50_000L, NOW))
                .apply(PaymentEvent.of("a5", PaymentEventType.REFUND_STARTED, 0L, NOW));

        ReduceResult result = reducer.reduce(captured, new ProviderEvent("evt-r", "prov-1",
                ProviderEventKind.REFUNDED, 20_000L, NOW + 20));

        assertThat(result.payment().state()).isEqualTo(PaymentState.REFUNDED);
        assertThat(result.payment().refundedMinor()).isEqualTo(20_000L);
    }

    @Test
    void eventForAnotherProviderReferenceIsStale() {
        ProviderEventReducer reducer = new ProviderEventReducer();
        Payment unknown = capturing().apply(PaymentEvent.of("a4", PaymentEventType.CAPTURE_UNKNOWN, 0L, NOW));
        ReduceResult result = reducer.reduce(unknown, new ProviderEvent("evt-x", "prov-other",
                ProviderEventKind.CAPTURED, 50_000L, NOW));
        assertThat(result.outcome()).isEqualTo(ReduceResult.Outcome.STALE);
    }
}
