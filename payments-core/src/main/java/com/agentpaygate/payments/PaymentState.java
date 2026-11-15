package com.agentpaygate.payments;

/**
 * Payment lifecycle states.
 *
 * <p>{@code AUTHORIZATION_UNKNOWN} and {@code CAPTURE_UNKNOWN} are first-class states, not an
 * afterthought: when a provider call times out we do not know whether the charge happened. The
 * payment stops there until the same provider idempotency key is replayed or reconciliation
 * settles it. Retrying with a fresh key is exactly how a double charge happens.
 *
 * <p>{@code progress} is a monotonic index of the happy path, used by the provider-event reducer
 * to ignore out-of-order notifications without ever moving a payment backwards. Failure and
 * denial states have progress 0 because they are not positions on the happy path.
 */
public enum PaymentState {
    REQUESTED(1),
    DENIED(0),
    AUTHORIZING(2),
    AUTHORIZATION_UNKNOWN(3),
    AUTHORIZED(4),
    CAPTURING(5),
    CAPTURE_UNKNOWN(6),
    CAPTURED(7),
    VOIDED(0),
    REFUNDING(8),
    REFUNDED(9),
    FAILED(0);

    private final int progress;

    PaymentState(int progress) {
        this.progress = progress;
    }

    public int progress() {
        return progress;
    }

    /** Unknown outcome: the provider may or may not have applied the operation. */
    public boolean isUnknown() {
        return this == AUTHORIZATION_UNKNOWN || this == CAPTURE_UNKNOWN;
    }

    public boolean isTerminal() {
        return PaymentTransitions.targetsFrom(this).isEmpty();
    }

    public boolean canTransitionTo(PaymentState target) {
        return PaymentTransitions.isAllowed(this, target);
    }
}
