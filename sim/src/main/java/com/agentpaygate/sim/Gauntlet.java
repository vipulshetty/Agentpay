package com.agentpaygate.sim;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The gauntlet: prove the invariant set is strong enough to notice each planted defect.
 *
 * <p>For every bug it searches seeds until one run violates an invariant. A bug that no seed
 * catches is a hole in the invariants, not a harmless bug — the report says so and the test fails.
 */
public final class Gauntlet {

    public record Detection(PlantedBug bug, int seed, int steps, List<String> violations) {
        public String firstInvariant() {
            return violations.isEmpty() ? "" : violations.get(0).split(":")[0];
        }
    }

    public record Report(int seeds, int steps, int cleanRuns, List<String> cleanViolations,
                         Map<PlantedBug, Detection> detections) {

        public boolean allCaught() {
            for (PlantedBug bug : PlantedBug.values()) {
                if (bug != PlantedBug.NONE && !detections.containsKey(bug)) {
                    return false;
                }
            }
            return true;
        }

        public List<PlantedBug> missed() {
            return java.util.Arrays.stream(PlantedBug.values())
                    .filter(bug -> bug != PlantedBug.NONE && !detections.containsKey(bug))
                    .toList();
        }
    }

    private Gauntlet() {
    }

    public static Report run(int seeds, int steps) {
        Map<PlantedBug, Detection> detections = new TreeMap<>();
        int cleanRuns = 0;
        List<String> cleanViolations = List.of();
        for (int seed = 1; seed <= seeds; seed++) {
            Simulation.SimResult reference = Simulation.run(SimConfig.clean(seed, steps));
            if (reference.clean()) {
                cleanRuns++;
            } else if (cleanViolations.isEmpty()) {
                cleanViolations = reference.violations();
            }
            for (PlantedBug bug : PlantedBug.values()) {
                if (bug == PlantedBug.NONE || detections.containsKey(bug)) {
                    continue;
                }
                Simulation.SimResult result = Simulation.run(new SimConfig(seed, steps, bug));
                if (!result.clean()) {
                    detections.put(bug, new Detection(bug, seed, steps, result.violations()));
                }
            }
        }
        return new Report(seeds, steps, cleanRuns, cleanViolations, Map.copyOf(detections));
    }

    /** Human-readable evidence block, printed by the CLI that writes docs/evidence. */
    public static String report(int seeds, int steps) {
        Report report = run(seeds, steps);
        StringBuilder out = new StringBuilder();
        out.append("simulation gauntlet: seeds=").append(seeds).append(" steps=").append(steps).append('\n');
        out.append("clean runs: ").append(report.cleanRuns()).append('/').append(seeds);
        out.append(report.cleanViolations().isEmpty() ? " (no invariant violated)\n"
                : " (VIOLATIONS: " + report.cleanViolations() + ")\n");
        for (PlantedBug bug : PlantedBug.values()) {
            if (bug == PlantedBug.NONE) {
                continue;
            }
            Detection detection = report.detections().get(bug);
            out.append(String.format("%-34s ", bug.name()));
            if (detection == null) {
                out.append("NOT CAUGHT\n");
            } else {
                out.append("caught at seed=").append(detection.seed())
                        .append(" by ").append(detection.firstInvariant()).append('\n');
            }
        }
        out.append("result: ").append(report.allCaught() ? "all planted bugs caught" : "HOLES: " + report.missed());
        return out.toString();
    }
}
