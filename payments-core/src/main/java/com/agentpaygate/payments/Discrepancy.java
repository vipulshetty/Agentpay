package com.agentpaygate.payments;

/**
 * One reconciliation finding.
 *
 * <p>{@code autoResolvable} is true only for cases where the safe resolution is unambiguous — the
 * canonical example being a payment in {@code CAPTURE_UNKNOWN} that the provider confirms as
 * captured for the same amount. Everything else (amount mismatch, provider-only charge, status
 * disagreement that is not an unknown) goes to manual review.
 */
public record Discrepancy(
        DiscrepancyType type,
        String providerRef,
        String paymentId,
        String detail,
        boolean autoResolvable) implements Comparable<Discrepancy> {

    @Override
    public int compareTo(Discrepancy other) {
        int byType = type.compareTo(other.type);
        if (byType != 0) {
            return byType;
        }
        int byRef = providerRef.compareTo(other.providerRef);
        return byRef != 0 ? byRef : paymentId.compareTo(other.paymentId);
    }
}
