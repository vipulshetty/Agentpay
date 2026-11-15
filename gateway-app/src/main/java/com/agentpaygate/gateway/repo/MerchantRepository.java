package com.agentpaygate.gateway.repo;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The merchant directory.
 *
 * <p>Its only job is to answer "what category is this merchant in" without asking the agent. A
 * mandate that allows a category is only meaningful if the category comes from a source the agent
 * cannot write, which is this table.
 */
@Repository
public class MerchantRepository {

    private final JdbcTemplate jdbc;

    public MerchantRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void upsert(String merchantId, String category) {
        jdbc.update("""
                insert into merchants (merchant_id, category) values (?, ?)
                on conflict (merchant_id) do update set category = excluded.category
                """, merchantId, category);
    }

    /** Empty when the merchant is not registered; an unknown merchant has no trusted category. */
    public Optional<String> categoryOf(String merchantId) {
        List<String> rows = jdbc.queryForList(
                "select category from merchants where merchant_id = ?", String.class, merchantId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<Map<String, Object>> list() {
        return jdbc.queryForList("select merchant_id, category, created_at from merchants order by merchant_id");
    }
}
