package com.agentpaygate.sim;

/**
 * Virtual clock for the simulation. Time only moves when the event loop moves it, so a run is
 * reproducible: nothing reads the machine clock. Time is kept in epoch seconds so the pure
 * cores never need time value types at all.
 */
public final class VirtualClock {

    private long epochSecond;

    public VirtualClock(long startEpochSecond) {
        this.epochSecond = startEpochSecond;
    }

    public long now() {
        return epochSecond;
    }

    public void advanceTo(long targetEpochSecond) {
        if (targetEpochSecond < epochSecond) {
            throw new IllegalArgumentException(
                    "virtual time cannot move backwards: " + targetEpochSecond + " < " + epochSecond);
        }
        epochSecond = targetEpochSecond;
    }

    public void advanceBy(long seconds) {
        if (seconds < 0L) {
            throw new IllegalArgumentException("cannot advance virtual time by a negative amount");
        }
        epochSecond = Math.addExact(epochSecond, seconds);
    }
}
