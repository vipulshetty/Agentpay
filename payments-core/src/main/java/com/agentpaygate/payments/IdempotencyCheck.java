package com.agentpaygate.payments;

/** What the client gets back. {@code payloadHash} identifies the request body that created this. */
public record IdempotencyCheck(Outcome outcome, String payloadHash, String paymentId, String state) {

    public enum Outcome {
        /** Key was never seen: proceed and remember the result. */
        NEW,
        /** Same key and same payload: return the stored result and change nothing. */
        REPLAY,
        /** Same key, different payload: reject with IDEMPOTENCY_KEY_REUSED. */
        CONFLICT
    }

    public static IdempotencyCheck fresh() {
        return new IdempotencyCheck(Outcome.NEW, "", "", "");
    }

    public static IdempotencyCheck replay(String payloadHash, String paymentId, String state) {
        return new IdempotencyCheck(Outcome.REPLAY, payloadHash, paymentId, state);
    }

    public static IdempotencyCheck conflict(String payloadHash, String paymentId) {
        return new IdempotencyCheck(Outcome.CONFLICT, payloadHash, paymentId, "");
    }
}
