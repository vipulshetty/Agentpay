package com.agentpaygate.pspsim;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * Failure modes the drills and integration tests switch on at runtime. All are off by default, so
 * an idle provider behaves like a boring, working provider.
 */
@Component
public class FailureModes {

    private final AtomicBoolean serverErrors = new AtomicBoolean(false);
    private final AtomicBoolean timeoutAfterCharge = new AtomicBoolean(false);
    private final AtomicBoolean duplicateWebhooks = new AtomicBoolean(false);
    private final AtomicBoolean dropWebhooks = new AtomicBoolean(false);
    private final AtomicInteger latencyMs = new AtomicInteger(0);
    private final AtomicInteger webhookDelayMs = new AtomicInteger(0);

    public boolean serverErrors() {
        return serverErrors.get();
    }

    public boolean timeoutAfterCharge() {
        return timeoutAfterCharge.get();
    }

    public boolean duplicateWebhooks() {
        return duplicateWebhooks.get();
    }

    public boolean dropWebhooks() {
        return dropWebhooks.get();
    }

    public int latencyMs() {
        return latencyMs.get();
    }

    public int webhookDelayMs() {
        return webhookDelayMs.get();
    }

    public void update(boolean serverErrors, boolean timeoutAfterCharge, boolean duplicateWebhooks,
                       boolean dropWebhooks, int latencyMs, int webhookDelayMs) {
        this.serverErrors.set(serverErrors);
        this.timeoutAfterCharge.set(timeoutAfterCharge);
        this.duplicateWebhooks.set(duplicateWebhooks);
        this.dropWebhooks.set(dropWebhooks);
        this.latencyMs.set(Math.max(0, latencyMs));
        this.webhookDelayMs.set(Math.max(0, webhookDelayMs));
    }

    public void reset() {
        update(false, false, false, false, 0, 0);
    }

    public java.util.Map<String, Object> snapshot() {
        java.util.Map<String, Object> out = new java.util.TreeMap<>();
        out.put("serverErrors", serverErrors());
        out.put("timeoutAfterCharge", timeoutAfterCharge());
        out.put("duplicateWebhooks", duplicateWebhooks());
        out.put("dropWebhooks", dropWebhooks());
        out.put("latencyMs", latencyMs());
        out.put("webhookDelayMs", webhookDelayMs());
        return out;
    }
}
