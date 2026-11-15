package com.agentpaygate.ledger;

/** Result of attempting to post a journal entry. */
public enum PostStatus {
    /** The entry was applied and changed balances. */
    APPLIED,
    /** The business event was already posted; nothing changed. */
    ALREADY_POSTED
}
