package com.agentpaygate.gateway.web;

import com.agentpaygate.gateway.service.WebhookService;
import com.agentpaygate.gateway.support.ApiException;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps errors to stable {@code code} values the demo agent and console can switch on. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> api(ApiException e) {
        return ResponseEntity.status(e.status()).body(body(e.code(), e.getMessage(), e.status()));
    }

    @ExceptionHandler(WebhookService.WebhookRejectedException.class)
    public ResponseEntity<Map<String, Object>> webhook(WebhookService.WebhookRejectedException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(body("WEBHOOK_REJECTED", e.getMessage(), HttpStatus.BAD_REQUEST));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(body("INVALID_REQUEST", e.getMessage(), HttpStatus.BAD_REQUEST));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> conflict(IllegalStateException e) {
        // Illegal transitions and ledger violations: the request was understood but cannot apply.
        log.warn("rejected by domain rule: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(body("DOMAIN_RULE_VIOLATION", e.getMessage(), HttpStatus.CONFLICT));
    }

    private static Map<String, Object> body(String code, String message, HttpStatus status) {
        Map<String, Object> out = new TreeMap<>();
        out.put("code", code);
        out.put("message", message == null ? "" : message);
        out.put("status", status.value());
        return out;
    }
}
