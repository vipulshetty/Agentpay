package com.agentpaygate.policy;

/**
 * Budget state for one agent under one mandate, in minor units.
 *
 * <p>{@code spent} and {@code reserved} are lifetime figures; the {@code *Today} figures are
 * scoped to {@code dayKey} (UTC day index). Approval reserves funds, capture converts reserved
 * into spent, void/failure releases the reservation. The invariant that must hold at all times is
 * {@code spent + reserved <= maxTotal}, which the engine checks before approving.
 */
public record SpendState(
        long spentTotal,
        long reservedTotal,
        long spentToday,
        long reservedToday,
        long dayKey) {

    public SpendState {
        if (spentTotal < 0L || reservedTotal < 0L || spentToday < 0L || reservedToday < 0L) {
            throw new IllegalArgumentException("spend figures must not be negative");
        }
    }

    /** Seconds per UTC day, used to derive the day index without any time API. */
    public static final long SECONDS_PER_DAY = 86_400L;

    public static long dayKeyOf(long epochSecond) {
        return Math.floorDiv(epochSecond, SECONDS_PER_DAY);
    }

    public static SpendState empty(long dayKey) {
        return new SpendState(0L, 0L, 0L, 0L, dayKey);
    }

    /** Rolls the daily counters when the day index changes; lifetime figures are untouched. */
    public SpendState forDay(long newDayKey) {
        if (newDayKey == dayKey) {
            return this;
        }
        return new SpendState(spentTotal, reservedTotal, 0L, 0L, newDayKey);
    }

    public SpendState reserve(long amount) {
        return new SpendState(
                spentTotal,
                Math.addExact(reservedTotal, amount),
                spentToday,
                Math.addExact(reservedToday, amount),
                dayKey);
    }

    public SpendState release(long amount) {
        return new SpendState(
                spentTotal,
                Math.subtractExact(reservedTotal, amount),
                spentToday,
                Math.subtractExact(reservedToday, amount),
                dayKey);
    }

    /** Converts a reservation into a captured spend. */
    public SpendState capture(long amount) {
        return new SpendState(
                Math.addExact(spentTotal, amount),
                Math.subtractExact(reservedTotal, amount),
                Math.addExact(spentToday, amount),
                Math.subtractExact(reservedToday, amount),
                dayKey);
    }

    public SpendState refund(long amount) {
        return new SpendState(
                Math.subtractExact(spentTotal, amount),
                reservedTotal,
                Math.subtractExact(spentToday, amount),
                reservedToday,
                dayKey);
    }

    public long committedTotal() {
        return Math.addExact(spentTotal, reservedTotal);
    }

    public long committedToday() {
        return Math.addExact(spentToday, reservedToday);
    }
}
