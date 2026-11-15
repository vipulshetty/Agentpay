package com.agentpaygate.pspsim;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Delivers signed webhooks to the gateway.
 *
 * <p>The signature is HMAC-SHA256 over {@code timestamp + "." + body}, exactly what the gateway
 * verifies. Failure modes are applied here: duplicate delivery, delayed delivery, or none at all
 * (which is what forces the gateway to reconcile instead of trusting a callback).
 */
@Component
public class WebhookSender {

    private static final Logger log = LoggerFactory.getLogger(WebhookSender.class);

    private final RestClient client;
    private final FailureModes modes;
    private final String url;
    private final String secret;
    private final ScheduledExecutorService delayExecutor =
            Executors.newScheduledThreadPool(2, runnable -> {
                Thread thread = new Thread(runnable, "psp-webhook-delay");
                thread.setDaemon(true);
                return thread;
            });

    public WebhookSender(RestClient.Builder builder, FailureModes modes,
                         @Value("${psp.webhook-url}") String url,
                         @Value("${psp.webhook-secret}") String secret) {
        this.client = builder.build();
        this.modes = modes;
        this.url = url;
        this.secret = secret;
    }

    public void send(String providerRef, String kind, long amountMinor) {
        if (modes.dropWebhooks()) {
            log.info("webhook dropped by failure mode providerRef={} kind={}", providerRef, kind);
            return;
        }
        String body = """
                {"eventId":"%s","providerRef":"%s","kind":"%s","amountMinor":%d,"occurredAt":"%s"}
                """.formatted("evt_" + UUID.randomUUID(), providerRef, kind, amountMinor, Instant.now()).trim();

        Runnable deliver = () -> deliver(body, providerRef, kind);
        long delay = modes.webhookDelayMs();
        if (delay > 0) {
            delayExecutor.schedule(deliver, delay, TimeUnit.MILLISECONDS);
        } else {
            deliver.run();
        }
    }

    private void deliver(String body, String providerRef, String kind) {
        try {
            long timestamp = Instant.now().getEpochSecond();
            String signature = hmac(secret, timestamp + "." + body);
            client.post()
                    .uri(url)
                    .header("Content-Type", "application/json")
                    .header("X-PSP-Signature", "sha256=" + signature)
                    .header("X-PSP-Timestamp", Long.toString(timestamp))
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            log.info("webhook delivered providerRef={} kind={}", providerRef, kind);
        } catch (RuntimeException e) {
            log.warn("webhook delivery failed providerRef={} kind={}: {}", providerRef, kind, e.getMessage());
        }
        if (modes.duplicateWebhooks()) {
            try {
                long timestamp = Instant.now().getEpochSecond();
                String signature = hmac(secret, timestamp + "." + body);
                client.post()
                        .uri(url)
                        .header("Content-Type", "application/json")
                        .header("X-PSP-Signature", "sha256=" + signature)
                        .header("X-PSP-Timestamp", Long.toString(timestamp))
                        .body(body)
                        .retrieve()
                        .toBodilessEntity();
                log.info("duplicate webhook delivered providerRef={}", providerRef);
            } catch (RuntimeException e) {
                log.warn("duplicate delivery failed: {}", e.getMessage());
            }
        }
    }

    public static String hmac(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    public Map<String, Object> config() {
        return Map.of("webhookUrl", url, "duplicateWebhooks", modes.duplicateWebhooks(),
                "dropWebhooks", modes.dropWebhooks(), "webhookDelayMs", modes.webhookDelayMs());
    }
}
