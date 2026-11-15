package com.agentpaygate.payments;

/** Events that move a payment through its state machine, with the state each one targets. */
public enum PaymentEventType {

    SUBMIT(PaymentState.REQUESTED),
    POLICY_DENIED(PaymentState.DENIED),
    AUTH_STARTED(PaymentState.AUTHORIZING),
    AUTH_SUCCEEDED(PaymentState.AUTHORIZED),
    AUTH_UNKNOWN(PaymentState.AUTHORIZATION_UNKNOWN),
    AUTH_FAILED(PaymentState.FAILED),
    CAPTURE_STARTED(PaymentState.CAPTURING),
    CAPTURE_SUCCEEDED(PaymentState.CAPTURED),
    CAPTURE_UNKNOWN(PaymentState.CAPTURE_UNKNOWN),
    CAPTURE_FAILED(PaymentState.FAILED),
    VOIDED(PaymentState.VOIDED),
    REFUND_STARTED(PaymentState.REFUNDING),
    REFUND_SUCCEEDED(PaymentState.REFUNDED),
    REFUND_FAILED(PaymentState.CAPTURED);

    private final PaymentState targetState;

    PaymentEventType(PaymentState targetState) {
        this.targetState = targetState;
    }

    public PaymentState targetState() {
        return targetState;
    }
}
