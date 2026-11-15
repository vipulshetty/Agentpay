package com.agentpaygate.tools;

import com.agentpaygate.sim.PlantedBug;
import com.agentpaygate.sim.SimConfig;
import com.agentpaygate.sim.Simulation;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Runs one simulation and writes its trace as JSON Lines.
 *
 * <p>The sim package cannot touch files, so this adapter does the writing. The trace is the
 * artefact a reviewer reads: every step, in order, with the virtual time it happened at.
 *
 * <pre>
 *   java -cp ... com.agentpaygate.tools.SimTraceMain 7 30 [bug] [output.jsonl]
 * </pre>
 */
public final class SimTraceMain {

    private SimTraceMain() {
    }

    public static void main(String[] args) throws IOException {
        int seed = args.length > 0 ? Integer.parseInt(args[0]) : 7;
        int steps = args.length > 1 ? Integer.parseInt(args[1]) : 30;
        PlantedBug bug = args.length > 2 ? PlantedBug.valueOf(args[2]) : PlantedBug.NONE;
        Path output = Path.of(args.length > 3 ? args[3] : "docs/evidence/phase-4-trace-sample.jsonl");

        Simulation.SimResult result = Simulation.run(new SimConfig(seed, steps, bug));
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }
        Files.writeString(output, String.join("\n", result.traceLines()) + "\n", StandardCharsets.UTF_8);

        System.out.println("seed=" + result.seed() + " steps=" + result.steps() + " bug=" + result.bug());
        System.out.println("payments=" + result.paymentsCreated() + " capturedMinor=" + result.capturedMinor());
        System.out.println("traceDigest=" + result.traceDigest());
        System.out.println("violations=" + (result.violations().isEmpty() ? "none" : result.violations()));
        System.out.println("trace written to " + output);
    }
}
