package com.agentpaygate.sim;

import java.util.Optional;

/**
 * The properties that must hold after every step of every run.
 *
 * <p>Each invariant is checked against the whole world, not against the operation that just ran:
 * that is what catches a defect that moves money without moving the payment, or the other way
 * round. A violation carries a human sentence, because a bare name is useless in a failure report.
 */
public enum Invariant {

    LEDGER_CONSERVES("every journal entry balances and all balances including EXTERNAL sum to zero") {
        @Override
        Optional<String> check(SimWorld world) {
            return world.ledger().conserves()
                    ? Optional.empty()
                    : Optional.of("ledger does not conserve: balances sum to "
                            + world.ledger().balances().values().stream().mapToLong(Long::longValue).sum());
        }
    },

    NO_NEGATIVE_BALANCES("no account other than EXTERNAL may go negative") {
        @Override
        Optional<String> check(SimWorld world) {
            return world.ledger().noNegativeNonExternalBalances()
                    ? Optional.empty()
                    : Optional.of("a non-external account holds a negative balance");
        }
    },

    NO_REFUSED_OPERATIONS("the ledger and the state machine accept every operation the model performs") {
        @Override
        Optional<String> check(SimWorld world) {
            return world.refusals().isEmpty()
                    ? Optional.empty()
                    : Optional.of("an operation was refused: " + world.refusals().get(0));
        }
    },

    BUDGET_WITHIN_MANDATE("spent + reserved never exceeds the mandate's lifetime limit") {
        @Override
        Optional<String> check(SimWorld world) {
            long committed = world.spend().committedTotal();
            long limit = world.mandate().maxTotal();
            return committed <= limit
                    ? Optional.empty()
                    : Optional.of("committed " + committed + " exceeds mandate total " + limit);
        }
    },

    RESERVATIONS_MATCH_PAYMENTS("reserved budget equals the sum held by payments that still hold one") {
        @Override
        Optional<String> check(SimWorld world) {
            long expected = world.payments().values().stream()
                    .filter(payment -> switch (payment.state()) {
                        case REQUESTED, AUTHORIZING, AUTHORIZATION_UNKNOWN, AUTHORIZED, CAPTURING,
                             CAPTURE_UNKNOWN -> true;
                        default -> false;
                    })
                    .mapToLong(com.agentpaygate.payments.Payment::amountMinor)
                    .sum();
            long actual = world.spend().reservedTotal();
            return expected == actual
                    ? Optional.empty()
                    : Optional.of("reserved " + actual + " but open payments hold " + expected);
        }
    },

    LEDGER_MATCHES_PAYMENTS("merchant payable equals captures minus refunds across all payments") {
        @Override
        Optional<String> check(SimWorld world) {
            long fromLedger = 0L;
            for (String merchant : SimWorld.MERCHANTS) {
                fromLedger = Math.addExact(fromLedger,
                        world.ledger().balance(com.agentpaygate.ledger.Account.merchantPayable(merchant)));
            }
            long fromPayments = world.payments().values().stream()
                    .mapToLong(payment -> payment.capturedMinor() - payment.refundedMinor())
                    .sum();
            return fromLedger == fromPayments
                    ? Optional.empty()
                    : Optional.of("merchant payable is " + fromLedger + " but payments say " + fromPayments);
        }
    },

    WALLET_MATCHES_FUNDING("the agent wallet equals funding minus captures plus refunds") {
        @Override
        Optional<String> check(SimWorld world) {
            long captured = world.payments().values().stream().mapToLong(payment -> payment.capturedMinor()).sum();
            long refunded = world.payments().values().stream().mapToLong(payment -> payment.refundedMinor()).sum();
            long expected = SimWorld.FUNDING_MINOR - captured + refunded;
            long actual = world.ledger().balance(com.agentpaygate.ledger.Account.agentWallet(SimWorld.AGENT));
            return actual == expected
                    ? Optional.empty()
                    : Optional.of("wallet holds " + actual + " but funding and payments say " + expected);
        }
    },

    REFUNDS_WITHIN_CAPTURES("no payment refunds more than it captured") {
        @Override
        Optional<String> check(SimWorld world) {
            for (com.agentpaygate.payments.Payment payment : world.payments().values()) {
                if (payment.refundedMinor() > payment.capturedMinor()) {
                    return Optional.of("payment " + payment.paymentId() + " refunded "
                            + payment.refundedMinor() + " of " + payment.capturedMinor());
                }
            }
            return Optional.empty();
        }
    },

    IDEMPOTENCY_UNIQUE("one idempotency key never creates two payments") {
        @Override
        Optional<String> check(SimWorld world) {
            long distinctKeys = world.payments().values().stream()
                    .map(com.agentpaygate.payments.Payment::providerIdempotencyKey)
                    .distinct()
                    .count();
            if (distinctKeys != world.payments().size()) {
                return Optional.of("two payments share a provider idempotency key");
            }
            if (world.idempotency().paymentIds().size() != world.payments().size()) {
                return Optional.of("idempotency registry holds " + world.idempotency().paymentIds().size()
                        + " keys for " + world.payments().size() + " payments");
            }
            return Optional.empty();
        }
    };

    private final String description;

    Invariant(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }

    abstract Optional<String> check(SimWorld world);
}
