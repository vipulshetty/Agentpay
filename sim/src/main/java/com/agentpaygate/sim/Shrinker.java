package com.agentpaygate.sim;

import java.util.List;

/**
 * Turns a failing run into the shortest failing run with the same seed.
 *
 * <p>Shrinking by step count is the cheapest useful reduction: fewer steps means fewer payments,
 * fewer webhooks and a trace a human can actually read. The search is a binary search that assumes
 * failure survives to longer prefixes; the caller re-runs the result and checks that the run
 * immediately shorter no longer fails, so a non-monotone case is reported rather than assumed.
 */
public final class Shrinker {

    /**
     * @param steps the smallest prefix length that still violates an invariant
     * @param minimal true when {@code steps - 1} was checked and does not fail
     */
    public record Shrunk(PlantedBug bug, int seed, int steps, List<String> violations, boolean minimal) {
    }

    private Shrinker() {
    }

    public static Shrunk shrink(PlantedBug bug, int seed, int failingSteps) {
        Simulation.SimResult failing = Simulation.run(new SimConfig(seed, failingSteps, bug));
        if (failing.clean()) {
            throw new IllegalArgumentException(
                    "run seed=" + seed + " steps=" + failingSteps + " with " + bug + " does not fail, so there is nothing to shrink");
        }
        int low = 1;
        int high = failingSteps;
        int best = failingSteps;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            Simulation.SimResult candidate = Simulation.run(new SimConfig(seed, mid, bug));
            if (candidate.clean()) {
                low = mid + 1;
            } else {
                best = mid;
                high = mid - 1;
            }
        }
        Simulation.SimResult shrunk = Simulation.run(new SimConfig(seed, best, bug));
        boolean minimal = best == 1 || Simulation.run(new SimConfig(seed, best - 1, bug)).clean();
        return new Shrunk(bug, seed, best, shrunk.violations(), minimal);
    }
}
