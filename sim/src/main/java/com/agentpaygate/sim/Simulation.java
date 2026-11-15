package com.agentpaygate.sim;

import java.util.List;

/** Runs one configuration and reports what happened. */
public final class Simulation {

    /**
     * @param violations every invariant that failed at the end of the run, empty for a clean run
     * @param traceDigest SHA-256 of the trace: equal digests mean identical runs
     */
    public record SimResult(int seed, int steps, PlantedBug bug, List<String> violations,
                            String traceDigest, List<String> traceLines, int paymentsCreated,
                            long capturedMinor) {

        public boolean clean() {
            return violations.isEmpty();
        }
    }

    private Simulation() {
    }

    public static SimResult run(SimConfig config) {
        SimWorld world = new SimWorld(config);
        for (int i = 0; i < config.steps(); i++) {
            world.step();
        }
        long captured = world.payments().values().stream()
                .mapToLong(payment -> payment.capturedMinor())
                .sum();
        return new SimResult(config.seed(), config.steps(), config.bug(), world.violations(),
                world.trace().digest(), world.trace().lines(), world.payments().size(), captured);
    }
}
