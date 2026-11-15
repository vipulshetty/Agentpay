package com.agentpaygate.payments;

/**
 * The four discrepancy classes reconciliation can find. Nothing is ever silently dropped: every
 * difference ends up in this list, flagged either auto-resolvable or for manual review.
 */
public enum DiscrepancyType {
    /** The provider has a charge we have no payment for. */
    MISSING_INTERNAL,
    /** We have a captured payment the provider never reported. */
    MISSING_PROVIDER,
    /** Both sides know the charge but the amounts differ. */
    AMOUNT_MISMATCH,
    /** Both sides know the charge but the statuses differ. */
    STATUS_MISMATCH
}
