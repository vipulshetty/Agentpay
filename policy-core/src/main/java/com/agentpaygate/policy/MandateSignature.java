package com.agentpaygate.policy;

import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;

/**
 * Ed25519 signing and verification over {@link CanonicalForm}.
 *
 * <p>Ed25519 was chosen over ECDSA P-256 because it is deterministic: the same mandate signed
 * with the same key always produces the same signature bytes. That keeps demo fixtures and audit
 * records reproducible. Key generation itself is not in this module: it needs a secure random
 * source and belongs in the tooling that runs outside the pure core.
 */
public final class MandateSignature {

    private static final String ALGORITHM = "Ed25519";

    private MandateSignature() {
    }

    public static String sign(Mandate mandate, PrivateKey privateKey) {
        try {
            Signature signature = Signature.getInstance(ALGORITHM);
            signature.initSign(privateKey);
            signature.update(CanonicalForm.bytesOf(mandate));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("cannot sign mandate " + mandate.mandateId(), e);
        }
    }

    /**
     * Verifies a signature. A missing key, a wrong key type, malformed base64 and a bad signature
     * all return {@code false}: verification never throws for untrusted input, because the caller
     * turns any failure into a {@link DenyReason#BAD_SIGNATURE} deny.
     */
    public static boolean verify(Mandate mandate, String signatureBase64, PublicKey publicKey) {
        if (publicKey == null || signatureBase64 == null || signatureBase64.isBlank()) {
            return false;
        }
        try {
            byte[] raw = Base64.getUrlDecoder().decode(signatureBase64);
            Signature signature = Signature.getInstance(ALGORITHM);
            signature.initVerify(publicKey);
            signature.update(CanonicalForm.bytesOf(mandate));
            return signature.verify(raw);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }
}
