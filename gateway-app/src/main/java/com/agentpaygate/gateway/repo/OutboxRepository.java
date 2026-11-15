package com.agentpaygate.gateway.repo;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Transactional outbox: the provider call is enqueued in the same transaction that records the
 * payment, so a crash between commit and provider call loses nothing. The worker claims work with
 * {@code FOR UPDATE SKIP LOCKED}, so several gateway instances can share one table.
 */
@Repository
public class OutboxRepository {

    private final JdbcTemplate jdbc;

    public OutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Item(long id, String paymentId, String operation, String providerIdemKey,
                       String payload, int attempts) {
    }

    public void enqueue(String paymentId, String operation, String providerIdemKey, String payloadJson) {
        jdbc.update("""
                insert into outbox (payment_id, operation, provider_idem_key, payload)
                values (?, ?, ?, ?::jsonb)
                """, paymentId, operation, providerIdemKey, payloadJson);
    }

    /** Claims one due item, or returns empty. */
    public Optional<Item> claimNext() {
        return jdbc.query("""
                update outbox set status = 'IN_FLIGHT', attempts = attempts + 1, updated_at = now()
                where id = (
                    select id from outbox
                    where status = 'PENDING' and next_attempt_at <= now()
                    order by id limit 1 for update skip locked
                )
                returning id, payment_id, operation, provider_idem_key, payload::text as payload, attempts
                """, (rs, rowNum) -> new Item(
                rs.getLong("id"), rs.getString("payment_id"), rs.getString("operation"),
                rs.getString("provider_idem_key"), rs.getString("payload"), rs.getInt("attempts")))
                .stream().findFirst();
    }

    public void complete(long id) {
        jdbc.update("update outbox set status = 'SENT', updated_at = now() where id = ?", id);
    }

    public void retryLater(long id, String error, int delaySeconds) {
        jdbc.update("""
                update outbox set status = 'PENDING', last_error = ?,
                    next_attempt_at = now() + (? || ' seconds')::interval, updated_at = now()
                where id = ?
                """, truncate(error), delaySeconds, id);
    }

    public void markUnknown(long id, String error) {
        jdbc.update("update outbox set status = 'UNKNOWN_OUTCOME', last_error = ?, updated_at = now() where id = ?",
                truncate(error), id);
    }

    public record StuckPayment(String paymentId, String providerIdemKey, long amountMinor, String currency,
                               String merchantId) {
    }

    /**
     * Payments that are mid-call with no outbox work left: they were claimed by a worker that died,
     * or the worker committed before the crash. Re-enqueueing them with the same provider key is
     * what makes a crash between the provider call and the local commit recoverable.
     */
    public java.util.List<StuckPayment> authorizingWithoutOutbox() {
        return jdbc.query("""
                select p.payment_id, p.provider_idem_key, p.amount_minor, p.currency, p.merchant_id
                from payments p
                where p.state = 'AUTHORIZING'
                  and not exists (
                      select 1 from outbox o
                      where o.payment_id = p.payment_id and o.status in ('PENDING','IN_FLIGHT'))
                order by p.created_at
                """, (rs, rowNum) -> new StuckPayment(
                rs.getString("payment_id"), rs.getString("provider_idem_key"),
                rs.getLong("amount_minor"), rs.getString("currency"), rs.getString("merchant_id")));
    }

    public long pendingCount() {
        Long value = jdbc.queryForObject(
                "select count(*) from outbox where status in ('PENDING','IN_FLIGHT')", Long.class);
        return value == null ? 0L : value;
    }

    public double lagSeconds() {
        Double value = jdbc.queryForObject("""
                select coalesce(max(extract(epoch from (now() - created_at))), 0)
                from outbox where status = 'PENDING'
                """, Double.class);
        return value == null ? 0d : value;
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() > 500 ? value.substring(0, 500) : value;
    }
}
