package com.agentpaygate.gateway.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Inbound webhook dedupe. The primary key on {@code event_id} makes a replay a no-op. */
@Repository
public class WebhookInboxRepository {

    private final JdbcTemplate jdbc;

    public WebhookInboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean insertIfNew(String eventId, String providerRef, String kind, long amountMinor, String payload) {
        int inserted = jdbc.update("""
                insert into webhook_inbox (event_id, provider_ref, kind, amount_minor, payload)
                values (?, ?, ?, ?, ?::jsonb)
                on conflict (event_id) do nothing
                """, eventId, providerRef, kind, amountMinor, payload);
        return inserted == 1;
    }

    public long count() {
        Long value = jdbc.queryForObject("select count(*) from webhook_inbox", Long.class);
        return value == null ? 0L : value;
    }
}
