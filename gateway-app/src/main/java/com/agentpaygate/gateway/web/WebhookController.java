package com.agentpaygate.gateway.web;

import com.agentpaygate.gateway.service.WebhookService;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Provider callbacks. The body is taken as a raw string so the HMAC is computed over exactly the
 * bytes the provider signed — re-serialising JSON would change whitespace and break verification.
 */
@RestController
@RequestMapping("/webhooks")
public class WebhookController {

    private final WebhookService webhooks;

    public WebhookController(WebhookService webhooks) {
        this.webhooks = webhooks;
    }

    @PostMapping("/psp")
    public ResponseEntity<Map<String, Object>> psp(
            @RequestBody String rawBody,
            @RequestHeader(value = "X-PSP-Signature", required = false) String signature,
            @RequestHeader(value = "X-PSP-Timestamp", required = false) String timestamp) {
        WebhookService.Outcome outcome = webhooks.handle(rawBody, signature, timestamp);
        return ResponseEntity.ok(Map.of("outcome", outcome.name()));
    }
}
