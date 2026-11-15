package com.agentpaygate.sim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class VirtualClockTest {

    @Test
    void advancesOnlyForward() {
        VirtualClock clock = new VirtualClock(1_700_000_000L);
        clock.advanceBy(30L);
        assertThat(clock.now()).isEqualTo(1_700_000_030L);
        clock.advanceTo(1_700_000_100L);
        assertThat(clock.now()).isEqualTo(1_700_000_100L);
        assertThatThrownBy(() -> clock.advanceTo(1_699_999_999L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
