package com.agentpaygate.payments;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable payment aggregate. Every change goes through {@link #apply(PaymentEvent)}, which
 * validates the transition and enforces the money invariants:
 *
 * <ul>
 *   <li>captured never exceeds the authorised amount;</li>
 *   <li>refunds never exceed captures (in total, so partial refunds add up);</li>
 *   <li>a payment never moves backwards through states.</li>
 * </ul>
 *
 * <p>{@code providerIdempotencyKey} is assigned once, when the payment is created, and never
 * changes. That is the whole defence against a double charge: every provider call for this
 * payment, including retries after a timeout, reuses this key.
 */
public record Payment(
        String paymentId,
        String agentId,
        String merchantId,
        String currency,
        long amountMinor,
        String providerRef,
        String providerIdempotencyKey,
        PaymentState state,
        long capturedMinor,
        long refundedMinor,
        long lastEventEpochSecond,
        List<StateChange> history) {

    /** One transition, kept for the payment detail timeline in the console. */
    public record StateChange(PaymentState from, PaymentState to, String eventId, long atEpochSecond) {
        public StateChange {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
        }
    }

    public Payment {
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(merchantId, "merchantId");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(state, "state");
        if (amountMinor <= 0L) {
            throw new IllegalArgumentException("payment amount must be positive");
        }
        if (providerRef == null) {
            providerRef = "";
        }
        if (providerIdempotencyKey == null || providerIdempotencyKey.isBlank()) {
            throw new IllegalArgumentException("provider idempotency key is required");
        }
        if (capturedMinor < 0L || capturedMinor > amountMinor) {
            throw new IllegalArgumentException("captured amount out of range: " + capturedMinor);
        }
        if (refundedMinor < 0L || refundedMinor > capturedMinor) {
            throw new IllegalArgumentException("refunded amount out of range: " + refundedMinor);
        }
        history = List.copyOf(history);
    }

    public static Payment requested(
            String paymentId,
            String agentId,
            String merchantId,
            String currency,
            long amountMinor,
            String providerIdempotencyKey,
            long nowEpochSecond) {
        return new Payment(paymentId, agentId, merchantId, currency, amountMinor, "",
                providerIdempotencyKey, PaymentState.REQUESTED, 0L, 0L, nowEpochSecond, List.of());
    }

    /**
     * Applies an event, returning a new payment. Illegal transitions throw: the state machine is
     * not advisory.
     */
    public Payment apply(PaymentEvent event) {
        Objects.requireNonNull(event, "event");
        PaymentState target = event.type().targetState();
        if (!state.canTransitionTo(target)) {
            throw new IllegalStateException(
                    "illegal transition for payment " + paymentId + ": " + state + " -> " + target
                            + " (event " + event.eventId() + ")");
        }

        long captured = capturedMinor;
        long refunded = refundedMinor;
        switch (event.type()) {
            case CAPTURE_SUCCEEDED -> {
                if (event.amountMinor() != 0L && event.amountMinor() != amountMinor) {
                    throw new IllegalArgumentException(
                            "capture amount " + event.amountMinor() + " does not equal authorised amount " + amountMinor);
                }
                captured = amountMinor;
            }
            case REFUND_SUCCEEDED -> {
                if (event.amountMinor() <= 0L) {
                    throw new IllegalArgumentException("refund amount must be positive");
                }
                long total = Math.addExact(refunded, event.amountMinor());
                if (total > captured) {
                    throw new IllegalStateException(
                            "refund of " + event.amountMinor() + " would exceed captured " + captured
                                    + " (already refunded " + refunded + ")");
                }
                refunded = total;
            }
            default -> {
                // no money movement for the remaining events
            }
        }

        String provider = event.providerRef().isBlank() ? providerRef : event.providerRef();
        List<StateChange> nextHistory = new ArrayList<>(history);
        nextHistory.add(new StateChange(state, target, event.eventId(), event.occurredAtEpochSecond()));

        return new Payment(paymentId, agentId, merchantId, currency, amountMinor, provider,
                providerIdempotencyKey, target, captured, refunded, event.occurredAtEpochSecond(),
                nextHistory);
    }

    public long refundableRemaining() {
        return capturedMinor - refundedMinor;
    }

    public boolean isFullyRefunded() {
        return capturedMinor > 0L && refundedMinor == capturedMinor;
    }
}
