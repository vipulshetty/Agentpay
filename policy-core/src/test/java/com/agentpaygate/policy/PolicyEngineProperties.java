package com.agentpaygate.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.List;
import java.util.Map;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Test;

/**
 * Properties of the policy engine over random inputs, including random sequences of requests that
 * each reserve budget on approval.
 */
class PolicyEngineProperties {

    private static final long NOW = 100_000L;
    private static final KeyPair ISSUER = keyPair();

    private static KeyPair keyPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static SignedMandate mandate(long perTx, long total, long perDay) {
        Mandate mandate = Mandate.of("mandate-p", "agent-p", "issuer-p", "INR",
                perTx, total, perDay, List.of("merchant-p"), List.of("cat-p"),
                NOW - 1_000L, NOW + 1_000_000L, "nonce-p");
        return new SignedMandate(mandate, MandateSignature.sign(mandate, ISSUER.getPrivate()));
    }

    private static PolicyContext context() {
        return PolicyContext.at(NOW, IssuerKeys.of(Map.of("issuer-p", ISSUER.getPublic())));
    }

    private static PaymentRequest request(long amount) {
        return new PaymentRequest("req", "idem", "agent-p", "merchant-p", "cat-p", amount, "INR", "req-nonce");
    }

    @Property
    void neverApprovesMoreThanAnyLimit(
            @ForAll @LongRange(min = 1, max = 1_000_000) long amount,
            @ForAll @LongRange(min = 1, max = 1_000_000) long perTx,
            @ForAll @LongRange(min = 1, max = 1_000_000) long total,
            @ForAll @LongRange(min = 1, max = 1_000_000) long perDay) {
        Decision decision = PolicyEngine.evaluate(mandate(perTx, total, perDay), request(amount),
                SpendState.empty(SpendState.dayKeyOf(NOW)), context());
        if (decision.approved()) {
            assertThat(amount).isLessThanOrEqualTo(perTx);
            assertThat(amount).isLessThanOrEqualTo(total);
            assertThat(amount).isLessThanOrEqualTo(perDay);
        }
    }

    @Property
    void committedBudgetNeverExceedsTheMandateTotal(
            @ForAll @IntRange(min = 1, max = 60) int attempts,
            @ForAll @LongRange(min = 1, max = 10_000) long amount,
            @ForAll @LongRange(min = 1, max = 100_000) long total) {
        SignedMandate mandate = mandate(100_000L, total, 100_000L);
        SpendState spend = SpendState.empty(SpendState.dayKeyOf(NOW));
        for (int i = 0; i < attempts; i++) {
            Decision decision = PolicyEngine.evaluate(mandate, request(amount), spend, context());
            if (decision.approved()) {
                spend = spend.reserve(decision.reservedAmountMinor());
            }
            assertThat(spend.committedTotal()).isLessThanOrEqualTo(total);
        }
    }

    @Property
    void expiredMandatesAreAlwaysDenied(
            @ForAll @LongRange(min = 0, max = 100_000) long nowOffset) {
        long notBefore = NOW;
        long expiresAt = NOW + 100L;
        Mandate mandate = Mandate.of("m", "agent-p", "issuer-p", "INR",
                100_000L, 100_000L, 100_000L, List.of("merchant-p"), List.of(),
                notBefore, expiresAt, "n");
        SignedMandate signed = new SignedMandate(mandate, MandateSignature.sign(mandate, ISSUER.getPrivate()));
        PolicyContext context = PolicyContext.at(expiresAt + nowOffset, IssuerKeys.of(Map.of("issuer-p", ISSUER.getPublic())));
        assertThat(PolicyEngine.evaluate(signed, request(1L), SpendState.empty(SpendState.dayKeyOf(NOW)), context).reason())
                .isEqualTo(DenyReason.EXPIRED);
    }

    @Property
    void replayingTheSameNonceIsAlwaysDenied(
            @ForAll @LongRange(min = 1, max = 50_000) long amount) {
        Decision decision = PolicyEngine.evaluate(mandate(50_000L, 100_000L, 50_000L), request(amount),
                SpendState.empty(SpendState.dayKeyOf(NOW)), context().withNonceAlreadyUsed(true));
        assertThat(decision.reason()).isEqualTo(DenyReason.REPLAYED);
    }

    @Test
    void spendStateArithmeticFailsLoudlyOnOverflow() {
        SpendState maxed = new SpendState(Long.MAX_VALUE, 0L, Long.MAX_VALUE, 0L, 0L);
        try {
            maxed.reserve(1L);
            assertThat(false).as("reserve should have thrown").isTrue();
        } catch (ArithmeticException expected) {
            assertThat(expected).hasMessageContaining("overflow");
        }
    }
}
