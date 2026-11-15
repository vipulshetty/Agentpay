package com.agentpaygate.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.constraints.StringLength;

/**
 * Properties of the ledger over random histories, not hand-picked scenarios.
 *
 * <p>A random history is a list of operations: fund an agent, capture for a merchant, refund a
 * capture. Refunds only ever reference an amount that a previous capture produced, so the
 * generator never has to know the running balance to produce valid input; the ledger itself
 * must reject anything that would break an invariant.
 */
class LedgerProperties {

    private static final String INR = "INR";

    @Property
    void conservationHoldsForEveryHistory(
            @ForAll @IntRange(min = 0, max = 40) int operations,
            @ForAll @LongRange(min = 1, max = 1_000_000) long amount,
            @ForAll @StringLength(min = 1, max = 5) String agentSeed,
            @ForAll @StringLength(min = 1, max = 5) String merchantSeed) {
        Ledger ledger = new Ledger(INR);
        List<Long> captured = new ArrayList<>();
        for (int i = 0; i < operations; i++) {
            int kind = i % 3;
            String agent = "agent-" + agentSeed + (i % 3);
            String merchant = "merchant-" + merchantSeed + (i % 2);
            if (kind == 0) {
                ledger.post(JournalEntry.funding("fund-" + i, INR, agent, amount));
            } else if (kind == 1) {
                try {
                    ledger.post(JournalEntry.capture("cap-" + i, INR, agent, merchant, amount));
                    captured.add(amount);
                } catch (IllegalStateException insufficientFunds) {
                    // A capture the wallet cannot cover must be rejected, never applied.
                }
            } else if (!captured.isEmpty()) {
                long refund = Math.min(amount, captured.get(captured.size() - 1));
                ledger.post(JournalEntry.refund("ref-" + i, INR, agent, merchant, refund));
            }
        }
        assertThat(ledger.conserves()).as("sum of all balances including EXTERNAL").isTrue();
        assertThat(ledger.noNegativeNonExternalBalances()).isTrue();
    }

    @Property
    void replayingEveryEntryNeverChangesTheState(
            @ForAll @IntRange(min = 1, max = 10) int count,
            @ForAll @LongRange(min = 1, max = 100_000) long amount) {
        Ledger ledger = new Ledger(INR);
        for (int i = 0; i < count; i++) {
            ledger.post(JournalEntry.funding("fund-" + i, INR, "agent-" + i, amount));
        }
        String before = ledger.stateHash();

        for (int i = 0; i < count; i++) {
            assertThat(ledger.post(JournalEntry.funding("fund-" + i, INR, "agent-" + i, amount)))
                    .isEqualTo(PostStatus.ALREADY_POSTED);
        }
        assertThat(ledger.stateHash()).isEqualTo(before);
        assertThat(ledger.journal()).hasSize(count);
    }

    @Property
    void stateHashIsDeterministicForTheSameHistory(
            @ForAll @IntRange(min = 0, max = 20) int count,
            @ForAll @LongRange(min = 1, max = 10_000) long amount) {
        String first = reproduce(count, amount).stateHash();
        String second = reproduce(count, amount).stateHash();
        assertThat(first).isEqualTo(second);
    }

    private static Ledger reproduce(int count, long amount) {
        Ledger ledger = new Ledger(INR);
        for (int i = 0; i < count; i++) {
            ledger.post(JournalEntry.funding("fund-" + i, INR, "agent-" + (i % 4), amount));
            if (i % 2 == 0) {
                ledger.post(JournalEntry.capture("cap-" + i, INR, "agent-" + (i % 4), "merchant-0", 1L));
            }
        }
        return ledger;
    }
}
