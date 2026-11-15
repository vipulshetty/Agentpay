package com.agentpaygate.payments;

/** Result of feeding one provider notification into the reducer. */
public record ReduceResult(Outcome outcome, Payment payment, String detail) {

    public enum Outcome {
        /** State changed; persist {@code payment}. */
        APPLIED,
        /** Event id already processed; nothing changed (idempotent). */
        DUPLICATE,
        /** Event would move the payment backwards; ignored (order-tolerant). */
        STALE,
        /** Event is valid but the payment is already in that state. */
        NO_CHANGE
    }

    public static ReduceResult applied(Payment payment) {
        return new ReduceResult(Outcome.APPLIED, payment, "applied");
    }

    public static ReduceResult duplicate(Payment payment) {
        return new ReduceResult(Outcome.DUPLICATE, payment, "duplicate event id");
    }

    public static ReduceResult stale(Payment payment, String detail) {
        return new ReduceResult(Outcome.STALE, payment, detail);
    }

    public static ReduceResult noChange(Payment payment, String detail) {
        return new ReduceResult(Outcome.NO_CHANGE, payment, detail);
    }

    public boolean changed() {
        return outcome == Outcome.APPLIED;
    }
}
