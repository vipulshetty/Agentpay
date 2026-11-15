package com.agentpaygate.policy;

/**
 * What an agent asks for. {@code requestId} is our own handle; {@code idempotencyKey} is the
 * agent-scoped key that makes retries safe; {@code nonce} ties the request to the mandate so a
 * captured request cannot be replayed.
 */
public record PaymentRequest(
        String requestId,
        String idempotencyKey,
        String agentId,
        String merchantId,
        String merchantCategory,
        long amountMinor,
        String currency,
        String nonce) {

    public PaymentRequest {
        requireText("requestId", requestId);
        requireText("idempotencyKey", idempotencyKey);
        requireText("agentId", agentId);
        requireText("merchantId", merchantId);
        requireText("currency", currency);
        requireText("nonce", nonce);
        if (merchantCategory == null) {
            merchantCategory = "";
        }
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
