package com.agentpaygate.policy;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AmountsTest {

    @Test
    void rejectsZeroAndNegativeAmounts() {
        assertThatThrownBy(() -> Amounts.requirePositive(0L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Amounts.requirePositive(-1L)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void overflowIsAnErrorNotAWrap() {
        assertThatThrownBy(() -> Amounts.add(Long.MAX_VALUE, 1L)).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> Amounts.multiply(Long.MAX_VALUE, 2L)).isInstanceOf(ArithmeticException.class);
    }
}
