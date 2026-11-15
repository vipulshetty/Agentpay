package com.agentpaygate.pspsim;

/** A charge as the fake provider remembers it. */
public record Charge(
        String providerRef,
        String idempotencyKey,
        long amountMinor,
        long refundedMinor,
        String currency,
        String merchantId,
        String paymentId,
        String status) {

    public Charge withRefund(long amount) {
        return new Charge(providerRef, idempotencyKey, amountMinor, refundedMinor + amount, currency,
                merchantId, paymentId, refundedMinor + amount >= amountMinor ? "REFUNDED" : status);
    }
}
