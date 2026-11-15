package com.agentpaygate.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class LedgerTest {

    @Test
    void fundingMovesValueFromExternalIntoWallet() {
        Ledger ledger = new Ledger("INR");
        assertThat(ledger.post(JournalEntry.funding("fund-1", "INR", "agent-a", 100_000L)))
                .isEqualTo(PostStatus.APPLIED);

        assertThat(ledger.balance(Account.agentWallet("agent-a"))).isEqualTo(100_000L);
        assertThat(ledger.balance(Account.external("INR"))).isEqualTo(-100_000L);
        assertThat(ledger.conserves()).isTrue();
    }

    @Test
    void captureMovesValueFromWalletToMerchant() {
        Ledger ledger = new Ledger("INR");
        ledger.post(JournalEntry.funding("fund-1", "INR", "agent-a", 100_000L));
        ledger.post(JournalEntry.capture("pay-1", "INR", "agent-a", "merchant-x", 25_000L));

        assertThat(ledger.balance(Account.agentWallet("agent-a"))).isEqualTo(75_000L);
        assertThat(ledger.balance(Account.merchantPayable("merchant-x"))).isEqualTo(25_000L);
        assertThat(ledger.conserves()).isTrue();
    }

    @Test
    void refundReversesACapture() {
        Ledger ledger = new Ledger("INR");
        ledger.post(JournalEntry.funding("fund-1", "INR", "agent-a", 100_000L));
        ledger.post(JournalEntry.capture("pay-1", "INR", "agent-a", "merchant-x", 40_000L));
        ledger.post(JournalEntry.refund("refund-1", "INR", "agent-a", "merchant-x", 40_000L));

        assertThat(ledger.balance(Account.agentWallet("agent-a"))).isEqualTo(100_000L);
        assertThat(ledger.balance(Account.merchantPayable("merchant-x"))).isZero();
        assertThat(ledger.conserves()).isTrue();
    }

    @Test
    void replayingABusinessEventChangesNothing() {
        Ledger ledger = new Ledger("INR");
        ledger.post(JournalEntry.funding("fund-1", "INR", "agent-a", 100_000L));
        String before = ledger.stateHash();

        assertThat(ledger.post(JournalEntry.funding("fund-1", "INR", "agent-a", 100_000L)))
                .isEqualTo(PostStatus.ALREADY_POSTED);

        assertThat(ledger.balance(Account.agentWallet("agent-a"))).isEqualTo(100_000L);
        assertThat(ledger.stateHash()).isEqualTo(before);
        assertThat(ledger.journal()).hasSize(1);
    }

    @Test
    void rejectsUnbalancedEntry() {
        assertThatThrownBy(() -> new JournalEntry("evt", "INR", java.util.List.of(
                Line.of(Account.agentWallet("a"), 100L),
                Line.of(Account.merchantPayable("m"), -99L))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not balance");
    }

    @Test
    void rejectsCaptureBeyondWalletBalance() {
        Ledger ledger = new Ledger("INR");
        ledger.post(JournalEntry.funding("fund-1", "INR", "agent-a", 1_000L));

        assertThatThrownBy(() -> ledger.post(
                JournalEntry.capture("pay-1", "INR", "agent-a", "merchant-x", 1_001L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("negative");

        assertThat(ledger.balance(Account.agentWallet("agent-a"))).isEqualTo(1_000L);
        assertThat(ledger.journal()).hasSize(1);
    }

    @Test
    void rejectsEntryInAnotherCurrency() {
        Ledger ledger = new Ledger("INR");
        assertThatThrownBy(() -> ledger.post(JournalEntry.funding("fund-1", "USD", "agent-a", 10L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("currency");
    }

    @Test
    void rejectsZeroAndNegativeAmounts() {
        assertThatThrownBy(() -> JournalEntry.funding("fund-1", "INR", "a", 0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JournalEntry.funding("fund-1", "INR", "a", -5L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Line.of(Account.agentWallet("a"), 0L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void overflowThrowsInsteadOfWrapping() {
        Ledger ledger = new Ledger("INR");
        ledger.post(JournalEntry.funding("fund-max", "INR", "agent-a", Long.MAX_VALUE));
        assertThatThrownBy(() -> ledger.post(JournalEntry.funding("fund-max-2", "INR", "agent-a", 1L)))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void stateHashIsStableForTheSameHistory() {
        Ledger first = new Ledger("INR");
        Ledger second = new Ledger("INR");
        for (Ledger ledger : java.util.List.of(first, second)) {
            ledger.post(JournalEntry.funding("fund-1", "INR", "agent-b", 500L));
            ledger.post(JournalEntry.funding("fund-2", "INR", "agent-a", 700L));
            ledger.post(JournalEntry.capture("pay-1", "INR", "agent-b", "merchant-z", 200L));
        }
        assertThat(first.stateHash()).isEqualTo(second.stateHash());
        assertThat(first.stateHash()).hasSize(64);
    }

    @Test
    void alternativeOrderOfIndependentEventsProducesDifferentHash() {
        Ledger a = new Ledger("INR");
        a.post(JournalEntry.funding("fund-1", "INR", "agent-b", 500L));
        a.post(JournalEntry.funding("fund-2", "INR", "agent-a", 700L));

        Ledger b = new Ledger("INR");
        b.post(JournalEntry.funding("fund-2", "INR", "agent-a", 700L));
        b.post(JournalEntry.funding("fund-1", "INR", "agent-b", 500L));

        assertThat(a.balances()).isEqualTo(b.balances());
        assertThat(a.stateHash()).isNotEqualTo(b.stateHash());
    }
}
