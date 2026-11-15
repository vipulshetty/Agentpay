package com.agentpaygate.policy;

import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * A spending mandate signed by a human issuer key. AP2-inspired, not AP2-compliant: it carries
 * limits, an allowlist and a validity window, and nothing else.
 *
 * <p>Amounts are {@code long} minor units (paise/cents). There is no FX: one mandate is one
 * currency. Allowlists are kept in a sorted set so the canonical form and therefore the signature
 * are stable regardless of insertion order.
 *
 * @param mandateId           stable identifier chosen by the issuer
 * @param agentId             the only agent this mandate authorises
 * @param issuerKeyId         key id used to look up the issuer public key
 * @param currency            ISO-4217 code, only INR and USD are supported
 * @param maxPerTransaction   ceiling for a single payment, minor units
 * @param maxTotal            ceiling for the whole mandate lifetime, minor units
 * @param maxPerDay           ceiling per calendar day (UTC), minor units
 * @param allowedMerchants    merchant ids that are allowed
 * @param allowedCategories   merchant categories that are allowed
 * @param notBeforeEpochSecond validity window start, epoch seconds
 * @param expiresAtEpochSecond validity window end (exclusive), epoch seconds
 * @param nonce               single-use value that makes each mandate unique
 */
public record Mandate(
        String mandateId,
        String agentId,
        String issuerKeyId,
        String currency,
        long maxPerTransaction,
        long maxTotal,
        long maxPerDay,
        SortedSet<String> allowedMerchants,
        SortedSet<String> allowedCategories,
        long notBeforeEpochSecond,
        long expiresAtEpochSecond,
        String nonce) {

    public Mandate {
        requireCanonicalField("mandateId", mandateId);
        requireCanonicalField("agentId", agentId);
        requireCanonicalField("issuerKeyId", issuerKeyId);
        requireCanonicalField("currency", currency);
        requireCanonicalField("nonce", nonce);
        Objects.requireNonNull(allowedMerchants, "allowedMerchants");
        Objects.requireNonNull(allowedCategories, "allowedCategories");

        if (mandateId.isBlank() || agentId.isBlank() || issuerKeyId.isBlank() || nonce.isBlank()) {
            throw new IllegalArgumentException("mandate id, agent id, issuer key id and nonce are required");
        }
        if (currency.length() != 3 || !currency.equals(currency.toUpperCase(java.util.Locale.ROOT))) {
            throw new IllegalArgumentException("currency must be a 3-letter upper-case code, got " + currency);
        }
        if (maxPerTransaction <= 0L || maxTotal <= 0L || maxPerDay <= 0L) {
            throw new IllegalArgumentException("mandate limits must be positive");
        }
        if (expiresAtEpochSecond <= notBeforeEpochSecond) {
            throw new IllegalArgumentException("mandate expiry must be after notBefore");
        }
        for (String merchant : allowedMerchants) {
            requireCanonicalField("allowedMerchant", merchant);
        }
        for (String category : allowedCategories) {
            requireCanonicalField("allowedCategory", category);
        }
        allowedMerchants = java.util.Collections.unmodifiableSortedSet(new TreeSet<>(allowedMerchants));
        allowedCategories = java.util.Collections.unmodifiableSortedSet(new TreeSet<>(allowedCategories));
    }

    /**
     * Builder-style factory so callers do not have to remember the record's parameter order.
     */
    public static Mandate of(
            String mandateId,
            String agentId,
            String issuerKeyId,
            String currency,
            long maxPerTransaction,
            long maxTotal,
            long maxPerDay,
            java.util.Collection<String> allowedMerchants,
            java.util.Collection<String> allowedCategories,
            long notBeforeEpochSecond,
            long expiresAtEpochSecond,
            String nonce) {
        return new Mandate(
                mandateId,
                agentId,
                issuerKeyId,
                currency,
                maxPerTransaction,
                maxTotal,
                maxPerDay,
                new TreeSet<>(allowedMerchants),
                new TreeSet<>(allowedCategories),
                notBeforeEpochSecond,
                expiresAtEpochSecond,
                nonce);
    }

    public boolean allowsMerchant(String merchantId, String category) {
        return allowedMerchants.contains(merchantId) || allowedCategories.contains(category);
    }

    public boolean isExpired(long nowEpochSecond) {
        return nowEpochSecond >= expiresAtEpochSecond;
    }

    public boolean isNotYetValid(long nowEpochSecond) {
        return nowEpochSecond < notBeforeEpochSecond;
    }

    private static void requireCanonicalField(String name, String value) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        if (value.indexOf('|') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(name + " must not contain '|' or newlines");
        }
    }
}
