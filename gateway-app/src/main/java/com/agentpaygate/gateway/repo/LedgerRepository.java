package com.agentpaygate.gateway.repo;

import com.agentpaygate.ledger.Account;
import com.agentpaygate.ledger.AccountType;
import com.agentpaygate.ledger.JournalEntry;
import com.agentpaygate.ledger.Line;
import com.agentpaygate.ledger.PostStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Double-entry postings in PostgreSQL.
 *
 * <p>The unique key on {@code (business_event_id, posting_index)} is the guarantee that a business
 * event posts at most once: replaying a posting is a no-op at the database level, not just in
 * application code. Negative non-external balances are rejected before the rows are written, so a
 * capture without funding cannot corrupt the ledger.
 */
@Repository
public class LedgerRepository {

    private final JdbcTemplate jdbc;

    public LedgerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public PostStatus post(JournalEntry entry) {
        Integer existing = jdbc.queryForObject(
                "select count(*) from ledger_postings where business_event_id = ?", Integer.class,
                entry.businessEventId());
        if (existing != null && existing > 0) {
            return PostStatus.ALREADY_POSTED;
        }
        // Validate the resulting balances before writing anything.
        Map<Account, Long> projected = new TreeMap<>(balances());
        for (Line line : entry.lines()) {
            projected.merge(line.account(), line.amount(), Math::addExact);
        }
        for (Map.Entry<Account, Long> e : projected.entrySet()) {
            if (!e.getKey().isExternal() && e.getValue() < 0L) {
                throw new IllegalStateException("posting " + entry.businessEventId()
                        + " would make non-external account " + e.getKey() + " negative");
            }
        }
        int index = 0;
        for (Line line : entry.lines()) {
            jdbc.update("""
                    insert into ledger_postings (business_event_id, posting_index, account_type, account_ref,
                        amount_minor, currency)
                    values (?, ?, ?, ?, ?, ?)
                    on conflict (business_event_id, posting_index) do nothing
                    """,
                    entry.businessEventId(), index++, line.account().type().name(), line.account().ref(),
                    line.amount(), entry.currency());
        }
        return PostStatus.APPLIED;
    }

    public Map<Account, Long> balances() {
        Map<Account, Long> out = new TreeMap<>();
        jdbc.query("""
                select account_type, account_ref, sum(amount_minor) as total
                from ledger_postings group by account_type, account_ref
                order by account_type, account_ref
                """, rs -> {
            out.put(new Account(AccountType.valueOf(rs.getString("account_type")), rs.getString("account_ref")),
                    rs.getLong("total"));
        });
        return out;
    }

    public long balanceOf(Account account) {
        Long value = jdbc.queryForObject("""
                select coalesce(sum(amount_minor), 0) from ledger_postings
                where account_type = ? and account_ref = ?
                """, Long.class, account.type().name(), account.ref());
        return value == null ? 0L : value;
    }

    public boolean conserves() {
        long sum = 0L;
        for (long value : balances().values()) {
            sum = Math.addExact(sum, value);
        }
        return sum == 0L;
    }

    public boolean noNegativeNonExternalBalances() {
        return balances().entrySet().stream()
                .noneMatch(e -> !e.getKey().isExternal() && e.getValue() < 0L);
    }

    public List<Map<String, Object>> entries(int limit) {
        return jdbc.queryForList("""
                select business_event_id, posting_index, account_type, account_ref, amount_minor, currency, created_at
                from ledger_postings order by seq desc limit ?
                """, limit);
    }

    public List<Map<String, Object>> entriesForPayment(String paymentId) {
        List<Map<String, Object>> rows = new ArrayList<>(jdbc.queryForList("""
                select business_event_id, posting_index, account_type, account_ref, amount_minor, currency
                from ledger_postings where business_event_id like ? order by seq
                """, "%" + paymentId + "%"));
        return rows;
    }
}
