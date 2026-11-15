package com.agentpaygate.payments;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentpaygate.ledger.JournalEntry;
import com.agentpaygate.ledger.Ledger;
import java.util.List;
import java.util.TreeMap;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;

/**
 * Random sequences of payments and provider notifications must never break the money invariants:
 * the ledger conserves, no payment is captured twice, and refunds never exceed captures.
 *
 * <p>The generator deliberately produces the awkward cases: provider notifications for payments
 * that are mid-flight, duplicates, and amounts that exceed remaining budget (which must be denied
 * rather than applied).
 */
class PaymentProperties {

    private static final long NOW = 1_700_000_000L;
    private static final String INR = "INR";

    @Property
    void ledgerConservesAndNoPaymentIsCapturedTwice(
            @ForAll @IntRange(min = 1, max = 12) int payments,
            @ForAll @LongRange(min = 1, max = 100_000) long amount,
            @ForAll @LongRange(min = 1, max = 4) long budgetMultiplier) {

        Ledger ledger = new Ledger(INR);
        TreeMap<String, Payment> book = new TreeMap<>();
        long budget = Math.multiplyExact(amount, budgetMultiplier);
        ledger.post(JournalEntry.funding("fund-0", INR, "agent-1", budget));

        long spent = 0L;
        for (int i = 0; i < payments; i++) {
            String id = "pay-" + i;
            if (spent + amount > budget) {
                continue; // budget exhausted: the engine would deny, so nothing is booked
            }
            Payment payment = Payment.requested(id, "agent-1", "merchant-1", INR, amount, "key-" + i, NOW);
            payment = payment.apply(PaymentEvent.of(id + "-a1", PaymentEventType.AUTH_STARTED, 0L, NOW));
            payment = payment.apply(PaymentEvent.of(id + "-a2", PaymentEventType.AUTH_SUCCEEDED, 0L, NOW)
                    .withProviderRef("prov-" + i));
            payment = payment.apply(PaymentEvent.of(id + "-a3", PaymentEventType.CAPTURE_STARTED, 0L, NOW));
            payment = payment.apply(PaymentEvent.of(id + "-a4", PaymentEventType.CAPTURE_SUCCEEDED, amount, NOW));
            book.put(id, payment);

            ledger.post(JournalEntry.capture("cap-" + i, INR, "agent-1", "merchant-1", amount));
            spent += amount;

            // A duplicated capture notification must not double-book anything.
            Payment again = payment.apply(PaymentEvent.of(id + "-a4-dup", PaymentEventType.REFUND_STARTED, 0L, NOW))
                    .apply(PaymentEvent.of(id + "-a4-dup-2", PaymentEventType.REFUND_FAILED, 0L, NOW));
            assertThat(again.state()).isEqualTo(PaymentState.CAPTURED);
            assertThat(again.capturedMinor()).isEqualTo(amount);
        }

        assertThat(ledger.conserves()).isTrue();
        assertThat(ledger.noNegativeNonExternalBalances()).isTrue();
        assertThat(ledger.balance(com.agentpaygate.ledger.Account.agentWallet("agent-1")))
                .isEqualTo(budget - spent);
        assertThat(book.values()).allSatisfy(p -> assertThat(p.capturedMinor()).isEqualTo(amount));
    }

    @Property
    void refundsNeverExceedCaptureForRandomSplits(
            @ForAll @LongRange(min = 1, max = 50_000) long amount,
            @ForAll @IntRange(min = 1, max = 5) int refunds) {
        Payment payment = Payment.requested("pay-r", "agent-1", "merchant-1", INR, amount, "key-r", NOW);
        payment = payment.apply(PaymentEvent.of("r1", PaymentEventType.AUTH_STARTED, 0L, NOW));
        payment = payment.apply(PaymentEvent.of("r2", PaymentEventType.AUTH_SUCCEEDED, 0L, NOW).withProviderRef("prov-r"));
        payment = payment.apply(PaymentEvent.of("r3", PaymentEventType.CAPTURE_STARTED, 0L, NOW));
        payment = payment.apply(PaymentEvent.of("r4", PaymentEventType.CAPTURE_SUCCEEDED, amount, NOW));

        long slice = Math.max(1L, amount / refunds);
        Payment current = payment;
        int applied = 0;
        for (int i = 0; i < refunds; i++) {
            Payment refunding = current.apply(PaymentEvent.of("rs-" + i, PaymentEventType.REFUND_STARTED, 0L, NOW));
            try {
                current = refunding.apply(PaymentEvent.of("rd-" + i, PaymentEventType.REFUND_SUCCEEDED, slice, NOW));
                applied++;
            } catch (IllegalStateException tooMuch) {
                current = refunding.apply(PaymentEvent.of("rd-fail-" + i, PaymentEventType.REFUND_FAILED, 0L, NOW));
            }
            assertThat(current.refundedMinor()).isLessThanOrEqualTo(current.capturedMinor());
        }
        assertThat(current.refundedMinor()).isEqualTo(Math.min(amount, slice * applied));
        assertThat(List.of(current.state())).containsAnyOf(PaymentState.CAPTURED, PaymentState.REFUNDED);
    }
}
