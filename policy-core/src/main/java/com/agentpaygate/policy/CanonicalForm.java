package com.agentpaygate.policy;

import java.nio.charset.StandardCharsets;

/**
 * Deterministic byte encoding of a mandate for signing.
 *
 * <p>The format is line-based with a fixed field order and a version tag, so it cannot be
 * reordered or re-serialised into different bytes by a different implementation:
 *
 * <pre>
 * agentpaygate-mandate-v1
 * mandateId=...
 * agentId=...
 * issuerKeyId=...
 * currency=...
 * maxPerTransaction=...
 * maxTotal=...
 * maxPerDay=...
 * allowedMerchants=a,b
 * allowedCategories=food,transport
 * notBefore=1700000000
 * expiresAt=1700003600
 * nonce=...
 * </pre>
 *
 * <p>Ids reject {@code |} and newlines at construction, so values cannot smuggle separators into
 * the encoding. Sets are sorted before joining, so two mandates that differ only in allowlist
 * insertion order produce identical bytes.
 */
public final class CanonicalForm {

    private static final String VERSION = "agentpaygate-mandate-v1";

    private CanonicalForm() {
    }

    public static String of(Mandate mandate) {
        StringBuilder out = new StringBuilder(256);
        out.append(VERSION).append('\n');
        out.append("mandateId=").append(mandate.mandateId()).append('\n');
        out.append("agentId=").append(mandate.agentId()).append('\n');
        out.append("issuerKeyId=").append(mandate.issuerKeyId()).append('\n');
        out.append("currency=").append(mandate.currency()).append('\n');
        out.append("maxPerTransaction=").append(mandate.maxPerTransaction()).append('\n');
        out.append("maxTotal=").append(mandate.maxTotal()).append('\n');
        out.append("maxPerDay=").append(mandate.maxPerDay()).append('\n');
        out.append("allowedMerchants=").append(String.join(",", mandate.allowedMerchants())).append('\n');
        out.append("allowedCategories=").append(String.join(",", mandate.allowedCategories())).append('\n');
        out.append("notBefore=").append(mandate.notBeforeEpochSecond()).append('\n');
        out.append("expiresAt=").append(mandate.expiresAtEpochSecond()).append('\n');
        out.append("nonce=").append(mandate.nonce()).append('\n');
        return out.toString();
    }

    public static byte[] bytesOf(Mandate mandate) {
        return of(mandate).getBytes(StandardCharsets.UTF_8);
    }
}
