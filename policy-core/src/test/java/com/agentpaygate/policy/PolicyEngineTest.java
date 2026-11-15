package com.agentpaygate.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PolicyEngineTest {

    private static final long NOW = 2_000L;
    private static final long NOT_BEFORE = 1_000L;
    private static final long EXPIRES_AT = 5_000L;
    private static final String KEY_ID = "issuer-1";
    private static final KeyPair ISSUER = generateKeyPair();

    private static KeyPair generateKeyPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Mandate baseline() {
        return Mandate.of("mandate-1", "agent-1", KEY_ID, "INR",
                50_000L, 100_000L, 60_000L,
                List.of("merchant-a"), List.of("groceries"),
                NOT_BEFORE, EXPIRES_AT, "nonce-1");
    }

    private static SignedMandate signed(Mandate mandate) {
        return new SignedMandate(mandate, MandateSignature.sign(mandate, ISSUER.getPrivate()));
    }

    private static PaymentRequest request(long amount, String merchant, String category) {
        return new PaymentRequest("req-1", "idem-1", "agent-1", merchant, category, amount, "INR", "req-nonce-1");
    }

    private static PolicyContext context() {
        IssuerKeys keys = IssuerKeys.of(Map.of(KEY_ID, ISSUER.getPublic()));
        return PolicyContext.at(NOW, keys);
    }

    @Test
    void approvesWithinMandateAndReservesTheAmount() {
        Decision decision = PolicyEngine.evaluate(signed(baseline()), request(10_000L, "merchant-a", "groceries"),
                SpendState.empty(SpendState.dayKeyOf(NOW)), context());
        assertThat(decision.approved()).isTrue();
        assertThat(decision.reservedAmountMinor()).isEqualTo(10_000L);
    }

    @Test
    void approvesExactlyAtThePerTransactionLimit() {
        Decision decision = PolicyEngine.evaluate(signed(baseline()), request(50_000L, "merchant-a", "groceries"),
                SpendState.empty(SpendState.dayKeyOf(NOW)), context());
        assertThat(decision.approved()).isTrue();
    }

    @Test
    void approvesExactlyTheRemainingBudget() {
        Mandate dailyHigh = Mandate.of("mandate-d", "agent-1", KEY_ID, "INR",
                50_000L, 100_000L, 100_000L,
                List.of("merchant-a"), List.of("groceries"),
                NOT_BEFORE, EXPIRES_AT, "nonce-d");
        SpendState spend = new SpendState(49_000L, 1_000L, 49_000L, 1_000L, SpendState.dayKeyOf(NOW));
        Decision decision = PolicyEngine.evaluate(signed(dailyHigh), request(50_000L, "merchant-a", "groceries"),
                spend, context());
        assertThat(decision.approved()).isTrue();
    }

    @Test
    void deniesExpiredMandate() {
        PolicyContext atExpiry = PolicyContext.at(EXPIRES_AT, context().issuerKeys());
        Decision decision = PolicyEngine.evaluate(signed(baseline()), request(100L, "merchant-a", "groceries"),
                SpendState.empty(SpendState.dayKeyOf(EXPIRES_AT)), atExpiry);
        assertThat(decision.denied()).isTrue();
        assertThat(decision.reason()).isEqualTo(DenyReason.EXPIRED);
    }

    @Test
    void deniesNotYetValidMandate() {
        PolicyContext before = PolicyContext.at(NOT_BEFORE - 1, context().issuerKeys());
        Decision decision = PolicyEngine.evaluate(signed(baseline()), request(100L, "merchant-a", "groceries"),
                SpendState.empty(SpendState.dayKeyOf(NOW)), before);
        assertThat(decision.reason()).isEqualTo(DenyReason.NOT_YET_VALID);
    }

    @Test
    void deniesTamperedMandate() {
        Mandate original = baseline();
        String signature = MandateSignature.sign(original, ISSUER.getPrivate());
        Mandate tampered = Mandate.of("mandate-1", "agent-1", KEY_ID, "INR",
                5_000_000L, 100_000L, 60_000L,
                List.of("merchant-a"), List.of("groceries"),
                NOT_BEFORE, EXPIRES_AT, "nonce-1");

        Decision decision = PolicyEngine.evaluate(new SignedMandate(tampered, signature),
                request(10_000L, "merchant-a", "groceries"),
                SpendState.empty(SpendState.dayKeyOf(NOW)), context());
        assertThat(decision.reason()).isEqualTo(DenyReason.BAD_SIGNATURE);
    }

    @Test
    void deniesUnknownIssuerKey() {
        Decision decision = PolicyEngine.evaluate(signed(baseline()), request(100L, "merchant-a", "groceries"),
                SpendState.empty(SpendState.dayKeyOf(NOW)),
                PolicyContext.at(NOW, IssuerKeys.none()));
        assertThat(decision.reason()).isEqualTo(DenyReason.BAD_SIGNATURE);
    }

    @Test
    void deniesSignatureFromAnotherKey() {
        KeyPair other = generateKeyPair();
        Mandate mandate = baseline();
        String foreignSignature = MandateSignature.sign(mandate, other.getPrivate());
        Decision decision = PolicyEngine.evaluate(new SignedMandate(mandate, foreignSignature),
                request(100L, "merchant-a", "groceries"),
                SpendState.empty(SpendState.dayKeyOf(NOW)), context());
        assertThat(decision.reason()).isEqualTo(DenyReason.BAD_SIGNATURE);
    }

    @Test
    void deniesAmountOverTransactionLimit() {
        Decision decision = PolicyEngine.evaluate(signed(baseline()), request(50_001L, "merchant-a", "groceries"),
                SpendState.empty(SpendState.dayKeyOf(NOW)), context());
        assertThat(decision.reason()).isEqualTo(DenyReason.AMOUNT_OVER_TX_LIMIT);
    }

    @Test
    void deniesWhenTotalBudgetIsExhausted() {
        SpendState spend = new SpendState(90_000L, 9_000L, 10_000L, 0L, SpendState.dayKeyOf(NOW));
        Decision decision = PolicyEngine.evaluate(signed(baseline()), request(2_000L, "merchant-a", "groceries"),
                spend, context());
        assertThat(decision.reason()).isEqualTo(DenyReason.BUDGET_EXHAUSTED);
    }

    @Test
    void deniesWhenDailyBudgetIsExhausted() {
        SpendState spend = new SpendState(55_000L, 0L, 55_000L, 0L, SpendState.dayKeyOf(NOW));
        Decision decision = PolicyEngine.evaluate(signed(baseline()), request(6_000L, "merchant-a", "groceries"),
                spend, context());
        assertThat(decision.reason()).isEqualTo(DenyReason.DAILY_LIMIT);
    }

    @Test
    void dailyCountersRollOverAtTheUtcDayBoundary() {
        long today = SpendState.dayKeyOf(NOW);
        SpendState yesterday = new SpendState(100_000L, 0L, 60_000L, 0L, today - 1);
        Decision decision = PolicyEngine.evaluate(signed(baseline()), request(6_000L, "merchant-a", "groceries"),
                yesterday, context());
        // Lifetime total is still 100_000 so the daily limit no longer blocks, but the total is spent.
        assertThat(decision.reason()).isEqualTo(DenyReason.BUDGET_EXHAUSTED);
    }

    @Test
    void deniesMerchantThatIsNotAllowed() {
        Decision decision = PolicyEngine.evaluate(signed(baseline()), request(100L, "merchant-z", "other"),
                SpendState.empty(SpendState.dayKeyOf(NOW)), context());
        assertThat(decision.reason()).isEqualTo(DenyReason.MERCHANT_NOT_ALLOWED);
    }

    @Test
    void deniesCategoryThatIsNotAllowed() {
        Mandate categoriesOnly = Mandate.of("mandate-2", "agent-1", KEY_ID, "INR",
                50_000L, 100_000L, 60_000L, List.of(), List.of("groceries"),
                NOT_BEFORE, EXPIRES_AT, "nonce-2");
        Decision decision = PolicyEngine.evaluate(signed(categoriesOnly), request(100L, "merchant-z", "pharmacy"),
                SpendState.empty(SpendState.dayKeyOf(NOW)), context());
        assertThat(decision.reason()).isEqualTo(DenyReason.CATEGORY_NOT_ALLOWED);
    }

    @Test
    void deniesEverythingWhenMandateHasNoAllowlist() {
        Mandate noLists = Mandate.of("mandate-3", "agent-1", KEY_ID, "INR",
                50_000L, 100_000L, 60_000L, List.of(), List.of(),
                NOT_BEFORE, EXPIRES_AT, "nonce-3");
        Decision decision = PolicyEngine.evaluate(signed(noLists), request(100L, "merchant-a", "groceries"),
                SpendState.empty(SpendState.dayKeyOf(NOW)), context());
        assertThat(decision.reason()).isEqualTo(DenyReason.MERCHANT_NOT_ALLOWED);
    }

    @Test
    void deniesCurrencyMismatch() {
        PaymentRequest usd = new PaymentRequest("req-2", "idem-2", "agent-1", "merchant-a", "groceries",
                100L, "USD", "req-nonce-2");
        Decision decision = PolicyEngine.evaluate(signed(baseline()), usd,
                SpendState.empty(SpendState.dayKeyOf(NOW)), context());
        assertThat(decision.reason()).isEqualTo(DenyReason.CURRENCY_MISMATCH);
    }

    @Test
    void deniesUnsupportedCurrency() {
        Mandate euro = Mandate.of("mandate-eur", "agent-1", KEY_ID, "EUR",
                50_000L, 100_000L, 60_000L, List.of("merchant-a"), List.of(),
                NOT_BEFORE, EXPIRES_AT, "nonce-eur");
        PaymentRequest euroRequest = new PaymentRequest("req-3", "idem-3", "agent-1", "merchant-a", "groceries",
                100L, "EUR", "req-nonce-3");
        Decision decision = PolicyEngine.evaluate(signed(euro), euroRequest,
                SpendState.empty(SpendState.dayKeyOf(NOW)), context());
        assertThat(decision.reason()).isEqualTo(DenyReason.UNSUPPORTED_CURRENCY);
    }

    @Test
    void deniesReplayedNonce() {
        Decision decision = PolicyEngine.evaluate(signed(baseline()), request(100L, "merchant-a", "groceries"),
                SpendState.empty(SpendState.dayKeyOf(NOW)), context().withNonceAlreadyUsed(true));
        assertThat(decision.reason()).isEqualTo(DenyReason.REPLAYED);
    }

    @Test
    void deniesZeroAndNegativeAmounts() {
        assertThat(PolicyEngine.evaluate(signed(baseline()), request(0L, "merchant-a", "groceries"),
                SpendState.empty(SpendState.dayKeyOf(NOW)), context()).reason())
                .isEqualTo(DenyReason.AMOUNT_NOT_POSITIVE);
        assertThat(PolicyEngine.evaluate(signed(baseline()), request(-100L, "merchant-a", "groceries"),
                SpendState.empty(SpendState.dayKeyOf(NOW)), context()).reason())
                .isEqualTo(DenyReason.AMOUNT_NOT_POSITIVE);
    }

    @Test
    void deniesAgentMismatch() {
        PaymentRequest otherAgent = new PaymentRequest("req-4", "idem-4", "agent-2", "merchant-a", "groceries",
                100L, "INR", "req-nonce-4");
        Decision decision = PolicyEngine.evaluate(signed(baseline()), otherAgent,
                SpendState.empty(SpendState.dayKeyOf(NOW)), context());
        assertThat(decision.reason()).isEqualTo(DenyReason.AGENT_MISMATCH);
    }

    @Test
    void deniesWithOverflowReasonInsteadOfWrapping() {
        Mandate huge = Mandate.of("mandate-huge", "agent-1", KEY_ID, "INR",
                Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, List.of("merchant-a"), List.of(),
                NOT_BEFORE, EXPIRES_AT, "nonce-huge");
        SpendState spend = new SpendState(1L, Long.MAX_VALUE - 1, 1L, Long.MAX_VALUE - 1, SpendState.dayKeyOf(NOW));
        Decision decision = PolicyEngine.evaluate(signed(huge), request(10L, "merchant-a", "groceries"),
                spend, context());
        assertThat(decision.reason()).isEqualTo(DenyReason.AMOUNT_OVERFLOW);
    }
}
