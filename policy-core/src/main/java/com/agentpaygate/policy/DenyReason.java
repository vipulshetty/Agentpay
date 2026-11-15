package com.agentpaygate.policy;

/**
 * Machine-readable reasons a payment can be denied.
 *
 * <p>Every deny path in {@link PolicyEngine} returns one of these; tests assert on the code, not
 * on message text, so the wording can change without weakening the test.
 */
public enum DenyReason {
    /** The mandate is past its expiry instant. */
    EXPIRED,
    /** The mandate is not valid yet. */
    NOT_YET_VALID,
    /** No registered issuer key, or the signature does not verify over the canonical form. */
    BAD_SIGNATURE,
    /** Requested amount exceeds the per-transaction ceiling. */
    AMOUNT_OVER_TX_LIMIT,
    /** Mandate total budget is exhausted once reserved and spent funds are counted. */
    BUDGET_EXHAUSTED,
    /** Daily budget is exhausted once reserved and spent funds for the day are counted. */
    DAILY_LIMIT,
    /** Merchant is not on the allowlist and its category is not allowed either. */
    MERCHANT_NOT_ALLOWED,
    /** Merchant category is not on the category allowlist. */
    CATEGORY_NOT_ALLOWED,
    /** Request currency does not equal the mandate currency. */
    CURRENCY_MISMATCH,
    /** Mandate currency is not one the gateway supports. */
    UNSUPPORTED_CURRENCY,
    /** This mandate nonce has already been used for a request. */
    REPLAYED,
    /** Amount was zero or negative. */
    AMOUNT_NOT_POSITIVE,
    /** Arithmetic overflowed while checking limits. */
    AMOUNT_OVERFLOW,
    /** Request names a different agent than the mandate was issued to. */
    AGENT_MISMATCH,
    /** A required field was missing or malformed. */
    MANDATE_MALFORMED
}
