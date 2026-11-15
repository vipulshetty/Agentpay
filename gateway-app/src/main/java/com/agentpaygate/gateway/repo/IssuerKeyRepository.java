package com.agentpaygate.gateway.repo;

import com.agentpaygate.policy.IssuerKeys;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Registered human issuer public keys, stored as DER and loaded by key id. */
@Repository
public class IssuerKeyRepository {

    private final JdbcTemplate jdbc;

    public IssuerKeyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(String keyId, PublicKey publicKey) {
        jdbc.update("""
                insert into issuer_keys (key_id, algorithm, public_key_der)
                values (?, ?, ?)
                on conflict (key_id) do update set public_key_der = excluded.public_key_der
                """, keyId, publicKey.getAlgorithm(), publicKey.getEncoded());
    }

    public Optional<PublicKey> find(String keyId) {
        List<byte[]> rows = jdbc.queryForList(
                "select public_key_der from issuer_keys where key_id = ?", byte[].class, keyId);
        return rows.stream().findFirst().map(IssuerKeyRepository::decode);
    }

    public List<Map<String, Object>> list() {
        return jdbc.queryForList("select key_id, algorithm, created_at from issuer_keys order by key_id");
    }

    /** Snapshot of every registered key, used by the pure policy engine. */
    public IssuerKeys snapshot() {
        Map<String, PublicKey> keys = new TreeMap<>();
        jdbc.query("select key_id, public_key_der from issuer_keys",
                rs -> {
                    keys.put(rs.getString("key_id"), decode(rs.getBytes("public_key_der")));
                });
        return IssuerKeys.of(keys);
    }

    private static PublicKey decode(byte[] der) {
        try {
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException("stored issuer key is not a valid Ed25519 key", e);
        }
    }
}
