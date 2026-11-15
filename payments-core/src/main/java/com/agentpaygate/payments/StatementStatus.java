package com.agentpaygate.payments;

/** Status as reported by the provider on its statement. */
public enum StatementStatus {
    PENDING,
    AUTHORIZED,
    CAPTURED,
    REFUNDED,
    FAILED
}
