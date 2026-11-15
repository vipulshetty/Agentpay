package com.agentpaygate.gateway.service;

import com.agentpaygate.gateway.config.AppProperties;
import com.agentpaygate.gateway.repo.AuditRepository;
import com.agentpaygate.gateway.repo.LedgerRepository;
import com.agentpaygate.gateway.repo.PaymentRepository;
import com.agentpaygate.gateway.repo.SpendRepository;
import com.agentpaygate.gateway.repo.WebhookInboxRepository;
import com.agentpaygate.ledger.JournalEntry;
import com.agentpaygate.payments.Payment;
import com.agentpaygate.payments.PaymentEvent;
import com.agentpaygate.payments.PaymentEventType;
import com.agentpaygate.payments.PaymentState;
import com.agentpaygate.policy.SpendState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles provider webhooks.
 *
 * <p>Security: the signature is HMAC-SHA256 over {@code timestamp + "." + rawBody} with the shared
 * webhook secret, compared in constant time, and the timestamp must be inside the tolerance
 * window. A forged or stale signature is rejected before any state is touched.
 *
 * <p>Duplicates are absorbed by the inbox primary key on {@code event_id}, so the inbox is the
 * idempotency boundary and the payment reducer never sees the same event twice.
 */
@Service
public class WebhookService {

    private final AppProperties properties;
    private final WebhookInboxRepository inbox;
    private final PaymentRepository payments;
    private final LedgerRepository ledger;
    private final SpendRepository spend;
    private final AuditRepository audit;
    private final MeterRegistry meters;
    private final ObjectMapper mapper;
    private final Clock clock;

    public WebhookService(AppProperties properties, WebhookInboxRepository inbox, PaymentRepository payments,
                          LedgerRepository ledger, SpendRepository spend, AuditRepository audit,
                          MeterRegistry meters, ObjectMapper mapper, Clock clock) {
        this.properties = properties;
        this.inbox = inbox;
        this.payments = payments;
        this.ledger = ledger;
        this.spend = spend;
        this.audit = audit;
        this.meters = meters;
        this.mapper = mapper;
        this.clock = clock;
    }

    public enum Outcome {
        APPLIED, DUPLICATE, IGNORED_UNKNOWN_PAYMENT
    }

    @Transactional
    public Outcome handle(String rawBody, String signature, String timestampHeader) {
        verifySignature(rawBody, signature, timestampHeader);
        JsonNode event = read(rawBody);
        String eventId = required(event, "eventId");
        String providerRef = required(event, "providerRef");
        String kind = required(event, "kind");
        long amountMinor = event.path("amountMinor").asLong(0L);

        if (!inbox.insertIfNew(eventId, providerRef, kind, amountMinor, rawBody)) {
            meters.counter("agentpaygate.webhook.duplicate").increment();
            return Outcome.DUPLICATE;
        }

        PaymentRepository.PaymentRow row = payments.findByProviderRef(providerRef).orElse(null);
        if (row == null) {
            // The charge exists at the provider but the gateway has not committed its reference
            // yet (a race with the outbox worker). Reconciliation picks it up; nothing is lost.
            meters.counter("agentpaygate.webhook.unmatched").increment();
            return Outcome.IGNORED_UNKNOWN_PAYMENT;
        }

        long now = clock.instant().getEpochSecond();
        Payment current = row.toPayment();
        Payment next = switch (kind) {
            case "CAPTURED" -> PaymentProgress.captured(current,
                    amountMinor == 0L ? row.amountMinor() : amountMinor, providerRef, "wh-" + eventId, now);
            case "AUTHORIZED" -> current.state() == PaymentState.AUTHORIZING
                    ? current.apply(new PaymentEvent("wh-" + eventId + "-auth", PaymentEventType.AUTH_SUCCEEDED,
                    0L, providerRef, "", now))
                    : current;
            case "REFUNDED" -> PaymentProgress.refunded(current, amountMinor, "wh-" + eventId, now);
            case "FAILED" -> PaymentProgress.failed(current, providerRef, "provider reported failure",
                    "wh-" + eventId, now);
            default -> current;
        };

        if (next.state() == current.state() && next.capturedMinor() == current.capturedMinor()
                && next.refundedMinor() == current.refundedMinor()) {
            return Outcome.DUPLICATE;
        }

        payments.update(row.paymentId(), next, row.reasonCode());
        payments.appendEvent(row.paymentId(), new PaymentEvent("wh-" + eventId, paymentEventType(kind),
                amountMinor, providerRef, "", now));

        if (kind.equals("CAPTURED") && current.state() != PaymentState.CAPTURED) {
            ledger.post(JournalEntry.capture("capture:" + row.paymentId(), row.currency(), row.agentId(),
                    row.merchantId(), row.amountMinor()));
            SpendState before = spend.lockAndGet(row.mandateId(), SpendState.dayKeyOf(now));
            spend.save(row.mandateId(), before.capture(row.amountMinor()));
        }
        if (kind.equals("REFUNDED") && amountMinor > 0L) {
            ledger.post(JournalEntry.refund("refund:" + row.paymentId() + ":" + eventId, row.currency(),
                    row.agentId(), row.merchantId(), amountMinor));
            SpendState before = spend.lockAndGet(row.mandateId(), SpendState.dayKeyOf(now));
            spend.save(row.mandateId(), before.refund(amountMinor));
        }
        audit.append("psp-sim", "WEBHOOK_" + kind, row.paymentId(), "eventId=" + eventId + " amount=" + amountMinor);
        meters.counter("agentpaygate.webhook.applied", "kind", kind).increment();
        return Outcome.APPLIED;
    }

    private static PaymentEventType paymentEventType(String kind) {
        return switch (kind) {
            case "CAPTURED" -> PaymentEventType.CAPTURE_SUCCEEDED;
            case "AUTHORIZED" -> PaymentEventType.AUTH_SUCCEEDED;
            case "REFUNDED" -> PaymentEventType.REFUND_SUCCEEDED;
            case "FAILED" -> PaymentEventType.CAPTURE_FAILED;
            default -> PaymentEventType.CAPTURE_UNKNOWN;
        };
    }

    private void verifySignature(String rawBody, String signature, String timestampHeader) {
        if (signature == null || signature.isBlank() || timestampHeader == null) {
            throw new WebhookRejectedException("missing signature or timestamp");
        }
        long timestamp;
        try {
            timestamp = Long.parseLong(timestampHeader.trim());
        } catch (NumberFormatException e) {
            throw new WebhookRejectedException("malformed timestamp");
        }
        long now = clock.instant().getEpochSecond();
        if (Math.abs(now - timestamp) > properties.webhookToleranceSeconds()) {
            throw new WebhookRejectedException("timestamp outside tolerance window");
        }
        String expected = hmac(properties.webhookSecret(), timestamp + "." + rawBody);
        String provided = signature.startsWith("sha256=") ? signature.substring("sha256=".length()) : signature;
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8))) {
            meters.counter("agentpaygate.webhook.rejected").increment();
            throw new WebhookRejectedException("signature mismatch");
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

    private JsonNode read(String rawBody) {
        try {
            return mapper.readTree(rawBody);
        } catch (Exception e) {
            throw new WebhookRejectedException("body is not valid JSON");
        }
    }

    private static String required(JsonNode event, String field) {
        JsonNode value = event.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            throw new WebhookRejectedException("missing field " + field);
        }
        return value.asText();
    }

    public static class WebhookRejectedException extends RuntimeException {
        public WebhookRejectedException(String message) {
            super(message);
        }

        public HttpStatus status() {
            return HttpStatus.BAD_REQUEST;
        }
    }
}
