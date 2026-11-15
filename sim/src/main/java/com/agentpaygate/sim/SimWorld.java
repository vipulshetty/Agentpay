package com.agentpaygate.sim;

import com.agentpaygate.ledger.Account;
import com.agentpaygate.ledger.JournalEntry;
import com.agentpaygate.ledger.Ledger;
import com.agentpaygate.payments.Discrepancy;
import com.agentpaygate.payments.IdempotencyCheck;
import com.agentpaygate.payments.IdempotencyKey;
import com.agentpaygate.payments.IdempotencyRegistry;
import com.agentpaygate.payments.Payment;
import com.agentpaygate.payments.PaymentEvent;
import com.agentpaygate.payments.PaymentEventType;
import com.agentpaygate.payments.PaymentState;
import com.agentpaygate.payments.ProviderEvent;
import com.agentpaygate.payments.ProviderEventKind;
import com.agentpaygate.payments.ProviderEventReducer;
import com.agentpaygate.payments.ProviderStatementLine;
import com.agentpaygate.payments.Reconciler;
import com.agentpaygate.payments.ReduceResult;
import com.agentpaygate.payments.StatementStatus;
import com.agentpaygate.policy.Decision;
import com.agentpaygate.policy.DenyReason;
import com.agentpaygate.policy.IssuerKeys;
import com.agentpaygate.policy.Mandate;
import com.agentpaygate.policy.MandateSignature;
import com.agentpaygate.policy.PaymentRequest;
import com.agentpaygate.policy.PolicyContext;
import com.agentpaygate.policy.PolicyEngine;
import com.agentpaygate.policy.SignedMandate;
import com.agentpaygate.policy.SpendState;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * One simulated world: a funded agent, a signed mandate, the real policy engine, the real ledger
 * and the real payment state machine, driven by a virtual clock and a seeded generator.
 *
 * <p>What the world does not have is a database, a thread or a wall clock. Every decision comes
 * from the pure cores, so a run is a pure function of its seed and length — and that is what makes
 * a failure shrinkable and a fix checkable.
 *
 * <p>The world deliberately mirrors the gateway's order of operations: policy first, then the
 * reservation, then the provider call, then the ledger. A defect in that order shows up here
 * before it shows up in production, which is the entire point of the lab.
 */
public final class SimWorld {

    public static final String AGENT = "agent-sim";
    public static final String CURRENCY = "INR";
    public static final String[] MERCHANTS = {"merchant-grocer", "merchant-books", "merchant-cafe"};
    public static final long FUNDING_MINOR = 1_000_000L;
    public static final long MAX_PER_TX = 50_000L;
    public static final long MAX_TOTAL = 400_000L;
    public static final long MAX_PER_DAY = 300_000L;
    public static final long START_EPOCH_SECOND = 1_700_000_000L;
    private static final String ISSUER_KEY_ID = "sim-issuer";

    private static final KeyPair ISSUER = issuerKeyPair();

    private final SimConfig config;
    private final VirtualClock clock = new VirtualClock(START_EPOCH_SECOND);
    private final Trace trace = new Trace(clock);
    private final SimRandom random;
    private final Ledger ledger = new Ledger(CURRENCY);
    private final Mandate mandate;
    private final SignedMandate signedMandate;
    private final IssuerKeys issuerKeys;
    private final IdempotencyRegistry idempotency = new IdempotencyRegistry();
    private final ProviderEventReducer reducer = new ProviderEventReducer();
    private final ProviderSim provider = new ProviderSim();
    private final TreeMap<String, Payment> payments = new TreeMap<>();
    private final TreeSet<String> usedNonces = new TreeSet<>();
    private final List<String> refusals = new ArrayList<>();
    private SpendState spend;
    private String lastWebhookEventId = "";
    private String lastKey;
    private String lastMerchant;
    private long lastAmount;
    private int step;

