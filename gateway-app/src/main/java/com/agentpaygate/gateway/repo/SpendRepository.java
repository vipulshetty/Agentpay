package com.agentpaygate.gateway.repo;

import com.agentpaygate.policy.SpendState;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Budget state per mandate, read with {@code SELECT ... FOR UPDATE}.
 *
 * <p>This lock is the reason two concurrent requests cannot both spend the last slice of budget.
 * The pure engine decides whether a request fits; this repository serialises the read-check-write
 * so the decision and the reservation see the same numbers.
 */
@Repository
public class SpendRepository {

    private final JdbcTemplate jdbc;

    public SpendRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Must be called inside a transaction; holds a row lock until commit. */
    public SpendState lockAndGet(String mandateId, long dayKey) {
        SpendState state = jdbc.queryForObject("""
                select spent_minor, reserved_minor, spent_today_minor, reserved_today_minor, day_key
                from mandate_spend where mandate_id = ? for update
                """, (rs, rowNum) -> new SpendState(
                        rs.getLong("spent_minor"), rs.getLong("reserved_minor"),
                        rs.getLong("spent_today_minor"), rs.getLong("reserved_today_minor"),
                        rs.getLong("day_key")), mandateId);
        if (state == null) {
            throw new IllegalStateException("no spend row for mandate " + mandateId);
        }
        if (state.dayKey() != dayKey) {
            // Day rollover: clear the daily counters before the engine sees them.
            jdbc.update("""
                    update mandate_spend set spent_today_minor = 0, reserved_today_minor = 0, day_key = ?,
                        version = version + 1
                    where mandate_id = ?
                    """, dayKey, mandateId);
            return new SpendState(state.spentTotal(), state.reservedTotal(), 0L, 0L, dayKey);
        }
        return state;
    }

    public void save(String mandateId, SpendState state) {
        jdbc.update("""
                update mandate_spend set spent_minor = ?, reserved_minor = ?, spent_today_minor = ?,
                    reserved_today_minor = ?, day_key = ?, version = version + 1
                where mandate_id = ?
                """, state.spentTotal(), state.reservedTotal(), state.spentToday(),
                state.reservedToday(), state.dayKey(), mandateId);
    }

    public SpendState current(String mandateId) {
        SpendState state = jdbc.queryForObject("""
                select spent_minor, reserved_minor, spent_today_minor, reserved_today_minor, day_key
                from mandate_spend where mandate_id = ?
                """, (rs, rowNum) -> new SpendState(
                        rs.getLong("spent_minor"), rs.getLong("reserved_minor"),
                        rs.getLong("spent_today_minor"), rs.getLong("reserved_today_minor"),
                        rs.getLong("day_key")), mandateId);
        return state == null ? SpendState.empty(0L) : state;
    }
}
