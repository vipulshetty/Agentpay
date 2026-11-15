package com.agentpaygate.policy;

import java.security.PublicKey;
import java.util.Set;

/**
 * The deny-by-default policy enforcement point, as a pure function.
 *
 * <p>Inputs are the signed mandate, the request, the agent's spend state and a context holding
 * the evaluation instant, the issuer key registry and whether this nonce was already used. The
 * output is an approval that reserves an amount, or a deny with a reason code. There is no
 * database, no clock and no randomness here: every call with the same arguments returns the same
 * decision.
 *
 * <p>Checks run in a fixed order and anything not explicitly allowed is denied. The engine never
 * compares a limit with a single number alone: budget checks use {@code spent + reserved}, so two
 * concurrent approvals cannot both fit into the last remaining slice of budget. The gateway adds
 * a database lock on top of this; the engine's job is to make the rule itself correct.
 */
public final class PolicyEngine {

    /** Currencies the system supports. No FX: a mandate is single-currency. */
    public static final Set<String> SUPPORTED_CURRENCIES = Set.of("INR", "USD");

    private PolicyEngine() {
    }

    public static Decision evaluate(
            SignedMandate signedMandate,
            PaymentRequest request,
            SpendState spendState,
            PolicyContext context) {

        Mandate mandate = signedMandate.mandate();

        if (!SUPPORTED_CURRENCIES.contains(mandate.currency())) {
            return Decision.deny(DenyReason.UNSUPPORTED_CURRENCY, mandate.currency());
        }
        if (!mandate.agentId().equals(request.agentId())) {
            return Decision.deny(DenyReason.AGENT_MISMATCH,
                    "mandate belongs to agent " + mandate.agentId() + ", request came from " + request.agentId());
        }

        PublicKey issuerKey = context.issuerKeys().publicKeyFor(mandate.issuerKeyId());
        if (issuerKey == null) {
            return Decision.deny(DenyReason.BAD_SIGNATURE, "unknown issuer key " + mandate.issuerKeyId());
        }
        if (!MandateSignature.verify(mandate, signedMandate.signatureBase64(), issuerKey)) {
            return Decision.deny(DenyReason.BAD_SIGNATURE, "signature does not verify for mandate " + mandate.mandateId());
        }

        if (!mandate.currency().equals(request.currency())) {
            return Decision.deny(DenyReason.CURRENCY_MISMATCH,
                    "mandate is " + mandate.currency() + ", request is " + request.currency());
        }
        if (request.amountMinor() <= 0L) {
            return Decision.deny(DenyReason.AMOUNT_NOT_POSITIVE, "amount " + request.amountMinor());
        }
        if (mandate.isExpired(context.nowEpochSecond())) {
            return Decision.deny(DenyReason.EXPIRED,
                    "mandate expired at " + mandate.expiresAtEpochSecond() + ", now " + context.nowEpochSecond());
        }
        if (mandate.isNotYetValid(context.nowEpochSecond())) {
            return Decision.deny(DenyReason.NOT_YET_VALID,
                    "mandate valid from " + mandate.notBeforeEpochSecond() + ", now " + context.nowEpochSecond());
        }
        if (context.nonceAlreadyUsed()) {
            return Decision.deny(DenyReason.REPLAYED, "nonce " + request.nonce() + " already used");
        }
        if (!mandate.allowsMerchant(request.merchantId(), request.merchantCategory())) {
            boolean merchantAllowed = !mandate.allowedMerchants().isEmpty();
            boolean categoryAllowed = !mandate.allowedCategories().isEmpty();
            if (!merchantAllowed && !categoryAllowed) {
                return Decision.deny(DenyReason.MERCHANT_NOT_ALLOWED,
                        "mandate has no allowlist, so every merchant is denied");
            }
            if (merchantAllowed && !mandate.allowedMerchants().contains(request.merchantId())) {
                return Decision.deny(DenyReason.MERCHANT_NOT_ALLOWED, request.merchantId());
            }
            return Decision.deny(DenyReason.CATEGORY_NOT_ALLOWED, request.merchantCategory());
        }

        try {
            if (request.amountMinor() > mandate.maxPerTransaction()) {
                return Decision.deny(DenyReason.AMOUNT_OVER_TX_LIMIT,
                        "amount " + request.amountMinor() + " exceeds per-transaction limit " + mandate.maxPerTransaction());
            }
            SpendState today = spendState.forDay(SpendState.dayKeyOf(context.nowEpochSecond()));
            if (Math.addExact(today.committedToday(), request.amountMinor()) > mandate.maxPerDay()) {
                return Decision.deny(DenyReason.DAILY_LIMIT,
                        "committed today " + today.committedToday() + " + " + request.amountMinor()
                                + " exceeds daily limit " + mandate.maxPerDay());
            }
            if (Math.addExact(today.committedTotal(), request.amountMinor()) > mandate.maxTotal()) {
                return Decision.deny(DenyReason.BUDGET_EXHAUSTED,
                        "committed " + today.committedTotal() + " + " + request.amountMinor()
                                + " exceeds mandate total " + mandate.maxTotal());
            }
        } catch (ArithmeticException overflow) {
            return Decision.deny(DenyReason.AMOUNT_OVERFLOW, overflow.getMessage());
        }

        return Decision.approve(request.amountMinor());
    }
}
