package com.agentpaygate.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Runtime configuration. The admin token is demo-grade: one shared secret, not a user system. */
@ConfigurationProperties(prefix = "agentpaygate")
public record AppProperties(
        String adminToken,
        String pspBaseUrl,
        int pspTimeoutMs,
        String webhookSecret,
        long webhookToleranceSeconds,
        Outbox outbox,
        Ledger ledger) {

    public record Outbox(long pollIntervalMs, int maxAttempts, boolean enabled) {
    }

    public record Ledger(String externalAccountRef) {
    }
}
