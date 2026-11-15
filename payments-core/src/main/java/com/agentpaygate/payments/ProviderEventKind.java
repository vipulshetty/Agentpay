package com.agentpaygate.payments;

/** The kinds of notifications a payment provider can send us. */
public enum ProviderEventKind {
    /** The provider authorised the charge. */
    AUTHORIZED,
    /** The provider captured the charge. */
    CAPTURED,
    /** The provider declined or failed the operation. */
    FAILED,
    /** The provider refunded (part or all of) the charge. */
    REFUNDED,
    /** The provider voided the authorisation. */
    VOIDED
}
