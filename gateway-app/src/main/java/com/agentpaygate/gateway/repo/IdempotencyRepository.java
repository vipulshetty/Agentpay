package com.agentpaygate.gateway.repo;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Idempotency keys in PostgreSQL, protected by a primary key on {@code (agent_id, idem_key)}.
 * Two simultaneous requests with the same key cannot both insert; the loser reads the winner's
 * row and returns that result instead of paying twice.
 */
@Repository
public class IdempotencyRepository {

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Stored(String payloadHash, String paymentId, String state) {
    }

    public Optional<Stored> find(String agentId, String key) {
        List<Stored> rows = jdbc.query("""
                select payload_hash, payment_id, state from idempotency_keys
                where agent_id = ? and idem_key = ?
                """, (rs, rowNum) -> new Stored(rs.getString("payload_hash"), rs.getString("payment_id"),
                rs.getString("state")), agentId, key);
        return rows.stream().findFirst();
    }

    /** Returns true when this call inserted the row, false when the key was already taken. */
    public boolean insertIfAbsent(String agentId, String key, String payloadHash, String paymentId, String state) {
        int inserted = jdbc.update("""
                insert into idempotency_keys (agent_id, idem_key, payload_hash, payment_id, state)
                values (?, ?, ?, ?, ?)
                on conflict (agent_id, idem_key) do nothing
                """, agentId, key, payloadHash, paymentId, state);
        return inserted == 1;
    }

    public void updateState(String agentId, String key, String state) {
        jdbc.update("update idempotency_keys set state = ? where agent_id = ? and idem_key = ?",
                state, agentId, key);
    }
}
