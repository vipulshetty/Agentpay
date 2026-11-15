package com.agentpaygate.pspsim;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** The provider's HTTP surface. Only the gateway calls this. */
@RestController
public class PspController {

    private final ChargeService charges;
    private final FailureModes modes;
    private final WebhookSender webhooks;

    public PspController(ChargeService charges, FailureModes modes, WebhookSender webhooks) {
        this.charges = charges;
        this.modes = modes;
        this.webhooks = webhooks;
    }

    @PostMapping("/v1/charges")
    public Map<String, Object> charge(@RequestBody ChargeService.ChargeRequest request) {
        Charge charge = charges.charge(request);
        return Map.of("providerRef", charge.providerRef(), "status", charge.status(),
                "amountMinor", charge.amountMinor());
    }

    @PostMapping("/v1/refunds")
    public Map<String, Object> refund(@RequestBody ChargeService.RefundRequest request) {
        Charge charge = charges.refund(request);
        return Map.of("providerRef", charge.providerRef(), "status", charge.status(),
                "refundedMinor", charge.refundedMinor());
    }

    @GetMapping("/v1/statement")
    public Map<String, Object> statement() {
        List<Map<String, Object>> lines = charges.all().stream()
                .map(charge -> {
                    Map<String, Object> line = new TreeMap<>();
                    line.put("providerRef", charge.providerRef());
                    line.put("paymentId", charge.paymentId());
                    line.put("amountMinor", charge.amountMinor());
                    line.put("status", charge.refundedMinor() > 0 ? "REFUNDED" : charge.status());
                    return line;
                })
                .toList();
        return Map.of("lines", lines, "count", lines.size());
    }

    @GetMapping("/v1/charges/{providerRef}")
    public ResponseEntity<Map<String, Object>> charge(@PathVariable String providerRef) {
        Charge charge = charges.byProviderRef(providerRef);
        if (charge == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("providerRef", charge.providerRef(), "status", charge.status(),
                "amountMinor", charge.amountMinor(), "refundedMinor", charge.refundedMinor()));
    }

    @GetMapping("/admin/failure-modes")
    public Map<String, Object> failureModes() {
        Map<String, Object> out = new TreeMap<>(modes.snapshot());
        out.putAll(webhooks.config());
        return out;
    }

    @PostMapping("/admin/failure-modes")
    public Map<String, Object> setFailureModes(@RequestBody Map<String, Object> body) {
        modes.update(
                Boolean.TRUE.equals(body.get("serverErrors")),
                Boolean.TRUE.equals(body.get("timeoutAfterCharge")),
                Boolean.TRUE.equals(body.get("duplicateWebhooks")),
                Boolean.TRUE.equals(body.get("dropWebhooks")),
                ((Number) body.getOrDefault("latencyMs", 0)).intValue(),
                ((Number) body.getOrDefault("webhookDelayMs", 0)).intValue());
        return failureModes();
    }

    @PostMapping("/admin/failure-modes/reset")
    public Map<String, Object> resetFailureModes() {
        modes.reset();
        return failureModes();
    }

    @GetMapping("/admin/charges")
    public List<Map<String, Object>> allCharges() {
        return charges.all().stream()
                .map(charge -> {
                    Map<String, Object> out = new TreeMap<>();
                    out.put("providerRef", charge.providerRef());
                    out.put("paymentId", charge.paymentId());
                    out.put("amountMinor", charge.amountMinor());
                    out.put("refundedMinor", charge.refundedMinor());
                    out.put("status", charge.status());
                    return out;
                })
                .toList();
    }

    @ExceptionHandler(ChargeService.ProviderFailure.class)
    public ResponseEntity<Map<String, Object>> providerFailure(ChargeService.ProviderFailure failure) {
        return ResponseEntity.status(failure.status())
                .body(Map.of("error", failure.getMessage(), "status", failure.status()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
    }
}
