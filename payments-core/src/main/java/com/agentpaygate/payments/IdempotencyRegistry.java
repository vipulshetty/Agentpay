package com.agentpaygate.payments;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * In-memory idempotency registry: key to (payload hash, payment id).
 *
 * <p>Three outcomes, and each exists for a reason:
 * <ul>
 *   <li>{@code NEW} — first time, proceed;</li>
 *   <li>{@code REPLAY} — same key and same payload, return the stored result and change nothing;</li>
 *   <li>{@code CONFLICT} — same key but a different payload, which is a client bug or an attack;
 *       reject it instead of silently paying a different amount.</li>
 * </ul>
 *
 * <p>The gateway keeps the same rule in a PostgreSQL table with a unique constraint on
 * {@code (agent_id, idempotency_key)}; this class is the pure reference implementation that the
 * simulator drives.
 */
public final class IdempotencyRegistry {

    private final TreeMap<IdempotencyKey, Stored> records = new TreeMap<>();

    private record Stored(String payloadHash, String paymentId, String state) {
    }

    public IdempotencyCheck check(IdempotencyKey key, String payloadHash) {
        Stored stored = records.get(key);
        if (stored == null) {
            return IdempotencyCheck.fresh();
        }
        if (!stored.payloadHash().equals(payloadHash)) {
            return IdempotencyCheck.conflict(stored.payloadHash(), stored.paymentId());
        }
        return IdempotencyCheck.replay(stored.payloadHash(), stored.paymentId(), stored.state());
    }

    public void remember(IdempotencyKey key, String payloadHash, String paymentId, String state) {
        records.put(key, new Stored(payloadHash, paymentId, state));
    }

    public void updateState(IdempotencyKey key, String state) {
        Stored stored = records.get(key);
        if (stored != null) {
            records.put(key, new Stored(stored.payloadHash(), stored.paymentId(), state));
        }
    }

    public int size() {
        return records.size();
    }

    public Map<IdempotencyKey, String> paymentIds() {
        TreeMap<IdempotencyKey, String> out = new TreeMap<>();
        records.forEach((key, stored) -> out.put(key, stored.paymentId()));
        return out;
    }

    /** Deterministic payload fingerprint over the fields that decide what is being paid. */
    public static String hashOf(String agentId, String merchantId, long amountMinor, String currency) {
        String canonical = "idem-v1|" + agentId + "|" + merchantId + "|" + amountMinor + "|" + currency;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JLS", e);
        }
    }
}
