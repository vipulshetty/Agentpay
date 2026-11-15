package com.agentpaygate.payments;

import java.util.Set;
import java.util.TreeSet;

/**
 * Turns provider notifications into state changes, tolerating duplication and reordering.
 *
 * <p>Three guards, each for a specific failure mode:
 * <ol>
 *   <li><b>Duplicate event id</b> — remember every processed id; a repeat is a no-op. Provider
 *       webhooks are at-least-once, so this is the common case, not the exception.</li>
 *   <li><b>Stale event</b> — a notification that describes an earlier point on the happy path is
 *       ignored, so a late "authorised" cannot undo a "captured".</li>
 *   <li><b>Impossible transition</b> — anything else goes through {@link Payment#apply}, which
 *       throws for illegal moves instead of guessing.</li>
 * </ol>
 *
 * <p>A capture notification for a payment stuck in {@code CAPTURE_UNKNOWN} is the important case:
 * it is how an unknown outcome is resolved when the provider did in fact charge.
 */
public final class ProviderEventReducer {

    private final Set<String> processedEventIds = new TreeSet<>();

    public ReduceResult reduce(Payment payment, ProviderEvent event) {
        if (processedEventIds.contains(event.eventId())) {
            return ReduceResult.duplicate(payment);
        }
        if (!payment.providerRef().isBlank() && !payment.providerRef().equals(event.providerRef())) {
            return ReduceResult.stale(payment, "event refers to " + event.providerRef()
                    + " but payment is bound to " + payment.providerRef());
        }

        PaymentState target = targetStateOf(event.kind());
        if (target == payment.state()) {
            processedEventIds.add(event.eventId());
            return ReduceResult.noChange(payment, "already in " + target);
        }
        if (isStale(payment.state(), target)) {
            processedEventIds.add(event.eventId());
            return ReduceResult.stale(payment, "event targets " + target + " but payment is already " + payment.state());
        }

        Payment next = payment.apply(new PaymentEvent(event.eventId(), eventTypeOf(event.kind()),
                event.amountMinor(), event.providerRef(), "", event.occurredAtEpochSecond()));
        processedEventIds.add(event.eventId());
        return ReduceResult.applied(next);
    }

    /** Exposed for tests and for the reconciliation replay path. */
    public boolean hasProcessed(String eventId) {
        return processedEventIds.contains(eventId);
    }

    public int processedCount() {
        return processedEventIds.size();
    }

    private static boolean isStale(PaymentState current, PaymentState target) {
        if (target.progress() == 0 || current.progress() == 0) {
            return false;
        }
        return target.progress() < current.progress();
    }

    private static PaymentEventType eventTypeOf(ProviderEventKind kind) {
        return switch (kind) {
            case AUTHORIZED -> PaymentEventType.AUTH_SUCCEEDED;
            case CAPTURED -> PaymentEventType.CAPTURE_SUCCEEDED;
            case FAILED -> PaymentEventType.CAPTURE_FAILED;
            case REFUNDED -> PaymentEventType.REFUND_SUCCEEDED;
            case VOIDED -> PaymentEventType.VOIDED;
        };
    }

    private static PaymentState targetStateOf(ProviderEventKind kind) {
        return switch (kind) {
            case AUTHORIZED -> PaymentState.AUTHORIZED;
            case CAPTURED -> PaymentState.CAPTURED;
            case FAILED -> PaymentState.FAILED;
            case REFUNDED -> PaymentState.REFUNDED;
            case VOIDED -> PaymentState.VOIDED;
        };
    }
}
