package com.agentpaygate.policy;

/** Result of a policy evaluation: approved with a reservation, or denied with a reason code. */
public record Decision(boolean approved, DenyReason reason, String detail, long reservedAmountMinor) {

    public Decision {
        if (approved && reason != null) {
            throw new IllegalArgumentException("an approved decision cannot carry a deny reason");
        }
        if (!approved && reason == null) {
            throw new IllegalArgumentException("a denied decision must carry a deny reason");
        }
        if (approved && reservedAmountMinor <= 0L) {
            throw new IllegalArgumentException("an approval must reserve a positive amount");
        }
    }

    public static Decision approve(long reservedAmountMinor) {
        return new Decision(true, null, "approved", reservedAmountMinor);
    }

    public static Decision deny(DenyReason reason, String detail) {
        return new Decision(false, reason, detail, 0L);
    }

    public boolean denied() {
        return !approved;
    }
}
