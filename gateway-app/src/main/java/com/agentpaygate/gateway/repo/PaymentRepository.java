package com.agentpaygate.gateway.repo;

import com.agentpaygate.payments.Payment;
import com.agentpaygate.payments.PaymentEvent;
import com.agentpaygate.payments.PaymentState;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Payments and their event log. State changes and ledger postings share the caller's transaction. */
@Repository
public class PaymentRepository {

    private final JdbcTemplate jdbc;

    public PaymentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record PaymentRow(
            String paymentId,
            String agentId,
            String mandateId,
            String merchantId,
            String merchantCategory,
            String currency,
            long amountMinor,
            PaymentState state,
            long capturedMinor,
            long refundedMinor,
            String providerRef,
            String providerIdemKey,
            String reasonCode,
            String requestNonce,
            Instant createdAt,
            Instant updatedAt) {

        public Payment toPayment() {
            return new Payment(paymentId, agentId, merchantId, currency, amountMinor,
                    providerRef == null ? "" : providerRef, providerIdemKey, state,
                    capturedMinor, refundedMinor, createdAt.getEpochSecond(), List.of());
        }
    }

    public void insert(PaymentRow row) {
        jdbc.update("""
                insert into payments (payment_id, agent_id, mandate_id, merchant_id, merchant_category, currency,
                    amount_minor, state, captured_minor, refunded_minor, provider_ref, provider_idem_key,
                    reason_code, request_nonce)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                row.paymentId(), row.agentId(), row.mandateId(), row.merchantId(), row.merchantCategory(),
                row.currency(), row.amountMinor(), row.state().name(), row.capturedMinor(),
                row.refundedMinor(), row.providerRef(), row.providerIdemKey(), row.reasonCode(),
                row.requestNonce());
    }

    public Optional<PaymentRow> find(String paymentId) {
        List<PaymentRow> rows = jdbc.query("select * from payments where payment_id = ?", this::map, paymentId);
        return rows.stream().findFirst();
    }

    public Optional<PaymentRow> findByProviderRef(String providerRef) {
        List<PaymentRow> rows = jdbc.query("select * from payments where provider_ref = ?", this::map, providerRef);
        return rows.stream().findFirst();
    }

    public List<PaymentRow> list(int limit) {
        return jdbc.query("select * from payments order by created_at desc limit ?", this::map, limit);
    }

    public List<PaymentRow> listByAgent(String agentId, int limit) {
        return jdbc.query("select * from payments where agent_id = ? order by created_at desc limit ?",
                this::map, agentId, limit);
    }

    public List<PaymentRow> allForReconciliation() {
        return jdbc.query("select * from payments where provider_ref is not null", this::map);
    }

    public void update(String paymentId, Payment payment, String reasonCode) {
        jdbc.update("""
                update payments set state = ?, captured_minor = ?, refunded_minor = ?, provider_ref = ?,
                    reason_code = ?, updated_at = now()
                where payment_id = ?
                """,
                payment.state().name(), payment.capturedMinor(), payment.refundedMinor(),
                payment.providerRef(), reasonCode, paymentId);
    }

    public void attachProviderRef(String paymentId, String providerRef) {
        jdbc.update("update payments set provider_ref = ?, updated_at = now() where payment_id = ?",
                providerRef, paymentId);
    }

    public void appendEvent(String paymentId, PaymentEvent event) {
        jdbc.update("""
                insert into payment_events (payment_id, event_id, type, amount_minor, provider_ref, reason_code,
                    occurred_at)
                values (?, ?, ?, ?, ?, ?, ?)
                on conflict (payment_id, event_id) do nothing
                """,
                paymentId, event.eventId(), event.type().name(), event.amountMinor(),
                event.providerRef(), event.reasonCode(),
                Timestamp.from(Instant.ofEpochSecond(event.occurredAtEpochSecond())));
    }

    public List<Map<String, Object>> events(String paymentId) {
        return jdbc.queryForList("""
                select event_id, type, amount_minor, provider_ref, reason_code, occurred_at
                from payment_events where payment_id = ? order by seq
                """, paymentId);
    }

    public Map<String, Long> countsByState() {
        Map<String, Long> counts = new java.util.TreeMap<>();
        jdbc.query("select state, count(*) as c from payments group by state order by state",
                rs -> {
                    counts.put(rs.getString("state"), rs.getLong("c"));
                });
        return counts;
    }

    public long totalCapturedMinor() {
        Long value = jdbc.queryForObject(
                "select coalesce(sum(captured_minor - refunded_minor), 0) from payments", Long.class);
        return value == null ? 0L : value;
    }

    private PaymentRow map(ResultSet rs, int rowNum) throws SQLException {
        return new PaymentRow(
                rs.getString("payment_id"),
                rs.getString("agent_id"),
                rs.getString("mandate_id"),
                rs.getString("merchant_id"),
                rs.getString("merchant_category"),
                rs.getString("currency"),
                rs.getLong("amount_minor"),
                PaymentState.valueOf(rs.getString("state")),
                rs.getLong("captured_minor"),
                rs.getLong("refunded_minor"),
                rs.getString("provider_ref"),
                rs.getString("provider_idem_key"),
                rs.getString("reason_code"),
                rs.getString("request_nonce"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
