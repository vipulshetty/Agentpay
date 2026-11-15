package com.agentpaygate.ledger;

import java.util.Objects;

/**
 * A ledger account: a class plus a reference (currency for EXTERNAL, agentId for a wallet,
 * merchantId for a payable). Ordering is (type, ref) so every iteration order in the ledger
 * is deterministic and independent of hash codes.
 */
public record Account(AccountType type, String ref) implements Comparable<Account> {

    public Account {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(ref, "ref");
        if (ref.isBlank()) {
            throw new IllegalArgumentException("account ref must not be blank");
        }
    }

    public static Account external(String currency) {
        return new Account(AccountType.EXTERNAL, currency);
    }

    public static Account agentWallet(String agentId) {
        return new Account(AccountType.AGENT_WALLET, agentId);
    }

    public static Account merchantPayable(String merchantId) {
        return new Account(AccountType.MERCHANT_PAYABLE, merchantId);
    }

    public boolean isExternal() {
        return type == AccountType.EXTERNAL;
    }

    @Override
    public int compareTo(Account other) {
        int byType = Integer.compare(type.ordinal(), other.type.ordinal());
        return byType != 0 ? byType : ref.compareTo(other.ref);
    }
}
