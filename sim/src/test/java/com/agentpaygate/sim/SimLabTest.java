package com.agentpaygate.sim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * The lab's own contract: clean runs never violate an invariant, runs are reproducible by seed,
 * the invariant set catches every planted defect, and a failure can be shrunk.
 */
class SimLabTest {

    @Test
    void cleanRunsNeverViolateAnInvariant() {
        for (int seed = 1; seed <= 40; seed++) {
            Simulation.SimResult result = Simulation.run(SimConfig.clean(seed, 80));
            assertThat(result.violations()).as("seed %d", seed).isEmpty();
            assertThat(result.paymentsCreated()).as("seed %d created payments", seed).isGreaterThan(0);
        }
    }

    @Test
    void sameSeedProducesTheSameTraceAndADifferentSeedDoesNot() {
        Simulation.SimResult first = Simulation.run(SimConfig.clean(7, 60));
        Simulation.SimResult second = Simulation.run(SimConfig.clean(7, 60));
        assertThat(second.traceDigest()).isEqualTo(first.traceDigest());
        assertThat(second.traceLines()).isEqualTo(first.traceLines());

        Simulation.SimResult other = Simulation.run(SimConfig.clean(8, 60));
        assertThat(other.traceDigest()).isNotEqualTo(first.traceDigest());
    }

    @Test
    void everyPlantedBugIsCaughtByAnInvariant() {
        Gauntlet.Report report = Gauntlet.run(40, 80);
        assertThat(report.cleanViolations()).as("clean runs must stay clean").isEmpty();
        assertThat(report.cleanRuns()).isEqualTo(40);
        assertThat(report.missed()).as("planted bugs no invariant caught").isEmpty();
        assertThat(report.allCaught()).isTrue();
        for (PlantedBug bug : PlantedBug.values()) {
            if (bug != PlantedBug.NONE) {
                assertThat(report.detections().get(bug).firstInvariant()).isNotBlank();
            }
        }
    }

    @Test
    void aDoubleCaptureIsCaughtByTheLedgerReconciliation() {
        Gauntlet.Detection detection = Gauntlet.run(20, 80).detections().get(PlantedBug.DOUBLE_CAPTURE_ON_REPLAY);
        assertThat(detection).isNotNull();
        assertThat(detection.violations())
                .anySatisfy(violation -> assertThat(violation).startsWith(Invariant.LEDGER_MATCHES_PAYMENTS.name()));
    }

    @Test
    void shrinkerFindsASmallerScenarioThatStillFails() {
        Gauntlet.Detection detection = Gauntlet.run(20, 80).detections().get(PlantedBug.DOUBLE_CAPTURE_ON_REPLAY);
        assertThat(detection).isNotNull();

        Shrinker.Shrunk shrunk = Shrinker.shrink(PlantedBug.DOUBLE_CAPTURE_ON_REPLAY, detection.seed(), 80);
        assertThat(shrunk.violations()).isNotEmpty();
        assertThat(shrunk.steps()).isLessThanOrEqualTo(80);
        assertThat(shrunk.minimal()).as("one step shorter must not fail").isTrue();
        assertThat(Simulation.run(new SimConfig(shrunk.seed(), shrunk.steps(),
                PlantedBug.DOUBLE_CAPTURE_ON_REPLAY)).clean()).isFalse();
    }

    @Test
    void shrinkingACleanRunIsRejected() {
        assertThatThrownBy(() -> Shrinker.shrink(PlantedBug.NONE, 7, 40))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not fail");
    }

    @Test
    void traceExportsAsJsonLines() {
        Simulation.SimResult result = Simulation.run(SimConfig.clean(3, 10));
        assertThat(result.traceLines()).isNotEmpty().allSatisfy(line ->
                assertThat(line).startsWith("{\"at\":").contains("\"seq\":").contains("\"kind\":\""));
        assertThat(result.traceDigest()).matches("[0-9a-f]{64}");
    }
}
