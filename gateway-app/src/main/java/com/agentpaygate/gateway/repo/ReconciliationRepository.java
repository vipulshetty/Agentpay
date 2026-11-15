package com.agentpaygate.gateway.repo;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Stored reconciliation findings, one row per discrepancy per run. */
@Repository
public class ReconciliationRepository {

    private final JdbcTemplate jdbc;

    public ReconciliationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(String runId, String type, String providerRef, String paymentId, String detail,
                     boolean autoResolvable, boolean resolved) {
        jdbc.update("""
                insert into reconciliation_discrepancies (run_id, type, provider_ref, payment_id, detail,
                    auto_resolvable, resolved)
                values (?, ?, ?, ?, ?, ?, ?)
                """, runId, type, providerRef, paymentId, detail, autoResolvable, resolved);
    }

    public List<Map<String, Object>> list(int limit) {
        return jdbc.queryForList("""
                select run_id, type, provider_ref, payment_id, detail, auto_resolvable, resolved, created_at
                from reconciliation_discrepancies order by id desc limit ?
                """, limit);
    }

    public long unresolvedCount() {
        Long value = jdbc.queryForObject(
                "select count(*) from reconciliation_discrepancies where resolved = false", Long.class);
        return value == null ? 0L : value;
    }
}
