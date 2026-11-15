package com.agentpaygate.gateway.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Mandate nonces: one nonce authorises exactly one payment, enforced by a primary key. */
@Repository
public class MandateNonceRepository {

    private final JdbcTemplate jdbc;

    public MandateNonceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Read-only check; does not consume the nonce. */
    public boolean isUsed(String mandateId, String nonce) {
        Integer count = jdbc.queryForObject(
                "select count(*) from mandate_nonces where mandate_id = ? and nonce = ?",
                Integer.class, mandateId, nonce);
        return count != null && count > 0;
    }

    /** Returns true when the nonce was free, false when it was already used. */
    public boolean register(String mandateId, String nonce, String paymentId) {
        int inserted = jdbc.update("""
                insert into mandate_nonces (mandate_id, nonce, payment_id) values (?, ?, ?)
                on conflict (mandate_id, nonce) do nothing
                """, mandateId, nonce, paymentId);
        return inserted == 1;
    }
}
