package com.agentpaygate.payments;

/** One line of the provider's statement, as reconciliation sees it. */
public record ProviderStatementLine(String providerRef, long amountMinor, StatementStatus status) {

    public ProviderStatementLine {
        if (providerRef == null || providerRef.isBlank()) {
            throw new IllegalArgumentException("providerRef is required");
        }
        if (status == null) {
            throw new IllegalArgumentException("status is required");
        }
        if (amountMinor < 0L) {
            throw new IllegalArgumentException("amount must not be negative");
        }
    }
}
