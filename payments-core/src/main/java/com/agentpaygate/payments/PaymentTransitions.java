package com.agentpaygate.payments;

import java.util.Map;
import java.util.Set;

/**
 * The legal transitions of the payment state machine. Everything not listed here is illegal and
 * throws, so an impossible sequence fails loudly instead of silently corrupting a payment.
 */
public final class PaymentTransitions {

    private static final Map<PaymentState, Set<PaymentState>> ALLOWED = Map.ofEntries(
            Map.entry(PaymentState.REQUESTED, Set.of(PaymentState.DENIED, PaymentState.AUTHORIZING)),
            Map.entry(PaymentState.AUTHORIZING, Set.of(
                    PaymentState.AUTHORIZED, PaymentState.AUTHORIZATION_UNKNOWN, PaymentState.FAILED)),
            Map.entry(PaymentState.AUTHORIZATION_UNKNOWN, Set.of(PaymentState.AUTHORIZED, PaymentState.FAILED)),
            Map.entry(PaymentState.AUTHORIZED, Set.of(PaymentState.CAPTURING, PaymentState.VOIDED)),
            Map.entry(PaymentState.CAPTURING, Set.of(
                    PaymentState.CAPTURED, PaymentState.CAPTURE_UNKNOWN, PaymentState.FAILED)),
            Map.entry(PaymentState.CAPTURE_UNKNOWN, Set.of(PaymentState.CAPTURED, PaymentState.FAILED)),
            Map.entry(PaymentState.CAPTURED, Set.of(PaymentState.REFUNDING)),
            Map.entry(PaymentState.REFUNDING, Set.of(PaymentState.REFUNDED, PaymentState.CAPTURED)),
            Map.entry(PaymentState.REFUNDED, Set.of(PaymentState.REFUNDING)),
            Map.entry(PaymentState.DENIED, Set.of()),
            Map.entry(PaymentState.VOIDED, Set.of()),
            Map.entry(PaymentState.FAILED, Set.of()));

    private PaymentTransitions() {
    }

    public static boolean isAllowed(PaymentState from, PaymentState to) {
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    public static Set<PaymentState> targetsFrom(PaymentState from) {
        return ALLOWED.getOrDefault(from, Set.of());
    }
}
