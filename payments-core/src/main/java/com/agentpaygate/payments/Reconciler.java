package com.agentpaygate.payments;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * Compares the provider statement against our own payments and ledger view.
 *
 * <p>Rules:
 * <ul>
 *   <li>a statement line with no matching payment is {@code MISSING_INTERNAL};</li>
 *   <li>a captured (or refunded) payment with no statement line is {@code MISSING_PROVIDER};</li>
 *   <li>same charge, different amount is {@code AMOUNT_MISMATCH};</li>
 *   <li>same charge, different status is {@code STATUS_MISMATCH}.</li>
 * </ul>
 *
 * <p>The only auto-resolvable class is "we were unsure, the provider confirms it captured the
 * exact amount we expected". That is a safe resolution because it needs no money movement beyond
 * completing the capture we already reserved. Anything else is flagged, never dropped.
 */
public final class Reconciler {

    private Reconciler() {
    }

    public static List<Discrepancy> reconcile(
            List<ProviderStatementLine> statementLines,
            List<Payment> payments) {

        TreeMap<String, ProviderStatementLine> byProviderRef = new TreeMap<>();
        for (ProviderStatementLine line : statementLines) {
            byProviderRef.put(line.providerRef(), line);
        }
        TreeMap<String, Payment> paymentsByProviderRef = new TreeMap<>();
        for (Payment payment : payments) {
            if (!payment.providerRef().isBlank()) {
                paymentsByProviderRef.put(payment.providerRef(), payment);
            }
        }

        List<Discrepancy> out = new ArrayList<>();

        for (ProviderStatementLine line : byProviderRef.values()) {
            Payment payment = paymentsByProviderRef.get(line.providerRef());
            if (payment == null) {
                out.add(new Discrepancy(DiscrepancyType.MISSING_INTERNAL, line.providerRef(), "",
                        "provider reports " + line.status() + " " + line.amountMinor()
                                + " but no internal payment references it", false));
                continue;
            }
            if (payment.state() == PaymentState.CAPTURE_UNKNOWN
                    && line.status() == StatementStatus.CAPTURED
                    && line.amountMinor() == payment.amountMinor()) {
                out.add(new Discrepancy(DiscrepancyType.STATUS_MISMATCH, line.providerRef(), payment.paymentId(),
                        "unknown capture confirmed by provider, same amount: safe to resolve", true));
                continue;
            }
            if (line.status() == StatementStatus.CAPTURED && payment.state() == PaymentState.CAPTURED
                    && line.amountMinor() != payment.capturedMinor()) {
                out.add(new Discrepancy(DiscrepancyType.AMOUNT_MISMATCH, line.providerRef(), payment.paymentId(),
                        "provider captured " + line.amountMinor() + " but internal capture is "
                                + payment.capturedMinor(), false));
                continue;
            }
            if (!statusAgrees(line.status(), payment.state())) {
                out.add(new Discrepancy(DiscrepancyType.STATUS_MISMATCH, line.providerRef(), payment.paymentId(),
                        "provider says " + line.status() + ", internal state is " + payment.state(), false));
            }
        }

        for (Payment payment : paymentsByProviderRef.values()) {
            if (byProviderRef.containsKey(payment.providerRef())) {
                continue;
            }
            boolean shouldBeOnStatement = payment.state() == PaymentState.CAPTURED
                    || payment.state() == PaymentState.REFUNDED
                    || payment.state() == PaymentState.CAPTURE_UNKNOWN;
            if (shouldBeOnStatement) {
                out.add(new Discrepancy(DiscrepancyType.MISSING_PROVIDER, payment.providerRef(), payment.paymentId(),
                        "internal state " + payment.state() + " has no provider statement line", false));
            }
        }

        out.sort(Discrepancy::compareTo);
        return List.copyOf(out);
    }

    private static boolean statusAgrees(StatementStatus providerStatus, PaymentState state) {
        return switch (providerStatus) {
            case CAPTURED -> state == PaymentState.CAPTURED;
            case REFUNDED -> state == PaymentState.REFUNDED;
            case AUTHORIZED -> state == PaymentState.AUTHORIZED;
            case FAILED -> state == PaymentState.FAILED || state == PaymentState.DENIED;
            case PENDING -> state == PaymentState.AUTHORIZING
                    || state == PaymentState.AUTHORIZATION_UNKNOWN
                    || state == PaymentState.AUTHORIZED;
        };
    }
}
