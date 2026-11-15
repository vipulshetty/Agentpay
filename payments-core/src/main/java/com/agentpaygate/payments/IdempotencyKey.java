package com.agentpaygate.payments;

/** Agent-scoped idempotency key. Two agents may use the same key without colliding. */
public record IdempotencyKey(String agentId, String key) implements Comparable<IdempotencyKey> {

    public IdempotencyKey {
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("agentId is required");
        }
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("idempotency key is required");
        }
    }

    @Override
    public int compareTo(IdempotencyKey other) {
        int byAgent = agentId.compareTo(other.agentId);
        return byAgent != 0 ? byAgent : key.compareTo(other.key);
    }
}
