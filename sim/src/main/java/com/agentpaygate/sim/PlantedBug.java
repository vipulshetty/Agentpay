package com.agentpaygate.sim;

/**
 * A deliberate defect the simulator can be asked to run with.
 *
 * <p>The point of the gauntlet is not that the simulator is correct; it is that the invariant set
 * is strong enough to notice when it is not. Each bug below is a real bug class from payment
 * systems, and each one must be caught by at least one invariant on at least one seed. A bug that
 * no invariant catches means the invariant set has a hole, and the test fails.
 */
public enum PlantedBug {

    /** No defect: the reference behaviour. */
    NONE("reference behaviour"),

    /** A duplicate capture notification posts the ledger entry a second time. */
    DOUBLE_CAPTURE_ON_REPLAY("capture posted again for a payment already captured"),

    /** A denied request still reserves budget, leaking the mandate's headroom. */
    RESERVATION_LEAK_ON_DENY("denied request reserves budget anyway"),

    /** A capture posts to the ledger but never converts the reservation into spend. */
    CAPTURE_WITHOUT_RESERVATION_RELEASE("capture leaves its reservation behind"),

    /** The mandate's budget limits are treated as advisory. */
    IGNORE_TOTAL_LIMIT("approves past the mandate's daily and lifetime limits"),

    /** A refund moves money in the ledger without moving the payment. */
    REFUND_LEDGER_WITHOUT_STATE("refund posts to the ledger but not to the payment"),

    /** A replayed idempotency key creates a second payment. */
    IDEMPOTENCY_IGNORED("replayed idempotency key creates a new payment");

    private final String description;

    PlantedBug(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }
}
