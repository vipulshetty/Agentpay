package com.agentpaygate.tools;

import com.agentpaygate.policy.CanonicalForm;
import com.agentpaygate.policy.Mandate;
import com.agentpaygate.policy.MandateSignature;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;
import java.util.List;

/**
 * Demo-only command line tool: generates an Ed25519 issuer key pair and signs a mandate.
 *
 * <p>This lives outside the pure cores because key generation needs a {@code SecureRandom} and
 * file access. Keys are written under {@code secrets/}, which is git-ignored: they are demo keys
 * for a simulated system, never real credentials.
 *
 * <pre>
 * java -cp mandate-cli.jar com.agentpaygate.tools.MandateToolMain keygen demo-issuer
 * java -cp mandate-cli.jar com.agentpaygate.tools.MandateToolMain sign demo-issuer \
 *      --agent agent-demo --currency INR --per-tx 250000 --total 2000000 --per-day 500000 \
 *      --merchants merchant-bookshop --categories groceries --not-before 0 --expires-at 4102444800
 * </pre>
 */
public final class MandateToolMain {

    private static final Path SECRETS = Path.of("secrets");
    private static final Path MANDATES = Path.of("demo", "mandates");

    private MandateToolMain() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            usage();
            System.exit(2);
        }
        switch (args[0]) {
            case "keygen" -> keygen(arg(args, 1, "demo-issuer"));
            case "sign" -> sign(args);
            case "canonical" -> canonical(args);
            default -> {
                usage();
                System.exit(2);
            }
        }
    }

    private static void keygen(String keyId) throws IOException {
        KeyPair pair = generateEd25519();
        Files.createDirectories(SECRETS);
        Files.writeString(SECRETS.resolve(keyId + "-private.key"), pem("PRIVATE KEY", pair.getPrivate().getEncoded()),
                StandardCharsets.US_ASCII);
        Files.writeString(SECRETS.resolve(keyId + "-public.key"), pem("PUBLIC KEY", pair.getPublic().getEncoded()),
                StandardCharsets.US_ASCII);
        System.out.println("wrote demo issuer key pair for " + keyId + " to " + SECRETS.toAbsolutePath()
                + " (demo keys only, git-ignored)");
        System.out.println("keyId=" + keyId);
    }

    private static void sign(String[] args) throws IOException {
        String keyId = arg(args, 1, "demo-issuer");
        String agentId = flag(args, "--agent", "agent-demo");
        String currency = flag(args, "--currency", "INR");
        long perTx = Long.parseLong(flag(args, "--per-tx", "250000"));
        long total = Long.parseLong(flag(args, "--total", "2000000"));
        long perDay = Long.parseLong(flag(args, "--per-day", "500000"));
        List<String> merchants = List.of(flag(args, "--merchants", "merchant-bookshop").split(","));
        List<String> categories = flag(args, "--categories", "").isBlank()
                ? List.of()
                : List.of(flag(args, "--categories", "").split(","));
        long notBefore = Long.parseLong(flag(args, "--not-before", "0"));
        long expiresAt = Long.parseLong(flag(args, "--expires-at", "4102444800"));
        String mandateId = flag(args, "--mandate-id", "mandate-" + keyId + "-" + System.nanoTime());
        String nonce = flag(args, "--nonce", "nonce-" + mandateId);

        PrivateKey privateKey = loadPrivateKey(keyId);
        Mandate mandate = Mandate.of(mandateId, agentId, keyId, currency, perTx, total, perDay,
                merchants, categories, notBefore, expiresAt, nonce);
        String signature = MandateSignature.sign(mandate, privateKey);

        Files.createDirectories(MANDATES);
        Path out = MANDATES.resolve(mandateId + ".mandate");
        Files.writeString(out, CanonicalForm.of(mandate) + "signature=" + signature + "\n", StandardCharsets.UTF_8);
        System.out.println("wrote signed mandate to " + out.toAbsolutePath());
        System.out.println("mandateId=" + mandateId);
    }

    private static void canonical(String[] args) throws IOException {
        Path file = Path.of(arg(args, 1, ""));
        String text = Files.readString(file, StandardCharsets.UTF_8);
        System.out.print(text);
    }

    private static PrivateKey loadPrivateKey(String keyId) throws IOException {
        Path path = SECRETS.resolve(keyId + "-private.key");
        if (!Files.exists(path)) {
            throw new IOException("missing " + path.toAbsolutePath() + " (run keygen first)");
        }
        String base64 = Files.readString(path, StandardCharsets.US_ASCII)
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(base64);
        try {
            return java.security.KeyFactory.getInstance("Ed25519")
                    .generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(der));
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException("cannot read private key " + path, e);
        }
    }

    static PublicKey loadPublicKey(Path path) throws IOException {
        String base64 = Files.readString(path, StandardCharsets.US_ASCII)
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(base64);
        try {
            return java.security.KeyFactory.getInstance("Ed25519")
                    .generatePublic(new java.security.spec.X509EncodedKeySpec(der));
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException("cannot read public key " + path, e);
        }
    }

    private static KeyPair generateEd25519() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 is required", e);
        }
    }

    private static String pem(String label, byte[] der) {
        return "-----BEGIN " + label + "-----\n"
                + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der)
                + "\n-----END " + label + "-----\n";
    }

    private static String arg(String[] args, int index, String fallback) {
        return args.length > index ? args[index] : fallback;
    }

    private static String flag(String[] args, String name, String fallback) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(name)) {
                return args[i + 1];
            }
        }
        return fallback;
    }

    private static void usage() {
        System.err.println("""
                usage:
                  keygen <keyId>
                  sign <keyId> [--mandate-id id] [--agent agentId] [--currency INR|USD]
                       [--per-tx n] [--total n] [--per-day n] [--merchants a,b]
                       [--categories c,d] [--not-before epoch] [--expires-at epoch] [--nonce n]
                  canonical <path-to-mandate-file>""");
    }
}
