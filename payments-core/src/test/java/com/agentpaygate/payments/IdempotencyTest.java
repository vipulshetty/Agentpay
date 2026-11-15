package com.agentpaygate.payments;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IdempotencyTest {

    @Test
    void firstUseIsNewAndSecondUseWithSamePayloadIsAReplay() {
        IdempotencyRegistry registry = new IdempotencyRegistry();
        IdempotencyKey key = new IdempotencyKey("agent-1", "key-1");
        String hash = IdempotencyRegistry.hashOf("agent-1", "merchant-1", 5_000L, "INR");

        assertThat(registry.check(key, hash).outcome()).isEqualTo(IdempotencyCheck.Outcome.NEW);
        registry.remember(key, hash, "pay-1", "CAPTURED");

        IdempotencyCheck replay = registry.check(key, hash);
        assertThat(replay.outcome()).isEqualTo(IdempotencyCheck.Outcome.REPLAY);
        assertThat(replay.paymentId()).isEqualTo("pay-1");
        assertThat(replay.state()).isEqualTo("CAPTURED");
    }

    @Test
    void sameKeyWithDifferentPayloadIsAConflict() {
        IdempotencyRegistry registry = new IdempotencyRegistry();
        IdempotencyKey key = new IdempotencyKey("agent-1", "key-1");
        String original = IdempotencyRegistry.hashOf("agent-1", "merchant-1", 5_000L, "INR");
        registry.remember(key, original, "pay-1", "CAPTURED");

        String differentAmount = IdempotencyRegistry.hashOf("agent-1", "merchant-1", 900_000L, "INR");
        IdempotencyCheck conflict = registry.check(key, differentAmount);
        assertThat(conflict.outcome()).isEqualTo(IdempotencyCheck.Outcome.CONFLICT);
        assertThat(conflict.paymentId()).isEqualTo("pay-1");
    }

    @Test
    void keysAreScopedPerAgent() {
        IdempotencyRegistry registry = new IdempotencyRegistry();
        String hash = IdempotencyRegistry.hashOf("agent-1", "merchant-1", 1_000L, "INR");
        registry.remember(new IdempotencyKey("agent-1", "shared"), hash, "pay-a", "CAPTURED");

        assertThat(registry.check(new IdempotencyKey("agent-2", "shared"), hash).outcome())
                .isEqualTo(IdempotencyCheck.Outcome.NEW);
    }

    @Test
    void payloadHashDependsOnEveryDecisionField() {
        String base = IdempotencyRegistry.hashOf("agent-1", "merchant-1", 1_000L, "INR");
        assertThat(IdempotencyRegistry.hashOf("agent-1", "merchant-1", 1_001L, "INR")).isNotEqualTo(base);
        assertThat(IdempotencyRegistry.hashOf("agent-1", "merchant-2", 1_000L, "INR")).isNotEqualTo(base);
        assertThat(IdempotencyRegistry.hashOf("agent-1", "merchant-1", 1_000L, "USD")).isNotEqualTo(base);
        assertThat(IdempotencyRegistry.hashOf("agent-2", "merchant-1", 1_000L, "INR")).isNotEqualTo(base);
    }
}
