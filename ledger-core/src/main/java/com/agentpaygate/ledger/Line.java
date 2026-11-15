package com.agentpaygate.ledger;

import java.util.Objects;

/**
 * One side of a journal entry. Amounts are signed minor units: positive increases the
 * account balance, negative decreases it. The signed form keeps the zero-sum rule trivial
 * to check with {@link Math#addExact(long, long)} and makes overflow loud instead of silent.
 */
public record Line(Account account, long amount) {

    public Line {
        Objects.requireNonNull(account, "account");
        if (amount == 0L) {
            throw new IllegalArgumentException("ledger line amount must not be zero");
        }
    }

    public static Line of(Account account, long amount) {
        return new Line(account, amount);
    }

    public Line negated() {
        return new Line(account, Math.negateExact(amount));
    }
}
