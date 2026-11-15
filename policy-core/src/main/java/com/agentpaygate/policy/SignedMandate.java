package com.agentpaygate.policy;

/**
 * A mandate plus the issuer signature over its canonical form. Keeping them together means the
 * engine cannot accidentally evaluate an unsigned mandate.
 */
public record SignedMandate(Mandate mandate, String signatureBase64) {

    public SignedMandate {
        if (mandate == null) {
            throw new IllegalArgumentException("mandate is required");
        }
        if (signatureBase64 == null || signatureBase64.isBlank()) {
            throw new IllegalArgumentException("signature is required");
        }
    }
}
