package com.agentpaygate.gateway.support;

import com.agentpaygate.policy.CanonicalForm;
import com.agentpaygate.policy.Mandate;
import com.agentpaygate.policy.SignedMandate;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * Parses the signed-mandate file produced by {@code mandate-cli}:
 *
 * <pre>
 * agentpaygate-mandate-v1
 * mandateId=...
 * ...
 * signature=base64url
 * </pre>
 *
 * <p>Parsing is strict: an unknown field, a missing field or a wrong version tag is an error. The
 * canonical form is rebuilt from the parsed mandate, so the signature is always checked over the
 * same bytes the issuer signed rather than over whatever text happened to arrive.
 */
public final class MandateFileParser {

    private static final String VERSION = "agentpaygate-mandate-v1";

    private MandateFileParser() {
    }

    public static SignedMandate parse(String text) {
        List<String> lines = new ArrayList<>(List.of(text.split("\n")));
        if (lines.isEmpty() || !lines.get(0).trim().equals(VERSION)) {
            throw ApiException.badRequest("MANDATE_MALFORMED", "missing or unsupported mandate version header");
        }
        TreeMap<String, String> fields = new TreeMap<>();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                throw ApiException.badRequest("MANDATE_MALFORMED", "malformed line: " + line);
            }
            fields.put(line.substring(0, eq), line.substring(eq + 1));
        }
        String signature = required(fields, "signature");
        Mandate mandate = Mandate.of(
                required(fields, "mandateId"),
                required(fields, "agentId"),
                required(fields, "issuerKeyId"),
                required(fields, "currency"),
                Long.parseLong(required(fields, "maxPerTransaction")),
                Long.parseLong(required(fields, "maxTotal")),
                Long.parseLong(required(fields, "maxPerDay")),
                csv(required(fields, "allowedMerchants")),
                csv(required(fields, "allowedCategories")),
                Long.parseLong(required(fields, "notBefore")),
                Long.parseLong(required(fields, "expiresAt")),
                required(fields, "nonce"));
        return new SignedMandate(mandate, signature);
    }

    /** The canonical text for storage/display, without the signature line. */
    public static String canonicalText(Mandate mandate) {
        return CanonicalForm.of(mandate);
    }

    private static String required(TreeMap<String, String> fields, String name) {
        String value = fields.get(name);
        if (value == null) {
            throw ApiException.badRequest("MANDATE_MALFORMED", "missing field " + name);
        }
        return value;
    }

    private static List<String> csv(String value) {
        if (value.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : value.split(",")) {
            if (!part.isBlank()) {
                out.add(part.trim());
            }
        }
        return out;
    }
}
