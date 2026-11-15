package com.agentpaygate.gateway.repo;

import com.agentpaygate.policy.Mandate;
import com.agentpaygate.policy.SignedMandate;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Signed mandates as uploaded by the operator. Stored with their signature so they can be re-verified. */
@Repository
public class MandateRepository {

    private final JdbcTemplate jdbc;

    public MandateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(SignedMandate signedMandate) {
        Mandate m = signedMandate.mandate();
        jdbc.update("""
                insert into mandates (mandate_id, agent_id, issuer_key_id, currency, max_per_transaction,
                    max_total, max_per_day, allowed_merchants, allowed_categories, not_before, expires_at,
                    nonce, signature)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                m.mandateId(), m.agentId(), m.issuerKeyId(), m.currency(), m.maxPerTransaction(),
                m.maxTotal(), m.maxPerDay(), String.join(",", m.allowedMerchants()),
                String.join(",", m.allowedCategories()), m.notBeforeEpochSecond(),
                m.expiresAtEpochSecond(), m.nonce(), signedMandate.signatureBase64());
        jdbc.update("""
                insert into mandate_spend (mandate_id, day_key) values (?, ?)
                on conflict (mandate_id) do nothing
                """, m.mandateId(), m.notBeforeEpochSecond() / 86_400L);
    }

    public Optional<SignedMandate> find(String mandateId) {
        List<SignedMandate> found = jdbc.query(
                "select * from mandates where mandate_id = ?", this::map, mandateId);
        return found.stream().findFirst();
    }

    public List<SignedMandate> listByAgent(String agentId) {
        return jdbc.query("select * from mandates where agent_id = ? order by created_at desc", this::map, agentId);
    }

    public List<Map<String, Object>> listAll() {
        return jdbc.queryForList("""
                select m.mandate_id, m.agent_id, m.currency, m.max_per_transaction, m.max_total, m.max_per_day,
                       s.spent_minor, s.reserved_minor, m.expires_at
                from mandates m join mandate_spend s on s.mandate_id = m.mandate_id
                order by m.mandate_id
                """);
    }

    public boolean exists(String mandateId) {
        Integer count = jdbc.queryForObject("select count(*) from mandates where mandate_id = ?", Integer.class, mandateId);
        return count != null && count > 0;
    }

    private SignedMandate map(ResultSet rs, int rowNum) throws SQLException {
        Mandate mandate = Mandate.of(
                rs.getString("mandate_id"),
                rs.getString("agent_id"),
                rs.getString("issuer_key_id"),
                rs.getString("currency"),
                rs.getLong("max_per_transaction"),
                rs.getLong("max_total"),
                rs.getLong("max_per_day"),
                split(rs.getString("allowed_merchants")),
                split(rs.getString("allowed_categories")),
                rs.getLong("not_before"),
                rs.getLong("expires_at"),
                rs.getString("nonce"));
        return new SignedMandate(mandate, rs.getString("signature"));
    }

    private static List<String> split(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /** Unused but kept explicit: the allowlist order in storage never affects a decision. */
    static TreeSet<String> sorted(Iterable<String> values) {
        TreeSet<String> set = new TreeSet<>();
        values.forEach(set::add);
        return set;
    }
}
