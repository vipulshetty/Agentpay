package com.agentpaygate.gateway.repo;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Agents and their API keys. Only the SHA-256 of a key is stored. */
@Repository
public class AgentRepository {

    private final JdbcTemplate jdbc;

    public AgentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void create(String agentId, String displayName, String rawApiKey) {
        jdbc.update("insert into agents (agent_id, display_name, api_key_hash) values (?, ?, ?)",
                agentId, displayName, hash(rawApiKey));
    }

    public Optional<String> findAgentIdByApiKey(String rawApiKey) {
        List<String> ids = jdbc.queryForList(
                "select agent_id from agents where api_key_hash = ?", String.class, hash(rawApiKey));
        return ids.stream().findFirst();
    }

    public boolean exists(String agentId) {
        Integer count = jdbc.queryForObject("select count(*) from agents where agent_id = ?", Integer.class, agentId);
        return count != null && count > 0;
    }

    public List<Map<String, Object>> list() {
        return jdbc.queryForList("select agent_id, display_name, created_at from agents order by agent_id");
    }

    public static String hash(String rawApiKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawApiKey.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JLS", e);
        }
    }
}
