package com.agentpaygate.gateway.repo;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Append-only audit log with a SHA-256 hash chain.
 *
 * <p>Each row stores the hash of the previous row and its own hash over
 * {@code prevHash|actor|action|subject|detail}. Editing any stored field breaks that entry's hash
 * and every later link, which {@link #verify()} reports.
 *
 * <p>Concurrency: a single row in {@code audit_chain_head} is locked for the duration of the
 * append. Two concurrent appends therefore cannot both read the same predecessor and fork the
 * chain, which is exactly what happened before this row existed. The lock is a row lock, held
 * until the surrounding transaction commits, so it is cheap and it joins the caller's transaction
 * (an audit entry for a rolled-back action rolls back with it).
 */
@Repository
public class AuditRepository {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public AuditRepository(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public record AuditVerify(boolean valid, long entries, String brokenAtSeq, String expected, String actual) {
    }

    public void append(String actor, String action, String subject, String detail) {
        String hash = hashOf(actor, action, subject, detail);
        tx.executeWithoutResult(status -> {
            String prevHash = jdbc.queryForObject(
                    "select last_hash from audit_chain_head where id = 1 for update", String.class);
            String rowHash = hashOfChain(prevHash, actor, action, subject, detail);
            jdbc.update("""
                    insert into audit_log (actor, action, subject, detail, prev_hash, hash)
                    values (?, ?, ?, ?, ?, ?)
                    """, actor, action, subject, detail, prevHash, rowHash);
            jdbc.update("""
                    update audit_chain_head set last_hash = ?, entries = entries + 1 where id = 1
                    """, rowHash);
        });
        // `hash` is computed above only to keep the helper honest in one place; the stored value
        // comes from hashOfChain with the locked predecessor.
        if (hash.isEmpty()) {
            throw new IllegalStateException("unreachable");
        }
    }

    public AuditVerify verify() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select seq, actor, action, subject, detail, prev_hash, hash from audit_log order by seq");
        String prev = "GENESIS";
        for (Map<String, Object> row : rows) {
            String expected = hashOfChain(prev, String.valueOf(row.get("actor")),
                    String.valueOf(row.get("action")), String.valueOf(row.get("subject")),
                    String.valueOf(row.get("detail")));
            if (!prev.equals(row.get("prev_hash")) || !expected.equals(row.get("hash"))) {
                return new AuditVerify(false, rows.size(), String.valueOf(row.get("seq")), expected,
                        String.valueOf(row.get("hash")));
            }
            prev = String.valueOf(row.get("hash"));
        }
        return new AuditVerify(true, rows.size(), null, prev, prev);
    }

    public List<Map<String, Object>> list(int limit) {
        return jdbc.queryForList("select * from audit_log order by seq desc limit ?", limit);
    }

    public static String hashOfChain(String prevHash, String actor, String action, String subject, String detail) {
        return sha256(prevHash + "|" + actor + "|" + action + "|" + subject + "|" + detail);
    }

    /** Convenience for callers that need a content hash without a predecessor. */
    public static String hashOf(String actor, String action, String subject, String detail) {
        return sha256(actor + "|" + action + "|" + subject + "|" + detail);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JLS", e);
        }
    }
}
