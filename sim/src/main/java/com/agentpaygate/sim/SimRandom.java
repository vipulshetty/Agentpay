package com.agentpaygate.sim;

/**
 * SplitMix64, implemented here rather than borrowed from {@code java.util}.
 *
 * <p>Two reasons: the simulator must never touch an unseeded generator, and a named algorithm
 * written down in the repository means a seed produces the same numbers on every JVM forever.
 * {@code java.util.Random} is explicitly forbidden by the determinism rule.
 */
public final class SimRandom {

    private static final long GOLDEN_GAMMA = 0x9E3779B97F4A7C15L;

    private long state;

    public SimRandom(long seed) {
        this.state = seed;
    }

    public long nextLong() {
        // SplitMix64 advances its state modulo 2^64, so wrapping is the algorithm, not a bug.
        state += GOLDEN_GAMMA;
        long z = state;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** Uniform in {@code [0, bound)}; {@code Math.floorMod} avoids the sign trap of {@code %}. */
    public int nextInt(int bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("bound must be positive, got " + bound);
        }
        return (int) Math.floorMod(nextLong(), (long) bound);
    }

    public boolean chance(int percent) {
        if (percent < 0 || percent > 100) {
            throw new IllegalArgumentException("percent must be within 0..100, got " + percent);
        }
        return nextInt(100) < percent;
    }
}
