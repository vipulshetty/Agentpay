package com.agentpaygate.payments;

/**
 * A webhook notification from the provider. Events may arrive twice, late, or out of order; the
 * reducer is built for that.
 */
public record ProviderEvent(
        String eventId,
        String providerRef,
        ProviderEventKind kind,
        long amountMinor,
        long occurredAtEpochSecond) {

    public ProviderEvent {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("eventId is required");
        }
        if (providerRef == null || providerRef.isBlank()) {
            throw new IllegalArgumentException("providerRef is required");
        }
        if (kind == null) {
            throw new IllegalArgumentException("kind is required");
        }
        if (amountMinor < 0L) {
            throw new IllegalArgumentException("amount must not be negative");
        }
    }
}
