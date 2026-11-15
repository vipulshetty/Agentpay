package com.agentpaygate.sim;

/** One simulation run: a seed, a length and (optionally) a planted defect. */
public record SimConfig(int seed, int steps, PlantedBug bug) {

    public SimConfig {
        if (steps < 1) {
            throw new IllegalArgumentException("steps must be positive, got " + steps);
        }
        if (bug == null) {
            throw new IllegalArgumentException("bug is required; use PlantedBug.NONE");
        }
    }

    public static SimConfig clean(int seed, int steps) {
        return new SimConfig(seed, steps, PlantedBug.NONE);
    }

    public SimConfig withSteps(int newSteps) {
        return new SimConfig(seed, newSteps, bug);
    }
}
