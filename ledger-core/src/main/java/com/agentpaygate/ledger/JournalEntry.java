package com.agentpaygate.ledger;

import java.util.List;
import java.util.Objects;

/**
 * A balanced set of lines that posts as one unit, tagged with the business event that caused
 * it. The business event id is the ledger's idempotency key: posting the same event twice is a
 * no-op, which is what makes "each business event posts at most once" hold even when a caller
 * retries.
 */
public record JournalEntry(String businessEventId, String currency, List<Line> lines) {

    public JournalEntry {
        Objects.requireNonNull(businessEventId, "businessEventId");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(lines, "lines");
        if (businessEventId.isBlank()) {
            throw new IllegalArgumentException("businessEventId must not be blank");
        }
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("journal entry needs at least one line");
        }
        long sum = 0L;
        for (Line line : lines) {
            sum = Math.addExact(sum, line.amount());
        }
        if (sum != 0L) {
            throw new IllegalArgumentException("journal entry does not balance, sum=" + sum);
        }
        lines = List.copyOf(lines);
    }

    /** Funding: value enters the system from EXTERNAL into an agent wallet. */
    public static JournalEntry funding(String businessEventId, String currency, String agentId, long amount) {
        if (amount <= 0L) {
            throw new IllegalArgumentException("funding amount must be positive");
        }
        return new JournalEntry(businessEventId, currency, List.of(
                Line.of(Account.external(currency), Math.negateExact(amount)),
                Line.of(Account.agentWallet(agentId), amount)));
    }

    /** Capture: value moves from the agent wallet to the merchant payable account. */
    public static JournalEntry capture(String businessEventId, String currency, String agentId, String merchantId, long amount) {
        if (amount <= 0L) {
            throw new IllegalArgumentException("capture amount must be positive");
        }
        return new JournalEntry(businessEventId, currency, List.of(
                Line.of(Account.agentWallet(agentId), Math.negateExact(amount)),
                Line.of(Account.merchantPayable(merchantId), amount)));
    }

    /** Refund: value moves back from merchant payable to the agent wallet. */
    public static JournalEntry refund(String businessEventId, String currency, String agentId, String merchantId, long amount) {
        if (amount <= 0L) {
            throw new IllegalArgumentException("refund amount must be positive");
        }
        return new JournalEntry(businessEventId, currency, List.of(
                Line.of(Account.merchantPayable(merchantId), Math.negateExact(amount)),
                Line.of(Account.agentWallet(agentId), amount)));
    }
}
