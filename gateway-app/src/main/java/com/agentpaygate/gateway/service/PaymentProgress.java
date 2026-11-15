package com.agentpaygate.gateway.service;

import com.agentpaygate.payments.Payment;
import com.agentpaygate.payments.PaymentEvent;
import com.agentpaygate.payments.PaymentEventType;

/**
 * Walks a payment forward with legal intermediate transitions.
 *
 * <p>Provider responses do not always arrive in the sequence our own state machine uses: a
 * webhook can report "captured" for a payment that is still {@code AUTHORIZING} because the
 * gateway crashed between the call and the local commit. This helper fills in the steps so the
 * transition table is never bypassed, and keeps event ids distinct per payment.
 */
public final class PaymentProgress {

    private PaymentProgress() {
    }

    public static Payment captured(Payment payment, long amountMinor, String providerRef, String eventId, long at) {
        Payment current = authorisedIfNeeded(payment, providerRef, eventId, at);
        if (current.state() == com.agentpaygate.payments.PaymentState.AUTHORIZED) {
            current = current.apply(new PaymentEvent(eventId + "-cap-start", PaymentEventType.CAPTURE_STARTED,
                    0L, providerRef, "", at));
        }
        if (current.state() == com.agentpaygate.payments.PaymentState.CAPTURED) {
            return current;
        }
        return current.apply(new PaymentEvent(eventId + "-cap", PaymentEventType.CAPTURE_SUCCEEDED,
                amountMinor, providerRef, "", at));
    }

    public static Payment captureUnknown(Payment payment, String providerRef, String eventId, long at) {
        Payment current = authorisedIfNeeded(payment, providerRef, eventId, at);
        if (current.state() == com.agentpaygate.payments.PaymentState.AUTHORIZED) {
            current = current.apply(new PaymentEvent(eventId + "-cap-start", PaymentEventType.CAPTURE_STARTED,
                    0L, providerRef, "", at));
        }
        if (current.state() == com.agentpaygate.payments.PaymentState.CAPTURE_UNKNOWN) {
            return current;
        }
        return current.apply(new PaymentEvent(eventId + "-cap-unknown", PaymentEventType.CAPTURE_UNKNOWN,
                0L, providerRef, "", at));
    }

    public static Payment failed(Payment payment, String providerRef, String reason, String eventId, long at) {
        Payment current = authorisedIfNeeded(payment, providerRef, eventId, at);
        if (current.state() == com.agentpaygate.payments.PaymentState.FAILED) {
            return current;
        }
        return current.apply(new PaymentEvent(eventId + "-failed", PaymentEventType.CAPTURE_FAILED,
                0L, providerRef, reason == null ? "" : reason, at));
    }

    public static Payment refunded(Payment payment, long amountMinor, String eventId, long at) {
        Payment current = payment;
        if (current.state() == com.agentpaygate.payments.PaymentState.CAPTURED) {
            current = current.apply(new PaymentEvent(eventId + "-refund-start", PaymentEventType.REFUND_STARTED,
                    0L, current.providerRef(), "", at));
        }
        return current.apply(new PaymentEvent(eventId + "-refund", PaymentEventType.REFUND_SUCCEEDED,
                amountMinor, current.providerRef(), "", at));
    }

    private static Payment authorisedIfNeeded(Payment payment, String providerRef, String eventId, long at) {
        Payment current = payment;
        if (current.state() == com.agentpaygate.payments.PaymentState.AUTHORIZING
                || current.state() == com.agentpaygate.payments.PaymentState.AUTHORIZATION_UNKNOWN) {
            current = current.apply(new PaymentEvent(eventId + "-auth", PaymentEventType.AUTH_SUCCEEDED,
                    0L, providerRef, "", at));
        }
        return current;
    }
}