    public SimWorld(SimConfig config) {
        this.config = config;
        this.random = new SimRandom(config.seed());

        this.mandate = Mandate.of("mandate-sim", AGENT, ISSUER_KEY_ID, CURRENCY,
                MAX_PER_TX, MAX_TOTAL, MAX_PER_DAY,
                List.of(MERCHANTS), List.of(), START_EPOCH_SECOND - 3_600L,
                START_EPOCH_SECOND + 10L * SpendState.SECONDS_PER_DAY, "mandate-sim-nonce");
        this.signedMandate = new SignedMandate(mandate, MandateSignature.sign(mandate, ISSUER.getPrivate()));
        this.issuerKeys = IssuerKeys.of(Map.of(ISSUER_KEY_ID, ISSUER.getPublic()));
        this.spend = SpendState.empty(SpendState.dayKeyOf(clock.now()));

        ledger.post(JournalEntry.funding("funding:" + AGENT, CURRENCY, AGENT, FUNDING_MINOR));
        trace.line("world.start", "seed=" + config.seed() + " funding=" + FUNDING_MINOR);
    }

    // ------------------------------------------------------------------ stepping

    /** Advances virtual time and performs exactly one action. */
    public void step() {
        step++;
        clock.advanceBy(1L + random.nextInt(30));
        int action = random.nextInt(100);
        try {
            if (action < 62) {
                requestPayment();
            } else if (action < 80) {
                deliverWebhook();
            } else if (action < 90) {
                refund();
            } else {
                reconcile();
            }
        } catch (RuntimeException e) {
            // A planted defect can land the model in a state the pure cores reject (a spend figure
            // going negative, an illegal transition). That refusal is evidence, not a crash: record
            // it and let the invariants report it.
            refusals.add("step " + step + " refused: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void requestPayment() {
        // Three deliberate shapes, because each exercises a different idempotency outcome:
        //   retry    - same key, same payload: must replay the original payment
        //   conflict - same key, different payload: must be rejected
        //   fresh    - a new key: must create a payment
        boolean retry = lastKey != null && random.chance(22);
        boolean reuseKeyWithNewPayload = lastKey != null && !retry && random.chance(8);
        String key;
        String merchant = MERCHANTS[random.nextInt(MERCHANTS.length)];
        long amount = 1_000L + random.nextInt(60_000);
        if (retry) {
            key = lastKey;
            merchant = lastMerchant;
            amount = lastAmount;
        } else if (reuseKeyWithNewPayload) {
            key = lastKey;
        } else {
            key = "idem-" + step;
            lastKey = key;
            lastMerchant = merchant;
            lastAmount = amount;
        }
        String payloadHash = IdempotencyRegistry.hashOf(AGENT, merchant, amount, CURRENCY);
        IdempotencyKey idempotencyKey = new IdempotencyKey(AGENT, key);
        IdempotencyCheck check = idempotency.check(idempotencyKey, payloadHash);

        if (check.outcome() == IdempotencyCheck.Outcome.CONFLICT) {
            trace.line("request.conflict", key);
            return;
        }
        if (check.outcome() == IdempotencyCheck.Outcome.REPLAY
                && config.bug() != PlantedBug.IDEMPOTENCY_IGNORED) {
            trace.line("request.replay", key + " -> " + check.paymentId());
            return;
        }

        String nonce = "nonce-" + step;
        PaymentRequest request = new PaymentRequest("req-" + step, key, AGENT, merchant,
                "groceries", amount, CURRENCY, nonce);
        spend = spend.forDay(SpendState.dayKeyOf(clock.now()));
        Decision decision = PolicyEngine.evaluate(signedMandate, request, spend,
                PolicyContext.at(clock.now(), issuerKeys).withNonceAlreadyUsed(usedNonces.contains(nonce)));

        boolean overridden = config.bug() == PlantedBug.IGNORE_TOTAL_LIMIT
                && (decision.reason() == DenyReason.BUDGET_EXHAUSTED
                        || decision.reason() == DenyReason.DAILY_LIMIT);
        if (decision.denied() && !overridden) {
            trace.line("request.denied", decision.reason().name());
            if (config.bug() == PlantedBug.RESERVATION_LEAK_ON_DENY) {
                spend = spend.reserve(amount);
            }
            return;
        }

        String paymentId = "pay-" + step;
        Payment payment = Payment.requested(paymentId, AGENT, merchant, CURRENCY, amount,
                "prov-" + paymentId, clock.now());
        payment = payment.apply(PaymentEvent.of(paymentId + ":auth-started", PaymentEventType.AUTH_STARTED, 0L, clock.now()));
        spend = spend.reserve(amount);
        usedNonces.add(nonce);
        idempotency.remember(idempotencyKey, payloadHash, paymentId, payment.state().name());
        payments.put(paymentId, payment);
        trace.line("request.approved", paymentId + " " + merchant + " " + amount
                + (overridden ? " overridden-limit" : ""));
        charge(payment);
    }

    private void charge(Payment payment) {
        ProviderSim.Charge charge = provider.charge(payment.providerIdempotencyKey(),
                payment.paymentId(), payment.amountMinor(), random);
        switch (charge.outcome()) {
            case CAPTURED -> {
                Payment next = payment
                        .apply(PaymentEvent.of(payment.paymentId() + ":auth-ok", PaymentEventType.AUTH_SUCCEEDED, 0L, clock.now())
                                .withProviderRef(charge.providerRef()))
                        .apply(PaymentEvent.of(payment.paymentId() + ":capture-started", PaymentEventType.CAPTURE_STARTED, 0L, clock.now()));
                next = next.apply(new PaymentEvent(payment.paymentId() + ":capture-ok",
                        PaymentEventType.CAPTURE_SUCCEEDED, next.amountMinor(), charge.providerRef(), "", clock.now()));
                payments.put(next.paymentId(), next);
                postCapture(next, "capture:" + next.paymentId());
                if (config.bug() != PlantedBug.CAPTURE_WITHOUT_RESERVATION_RELEASE) {
                    spend = spend.capture(next.amountMinor());
                }
                trace.line("charge.captured", next.paymentId() + " " + charge.providerRef());
            }
            case DECLINED -> {
                Payment next = payment.apply(new PaymentEvent(payment.paymentId() + ":auth-failed",
                        PaymentEventType.AUTH_FAILED, 0L, charge.providerRef(), "DECLINED", clock.now()));
                payments.put(next.paymentId(), next);
                spend = spend.release(next.amountMinor());
                trace.line("charge.declined", next.paymentId());
            }
            case TIMEOUT -> {
                Payment next = payment
                        .apply(PaymentEvent.of(payment.paymentId() + ":auth-ok", PaymentEventType.AUTH_SUCCEEDED, 0L, clock.now())
                                .withProviderRef(charge.providerRef()))
                        .apply(PaymentEvent.of(payment.paymentId() + ":capture-started", PaymentEventType.CAPTURE_STARTED, 0L, clock.now()));
                next = next.apply(new PaymentEvent(payment.paymentId() + ":capture-unknown",
                        PaymentEventType.CAPTURE_UNKNOWN, 0L, charge.providerRef(), "TIMEOUT", clock.now()));
                payments.put(next.paymentId(), next);
                trace.line("charge.timeout", next.paymentId() + " (provider may have charged)");
            }
        }
    }

    private void deliverWebhook() {
        List<Payment> delivered = payments.values().stream()
                .filter(payment -> payment.state() == PaymentState.CAPTURED || payment.state() == PaymentState.REFUNDED)
                .toList();
        if (delivered.isEmpty()) {
            trace.line("webhook.skipped", "no captured payment yet");
            return;
        }
        Payment payment = delivered.get(random.nextInt(delivered.size()));
        ProviderEventKind kind = payment.state() == PaymentState.REFUNDED
                ? ProviderEventKind.REFUNDED
                : ProviderEventKind.CAPTURED;
        boolean duplicate = random.chance(30) && !lastWebhookEventId.isBlank();
        String eventId = duplicate ? lastWebhookEventId : "evt-" + step;
        lastWebhookEventId = eventId;

        ProviderEvent event = new ProviderEvent(eventId, payment.providerRef(), kind,
                payment.amountMinor(), clock.now());
        ReduceResult result = reducer.reduce(payment, event);
        payments.put(payment.paymentId(), result.payment());
        trace.line(duplicate ? "webhook.duplicate" : "webhook.delivered",
                kind + " " + payment.paymentId() + " -> " + result.outcome());

        if (kind == ProviderEventKind.CAPTURED && config.bug() == PlantedBug.DOUBLE_CAPTURE_ON_REPLAY) {
            postCapture(payment, "capture-replay:" + step);
        }
    }

    private void refund() {
        List<Payment> refundable = payments.values().stream()
                .filter(payment -> payment.state() == PaymentState.CAPTURED && payment.refundableRemaining() > 0L)
                .toList();
        if (refundable.isEmpty()) {
            trace.line("refund.skipped", "nothing refundable");
            return;
        }
        Payment payment = refundable.get(random.nextInt(refundable.size()));
        long amount = payment.refundableRemaining();
        Payment refunded = payment
                .apply(PaymentEvent.of(payment.paymentId() + ":refund-" + step + ":started",
                        PaymentEventType.REFUND_STARTED, 0L, clock.now()))
                .apply(new PaymentEvent(payment.paymentId() + ":refund-" + step + ":ok",
                        PaymentEventType.REFUND_SUCCEEDED, amount, payment.providerRef(), "", clock.now()));

        if (config.bug() == PlantedBug.REFUND_LEDGER_WITHOUT_STATE) {
            postRefund(payment, "refund-bug:" + step, amount);
            trace.line("refund.ledger-only", payment.paymentId() + " " + amount);
            return;
        }

        payments.put(refunded.paymentId(), refunded);
        spend = spend.refund(amount);
        postRefund(refunded, "refund:" + refunded.paymentId() + ":" + refunded.refundedMinor(), amount);
        provider.refund(payment.providerRef());
        trace.line("refund.applied", refunded.paymentId() + " " + amount);
    }

    private void reconcile() {
        List<Payment> unknown = payments.values().stream()
                .filter(payment -> payment.state() == PaymentState.CAPTURE_UNKNOWN)
                .toList();
        if (unknown.isEmpty()) {
            trace.line("reconcile", "nothing unknown");
            return;
        }
        List<ProviderStatementLine> statement = provider.statement();
        for (Payment payment : unknown) {
            ProviderStatementLine line = statement.stream()
                    .filter(candidate -> candidate.providerRef().equals(payment.providerRef()))
                    .findFirst()
                    .orElse(null);
            if (line == null) {
                continue;
            }
            List<Discrepancy> found = Reconciler.reconcile(List.of(line), List.of(payment));
            boolean resolvable = found.stream().anyMatch(Discrepancy::autoResolvable);
            if (!resolvable) {
                trace.line("reconcile.flagged", payment.paymentId() + " " + found.size());
                continue;
            }
            Payment resolved = payment.apply(new PaymentEvent(payment.paymentId() + ":reconciled",
                    PaymentEventType.CAPTURE_SUCCEEDED, payment.amountMinor(), payment.providerRef(),
                    "RECONCILED", clock.now()));
            payments.put(resolved.paymentId(), resolved);
            postCapture(resolved, "capture:" + resolved.paymentId());
            spend = spend.capture(resolved.amountMinor());
            trace.line("reconcile.resolved", resolved.paymentId());
        }
    }

    // ------------------------------------------------------------------ posting

    private void postCapture(Payment payment, String businessEventId) {
        try {
            ledger.post(JournalEntry.capture(businessEventId, CURRENCY, payment.agentId(),
                    payment.merchantId(), payment.capturedMinor() == 0L ? payment.amountMinor() : payment.capturedMinor()));
        } catch (RuntimeException e) {
            refusals.add(businessEventId + ": " + e.getMessage());
        }
    }

    private void postRefund(Payment payment, String businessEventId, long amount) {
        try {
            ledger.post(JournalEntry.refund(businessEventId, CURRENCY, payment.agentId(),
                    payment.merchantId(), amount));
        } catch (RuntimeException e) {
            refusals.add(businessEventId + ": " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ inspection

    public List<String> violations() {
        List<String> out = new ArrayList<>();
        for (Invariant invariant : Invariant.values()) {
            invariant.check(this).ifPresent(violation -> out.add(invariant.name() + ": " + violation));
        }
        if (!payments.keySet().equals(new TreeSet<>(payments.keySet()))) {
            out.add("payment index is inconsistent");
        }
        return List.copyOf(out);
    }

    public VirtualClock clock() {
        return clock;
    }

    public Trace trace() {
        return trace;
    }

    public Ledger ledger() {
        return ledger;
    }

    public Mandate mandate() {
        return mandate;
    }

    public SpendState spend() {
        return spend;
    }

    public Map<String, Payment> payments() {
        return java.util.Collections.unmodifiableMap(new TreeMap<>(payments));
    }

    public IdempotencyRegistry idempotency() {
        return idempotency;
    }

    public List<String> refusals() {
        return List.copyOf(refusals);
    }

    public ProviderSim provider() {
        return provider;
    }

    public int stepCount() {
        return step;
    }

    private static KeyPair issuerKeyPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Ed25519 is required", e);
        }
    }

    /**
     * The provider: deduplicates on the idempotency key, and decides an outcome from the seeded
     * generator so a run is reproducible. "Timeout" still charges — that is the whole problem the
     * unknown states exist for.
     */
    public static final class ProviderSim {

        public enum Outcome {
            CAPTURED,
            DECLINED,
            TIMEOUT
        }

        public record Charge(String providerRef, String paymentId, long amountMinor, Outcome outcome,
                             boolean refunded) {
        }

        private final TreeMap<String, Charge> byIdempotencyKey = new TreeMap<>();
        private final TreeMap<String, Charge> byProviderRef = new TreeMap<>();
        private int counter;

        Charge charge(String idempotencyKey, String paymentId, long amountMinor, SimRandom random) {
            Charge existing = byIdempotencyKey.get(idempotencyKey);
            if (existing != null) {
                return existing;
            }
            int roll = random.nextInt(100);
            Outcome outcome = roll < 78 ? Outcome.CAPTURED : roll < 90 ? Outcome.TIMEOUT : Outcome.DECLINED;
            counter++;
            String ref = "psp_" + String.format("%05d", counter);
            Charge charge = new Charge(ref, paymentId, amountMinor, outcome, false);
            byIdempotencyKey.put(idempotencyKey, charge);
            byProviderRef.put(ref, charge);
            return charge;
        }

        void refund(String providerRef) {
            Charge charge = byProviderRef.get(providerRef);
            if (charge != null) {
                byProviderRef.put(providerRef, new Charge(charge.providerRef(), charge.paymentId(),
                        charge.amountMinor(), charge.outcome(), true));
            }
        }

        public List<Charge> charges() {
            return List.copyOf(byProviderRef.values());
        }

        /** Only charges the provider actually applied appear here. */
        public List<ProviderStatementLine> statement() {
            List<ProviderStatementLine> lines = new ArrayList<>();
            for (Charge charge : byProviderRef.values()) {
                StatementStatus status = charge.outcome() == Outcome.DECLINED
                        ? StatementStatus.FAILED
                        : charge.refunded() ? StatementStatus.REFUNDED : StatementStatus.CAPTURED;
                lines.add(new ProviderStatementLine(charge.providerRef(), charge.amountMinor(), status));
            }
            return List.copyOf(lines);
        }
    }

    /** Account helper kept here so the invariant code does not need the ledger package. */
    public static Account wallet() {
        return Account.agentWallet(AGENT);
    }
}
