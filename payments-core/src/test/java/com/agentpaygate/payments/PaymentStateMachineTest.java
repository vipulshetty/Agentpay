package com.agentpaygate.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PaymentStateMachineTest {

    private static final long NOW = 1_700_000_000L;

    private static Payment payment() {
        return Payment.requested("pay-1", "agent-1", "merchant-1", "INR", 100_000L, "prov-key-1", NOW);
    }

    private static Payment advance(Payment from, PaymentState target) {
        Payment current = from;
        for (PaymentEventType type : PaymentEventType.values()) {
            if (type.targetState() == target) {
                long amount = (type == PaymentEventType.REFUND_SUCCEEDED) ? 1L : 0L;
                current = current.apply(PaymentEvent.of("evt-" + type, type, amount, NOW));
                return current;
            }
        }
        throw new IllegalStateException("no event type targets " + target);
    }

    @Test
    void everyLegalTransitionIsAccepted() {
        for (PaymentState from : PaymentState.values()) {
            for (PaymentState to : PaymentState.values()) {
                if (!PaymentTransitions.isAllowed(from, to)) {
                    continue;
                }
                Payment start = stateOf(from);
                PaymentEventType eventType = eventTypeTargeting(to, from);
                if (eventType == null) {
                    continue;
                }
                long amount = eventType == PaymentEventType.REFUND_SUCCEEDED ? Math.max(1L, start.capturedMinor()) : 0L;
                Payment after = start.apply(PaymentEvent.of("e-" + from + "-" + to, eventType, amount, NOW));
                assertThat(after.state()).isEqualTo(to);
            }
        }
    }

    @Test
    void everyIllegalTransitionThrows() {
        for (PaymentState from : PaymentState.values()) {
            for (PaymentState to : PaymentState.values()) {
                if (PaymentTransitions.isAllowed(from, to)) {
                    continue;
                }
                PaymentEventType eventType = eventTypeTargeting(to, from);
                if (eventType == null) {
                    continue;
                }
                Payment start = stateOf(from);
                long amount = eventType == PaymentEventType.REFUND_SUCCEEDED ? 1L : 0L;
                assertThatThrownBy(() -> start.apply(PaymentEvent.of("x", eventType, amount, NOW)))
                        .as("transition %s -> %s must be rejected", from, to)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("illegal transition");
            }
        }
    }

    @Test
    void refundsAccumulateAndNeverExceedTheCapture() {
        Payment captured = capture(payment(), NOW);
        Payment partiallyRefunded = captured
                .apply(PaymentEvent.of("r1", PaymentEventType.REFUND_STARTED, 0L, NOW))
                .apply(PaymentEvent.of("r1-done", PaymentEventType.REFUND_SUCCEEDED, 40_000L, NOW));

        assertThat(partiallyRefunded.refundedMinor()).isEqualTo(40_000L);
        assertThat(partiallyRefunded.refundableRemaining()).isEqualTo(60_000L);
        assertThat(partiallyRefunded.state()).isEqualTo(PaymentState.REFUNDED);

        Payment second = partiallyRefunded
                .apply(PaymentEvent.of("r2", PaymentEventType.REFUND_STARTED, 0L, NOW))
                .apply(PaymentEvent.of("r2-done", PaymentEventType.REFUND_SUCCEEDED, 60_000L, NOW));
        assertThat(second.refundedMinor()).isEqualTo(100_000L);
        assertThat(second.isFullyRefunded()).isTrue();

        assertThatThrownBy(() -> second
                .apply(PaymentEvent.of("r3", PaymentEventType.REFUND_STARTED, 0L, NOW))
                .apply(PaymentEvent.of("r3-done", PaymentEventType.REFUND_SUCCEEDED, 1L, NOW)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exceed captured");
    }

    @Test
    void cannotRefundMoreThanCaptured() {
        Payment captured = capture(payment(), NOW);
        Payment refunding = captured.apply(PaymentEvent.of("r1", PaymentEventType.REFUND_STARTED, 0L, NOW));
        assertThatThrownBy(() -> refunding.apply(
                PaymentEvent.of("r1-done", PaymentEventType.REFUND_SUCCEEDED, 100_001L, NOW)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exceed captured");
    }

    @Test
    void voidOnlyBeforeCapture() {
        Payment authorised = advance(advance(payment(), PaymentState.AUTHORIZING), PaymentState.AUTHORIZED);
        Payment voided = authorised.apply(PaymentEvent.of("v", PaymentEventType.VOIDED, 0L, NOW));
        assertThat(voided.state()).isEqualTo(PaymentState.VOIDED);

        Payment captured = capture(payment(), NOW);
        assertThatThrownBy(() -> captured.apply(PaymentEvent.of("v2", PaymentEventType.VOIDED, 0L, NOW)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void captureStoresTheCapturedAmountAndKeepsTheProviderKeyStable() {
        Payment captured = capture(payment(), NOW);
        assertThat(captured.capturedMinor()).isEqualTo(100_000L);
        assertThat(captured.providerIdempotencyKey()).isEqualTo("prov-key-1");
        assertThat(captured.history()).hasSize(4);
    }

    @Test
    void captureAmountMustMatchTheAuthorisedAmount() {
        Payment capturing = advance(payment(), PaymentState.AUTHORIZING);
        capturing = advance(capturing, PaymentState.AUTHORIZED);
        capturing = advance(capturing, PaymentState.CAPTURING);
        Payment start = capturing;
        assertThatThrownBy(() -> start.apply(
                PaymentEvent.of("c", PaymentEventType.CAPTURE_SUCCEEDED, 99_999L, NOW)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("authorised amount");
    }

    @Test
    void terminalStatesHaveNoOutgoingTransitions() {
        Set<PaymentState> terminals = EnumSet.of(PaymentState.DENIED, PaymentState.FAILED, PaymentState.VOIDED);
        for (PaymentState terminal : terminals) {
            assertThat(PaymentTransitions.targetsFrom(terminal)).isEmpty();
            assertThat(terminal.isTerminal()).isTrue();
        }
    }

    private static Payment capture(Payment start, long at) {
        Payment p = start;
        p = p.apply(PaymentEvent.of("auth-start", PaymentEventType.AUTH_STARTED, 0L, at));
        p = p.apply(PaymentEvent.of("auth-ok", PaymentEventType.AUTH_SUCCEEDED, 0L, at).withProviderRef("prov-1"));
        p = p.apply(PaymentEvent.of("cap-start", PaymentEventType.CAPTURE_STARTED, 0L, at));
        return p.apply(PaymentEvent.of("cap-ok", PaymentEventType.CAPTURE_SUCCEEDED, 100_000L, at));
    }

    /** Builds a payment sitting in {@code target} through the shortest legal path. */
    private static Payment stateOf(PaymentState target) {
        return switch (target) {
            case REQUESTED -> payment();
            case DENIED -> payment().apply(PaymentEvent.of("deny", PaymentEventType.POLICY_DENIED, 0L, NOW));
            case AUTHORIZING -> advance(payment(), PaymentState.AUTHORIZING);
            case AUTHORIZATION_UNKNOWN -> advance(advance(payment(), PaymentState.AUTHORIZING), PaymentState.AUTHORIZATION_UNKNOWN);
            case AUTHORIZED -> advance(advance(payment(), PaymentState.AUTHORIZING), PaymentState.AUTHORIZED);
            case CAPTURING -> advance(stateOf(PaymentState.AUTHORIZED), PaymentState.CAPTURING);
            case CAPTURE_UNKNOWN -> advance(stateOf(PaymentState.CAPTURING), PaymentState.CAPTURE_UNKNOWN);
            case CAPTURED -> capture(payment(), NOW);
            case VOIDED -> stateOf(PaymentState.AUTHORIZED).apply(PaymentEvent.of("v", PaymentEventType.VOIDED, 0L, NOW));
            case REFUNDING -> stateOf(PaymentState.CAPTURED)
                    .apply(PaymentEvent.of("r", PaymentEventType.REFUND_STARTED, 0L, NOW));
            case REFUNDED -> stateOf(PaymentState.REFUNDING)
                    .apply(PaymentEvent.of("rd", PaymentEventType.REFUND_SUCCEEDED, 10_000L, NOW));
            case FAILED -> advance(advance(payment(), PaymentState.AUTHORIZING), PaymentState.FAILED);
        };
    }

    private static PaymentEventType eventTypeTargeting(PaymentState target, PaymentState from) {
        List<PaymentEventType> candidates = List.of(PaymentEventType.values());
        for (PaymentEventType type : candidates) {
            if (type.targetState() != target) {
                continue;
            }
            if (target == PaymentState.REFUNDED && from == PaymentState.REFUNDING) {
                return type;
            }
            return type;
        }
        return null;
    }
}
