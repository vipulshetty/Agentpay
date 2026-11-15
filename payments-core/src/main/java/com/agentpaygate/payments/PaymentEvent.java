package com.agentpaygate.payments;

/**
 * A business event that caused a payment to move. {@code eventId} is unique per event so replays
 * can be ignored; {@code amountMinor} is only meaningful for capture and refund events.
 */
public record PaymentEvent(
        String eventId,
        PaymentEventType type,
        long amountMinor,
        String providerRef,
        String reasonCode,
        long occurredAtEpochSecond) {

    public PaymentEvent {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("eventId is required");
        }
        if (type == null) {
            throw new IllegalArgumentException("type is required");
        }
        if (amountMinor < 0L) {
            throw new IllegalArgumentException("event amount must not be negative");
        }
        if (providerRef == null) {
            providerRef = "";
        }
        if (reasonCode == null) {
            reasonCode = "";
        }
    }

    public static PaymentEvent of(String eventId, PaymentEventType type, long amountMinor, long at) {
        return new PaymentEvent(eventId, type, amountMinor, "", "", at);
    }

    public PaymentEvent withProviderRef(String ref) {
        return new PaymentEvent(eventId, type, amountMinor, ref, reasonCode, occurredAtEpochSecond);
    }

    public PaymentEvent withReason(String reason) {
        return new PaymentEvent(eventId, type, amountMinor, providerRef, reason, occurredAtEpochSecond);
    }
}
