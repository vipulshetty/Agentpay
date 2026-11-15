package com.agentpaygate.gateway.support;

import org.springframework.http.HttpStatus;

/** An error with a stable machine-readable code, mapped to an HTTP status by the handler advice. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    /** Same idempotency key, different payload: a client bug or an attack, never a retry. */
    public static ApiException idempotencyConflict(String paymentId) {
        return new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                "this idempotency key was already used for a different payload (payment " + paymentId + ")");
    }

    public static ApiException notFound(String what) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", what + " not found");
    }

    public static ApiException forbidden(String what) {
        return new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", what);
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
