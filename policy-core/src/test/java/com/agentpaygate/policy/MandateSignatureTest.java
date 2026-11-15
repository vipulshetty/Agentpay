package com.agentpaygate.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class MandateSignatureTest {

    private static KeyPair keyPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Mandate mandate(long perTx, List<String> merchants, List<String> categories) {
        return Mandate.of("mandate-9", "agent-9", "issuer-9", "INR",
                perTx, 100_000L, 50_000L, merchants, categories, 1_000L, 9_000L, "nonce-9");
    }

    @Test
    void signatureVerifiesForTheSignedMandate() {
        KeyPair issuer = keyPair();
        Mandate mandate = mandate(10_000L, List.of("merchant-a"), List.of("groceries"));
        String signature = MandateSignature.sign(mandate, issuer.getPrivate());
        assertThat(MandateSignature.verify(mandate, signature, issuer.getPublic())).isTrue();
    }

    @Test
    void signatureIsDeterministic() {
        KeyPair issuer = keyPair();
        Mandate mandate = mandate(10_000L, List.of("merchant-a"), List.of("groceries"));
        assertThat(MandateSignature.sign(mandate, issuer.getPrivate()))
                .isEqualTo(MandateSignature.sign(mandate, issuer.getPrivate()));
    }

    @Test
    void anyTamperedFieldFailsVerification() {
        KeyPair issuer = keyPair();
        Mandate original = mandate(10_000L, List.of("merchant-a"), List.of("groceries"));
        String signature = MandateSignature.sign(original, issuer.getPrivate());

        assertThat(MandateSignature.verify(mandate(99_999L, List.of("merchant-a"), List.of("groceries")),
                signature, issuer.getPublic())).isFalse();
        assertThat(MandateSignature.verify(mandate(10_000L, List.of("merchant-b"), List.of("groceries")),
                signature, issuer.getPublic())).isFalse();
        assertThat(MandateSignature.verify(mandate(10_000L, List.of("merchant-a"), List.of("pharmacy")),
                signature, issuer.getPublic())).isFalse();
    }

    @Test
    void verificationIsFalseForGarbageSignatureAndMissingKey() {
        Mandate mandate = mandate(10_000L, List.of("merchant-a"), List.of("groceries"));
        assertThat(MandateSignature.verify(mandate, "not-a-signature", keyPair().getPublic())).isFalse();
        assertThat(MandateSignature.verify(mandate, "AAAA", null)).isFalse();
        assertThat(MandateSignature.verify(mandate, "", keyPair().getPublic())).isFalse();
    }

    @Test
    void canonicalFormIsStableRegardlessOfAllowlistInsertionOrder() {
        Mandate first = mandate(10_000L, List.of("merchant-b", "merchant-a"), List.of("transport", "groceries"));
        Mandate second = mandate(10_000L, List.of("merchant-a", "merchant-b"), List.of("groceries", "transport"));
        assertThat(CanonicalForm.of(first)).isEqualTo(CanonicalForm.of(second));
        assertThat(CanonicalForm.of(first)).startsWith("agentpaygate-mandate-v1\n");
    }

    @Test
    void allowlistsAreDefensivelySortedAndUnmodifiable() {
        Mandate mandate = new Mandate("m", "a", "k", "INR", 1L, 10L, 5L,
                new TreeSet<>(List.of("z", "a")), new TreeSet<>(List.of("q", "b")),
                1L, 9L, "n");
        assertThat(mandate.allowedMerchants()).containsExactly("a", "z");
        assertThatThrownBy(() -> mandate.allowedMerchants().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsMandatesWithUnsafeCanonicalCharacters() {
        assertThatThrownBy(() -> new Mandate("m|x", "a", "k", "INR", 1L, 10L, 5L,
                new TreeSet<>(), new TreeSet<>(), 1L, 9L, "n"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Mandate("m", "a", "k", "INR", 1L, 10L, 5L,
                new TreeSet<>(), new TreeSet<>(), 10L, 10L, "n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expiry");
        assertThatThrownBy(() -> new Mandate("m", "a", "k", "inr", 1L, 10L, 5L,
                new TreeSet<>(), new TreeSet<>(), 1L, 9L, "n"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
