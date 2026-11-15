package com.agentpaygate.sim;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * The run's ordered record of what happened, as JSON Lines.
 *
 * <p>Exporting is a pure operation: {@link #exportJsonLines()} returns a String and the caller
 * decides where it goes, because this package is not allowed to touch files. Two runs with the
 * same seed must produce the same digest, which is what makes a failure reproducible by seed.
 */
public final class Trace {

    private final VirtualClock clock;
    private final List<String> lines = new ArrayList<>();

    public Trace(VirtualClock clock) {
        this.clock = clock;
    }

    public void line(String kind, String detail) {
        lines.add("{\"at\":" + clock.now()
                + ",\"seq\":" + lines.size()
                + ",\"kind\":\"" + escape(kind)
                + "\",\"detail\":\"" + escape(detail) + "\"}");
    }

    public List<String> lines() {
        return List.copyOf(lines);
    }

    public int size() {
        return lines.size();
    }

    /** SHA-256 over the joined trace: equal digests mean identical histories. */
    public String digest() {
        return sha256(String.join("\n", lines));
    }

    public String exportJsonLines() {
        return lines.isEmpty() ? "" : String.join("\n", lines) + "\n";
    }

    private static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append("\\u").append(HexFormat.of().toHexDigits(c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JLS", e);
        }
    }
}
