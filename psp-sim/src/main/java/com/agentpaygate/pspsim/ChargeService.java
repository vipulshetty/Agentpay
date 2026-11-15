package com.agentpaygate.pspsim;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * In-memory charge store.
 *
 * <p>Idempotency is per {@code idempotencyKey}: a repeated charge request returns the original
 * charge instead of creating a second one. That is the provider-side half of the double-charge
 * defence, and the reason the gateway must reuse the key after a timeout.
 */
@Service
public class ChargeService {

    private static final Logger log = LoggerFactory.getLogger(ChargeService.class);

    private final ConcurrentHashMap<String, Charge> byRef = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> refByIdemKey = new ConcurrentHashMap<>();
    private final FailureModes modes;
    private final WebhookSender webhooks;

    public ChargeService(FailureModes modes, WebhookSender webhooks) {
        this.modes = modes;
        this.webhooks = webhooks;
    }

    public record ChargeRequest(String idempotencyKey, long amountMinor, String currency,
                                String merchantId, String paymentId) {
    }

    public record RefundRequest(String idempotencyKey, String providerRef, long amountMinor) {
    }

    public Charge charge(ChargeRequest request) {
        if (request.idempotencyKey() == null || request.idempotencyKey().isBlank()) {
            throw new IllegalArgumentException("idempotencyKey is required");
        }
        if (request.amountMinor() <= 0L) {
            throw new IllegalArgumentException("amountMinor must be positive");
        }
        if (modes.serverErrors()) {
            throw new ProviderFailure(500, "provider unavailable (failure mode)");
        }
        sleep(modes.latencyMs());

        String existingRef = refByIdemKey.get(request.idempotencyKey());
        if (existingRef != null) {
            log.info("idempotent replay idempotencyKey={} providerRef={}", request.idempotencyKey(), existingRef);
            return byRef.get(existingRef);
        }

        Charge charge = new Charge("psp_" + UUID.randomUUID(), request.idempotencyKey(),
                request.amountMinor(), 0L, request.currency(), request.merchantId(),
                request.paymentId(), "CAPTURED");
        byRef.put(charge.providerRef(), charge);
        refByIdemKey.put(request.idempotencyKey(), charge.providerRef());
        log.info("charged providerRef={} amount={} paymentId={}", charge.providerRef(),
                charge.amountMinor(), charge.paymentId());

        if (modes.timeoutAfterCharge()) {
            // The charge happened, but the response is held back long enough for the caller to
            // give up, and no webhook is sent. Returning a 5xx instead would be a different
            // failure (the provider said no) and would invite a safe retry; holding the response
            // is what produces a genuine unknown outcome.
            sleep(Math.max(5_000, modes.latencyMs()));
            return charge;
        }
        webhooks.send(charge.providerRef(), "CAPTURED", charge.amountMinor());
        return charge;
    }

    public Charge refund(RefundRequest request) {
        if (modes.serverErrors()) {
            throw new ProviderFailure(500, "provider unavailable (failure mode)");
        }
        sleep(modes.latencyMs());
        Charge charge = byRef.get(request.providerRef());
        if (charge == null) {
            throw new IllegalArgumentException("unknown providerRef " + request.providerRef());
        }
        String idemRef = refByIdemKey.get(request.idempotencyKey());
        if (idemRef != null) {
            return byRef.get(idemRef);
        }
        if (charge.refundedMinor() + request.amountMinor() > charge.amountMinor()) {
            throw new IllegalArgumentException("refund exceeds captured amount");
        }
        Charge refunded = charge.withRefund(request.amountMinor());
        byRef.put(refunded.providerRef(), refunded);
        refByIdemKey.put(request.idempotencyKey(), refunded.providerRef());
        webhooks.send(refunded.providerRef(), "REFUNDED", request.amountMinor());
        return refunded;
    }

    public List<Charge> all() {
        return new ArrayList<>(byRef.values());
    }

    public Charge byProviderRef(String providerRef) {
        return byRef.get(providerRef);
    }

    private static void sleep(int millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** A provider failure with an HTTP status, mapped by the controller. */
    public static class ProviderFailure extends RuntimeException {
        private final int status;

        public ProviderFailure(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }
}
