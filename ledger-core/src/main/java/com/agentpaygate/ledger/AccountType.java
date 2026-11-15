package com.agentpaygate.ledger;

/**
 * Account classes in the double-entry ledger.
 *
 * <p>EXTERNAL represents the outside world (funding source / clearing). It is the only
 * class allowed to run a negative balance: every unit of value inside the system was
 * funded from EXTERNAL, so the sum of all balances including EXTERNAL is zero.
 */
public enum AccountType {
    EXTERNAL,
    AGENT_WALLET,
    MERCHANT_PAYABLE
}
