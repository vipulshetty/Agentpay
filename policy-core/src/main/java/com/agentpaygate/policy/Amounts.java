package com.agentpaygate.policy;

/** Integer money helpers that fail loudly instead of wrapping. */
public final class Amounts {

    private Amounts() {
    }

    public static long requirePositive(long amount) {
        if (amount <= 0L) {
            throw new IllegalArgumentException("amount must be positive, got " + amount);
        }
        return amount;
    }

    public static long add(long left, long right) {
        return Math.addExact(left, right);
    }

    public static long multiply(long value, long factor) {
        return Math.multiplyExact(value, factor);
    }
}
