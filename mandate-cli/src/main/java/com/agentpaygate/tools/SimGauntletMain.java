package com.agentpaygate.tools;

import com.agentpaygate.sim.Gauntlet;

/**
 * Prints the simulation gauntlet report. Kept out of the sim package because sim is not allowed to
 * perform output; this is the adapter that writes the evidence file.
 *
 * <pre>
 *   java -cp ... com.agentpaygate.tools.SimGauntletMain [seeds] [steps]
 * </pre>
 */
public final class SimGauntletMain {

    private SimGauntletMain() {
    }

    public static void main(String[] args) {
        int seeds = args.length > 0 ? Integer.parseInt(args[0]) : 40;
        int steps = args.length > 1 ? Integer.parseInt(args[1]) : 80;
        System.out.println(Gauntlet.report(seeds, steps));
    }
}
