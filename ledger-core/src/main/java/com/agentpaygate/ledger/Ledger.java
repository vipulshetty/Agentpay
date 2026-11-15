package com.agentpaygate.ledger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * In-memory double-entry ledger, pure and deterministic.
 *
 * <p>Rules enforced here:
 * <ul>
 *   <li>every posted entry balances to zero (checked in {@link JournalEntry});</li>
 *   <li>the sum of all balances including EXTERNAL is exactly zero;</li>
 *   <li>no non-EXTERNAL account may go negative;</li>
 *   <li>a business event posts at most once (replay is a no-op);</li>
 *   <li>balance arithmetic uses {@link Math#addExact(long, long)}, so overflow throws.</li>
 * </ul>
 *
 * <p>All iteration is over sorted collections, so {@link #stateHash()} is identical across
 * JVMs for the same history. Persistence lives in the gateway, not here.
 */
public final class Ledger {

    private final String currency;
    private final TreeMap<Account, Long> balances = new TreeMap<>();
    private final TreeSet<String> postedEvents = new TreeSet<>();
    private final List<JournalEntry> journal = new ArrayList<>();

    public Ledger(String currency) {
        this.currency = Objects.requireNonNull(currency, "currency");
    }

    public String currency() {
        return currency;
    }

    /**
     * Applies an entry. Validation happens on a copy first, so a rejected entry never leaves
     * a partial posting behind.
     */
    public PostStatus post(JournalEntry entry) {
        Objects.requireNonNull(entry, "entry");
        if (!currency.equals(entry.currency())) {
            throw new IllegalArgumentException(
                    "entry currency " + entry.currency() + " does not match ledger currency " + currency);
        }
        if (postedEvents.contains(entry.businessEventId())) {
            return PostStatus.ALREADY_POSTED;
        }
        TreeMap<Account, Long> candidate = new TreeMap<>(balances);
        for (Line line : entry.lines()) {
            long current = candidate.getOrDefault(line.account(), 0L);
            candidate.put(line.account(), Math.addExact(current, line.amount()));
        }
        for (Map.Entry<Account, Long> e : candidate.entrySet()) {
            if (!e.getKey().isExternal() && e.getValue() < 0L) {
                throw new IllegalStateException(
                        "posting " + entry.businessEventId() + " would make non-external account "
                                + e.getKey() + " negative (" + e.getValue() + ")");
            }
        }
        balances.clear();
        balances.putAll(candidate);
        postedEvents.add(entry.businessEventId());
        journal.add(entry);
        return PostStatus.APPLIED;
    }

    public long balance(Account account) {
        return balances.getOrDefault(account, 0L);
    }

    public SortedMap<Account, Long> balances() {
        return Collections.unmodifiableSortedMap(new TreeMap<>(balances));
    }

    public List<JournalEntry> journal() {
        return List.copyOf(journal);
    }

    public boolean hasPosted(String businessEventId) {
        return postedEvents.contains(businessEventId);
    }

    /** Sum of every balance including EXTERNAL must be exactly zero. */
    public boolean conserves() {
        long sum = 0L;
        for (long value : balances.values()) {
            sum = Math.addExact(sum, value);
        }
        return sum == 0L;
    }

    /** No account other than EXTERNAL may hold a negative balance. */
    public boolean noNegativeNonExternalBalances() {
        for (Map.Entry<Account, Long> e : balances.entrySet()) {
            if (!e.getKey().isExternal() && e.getValue() < 0L) {
                return false;
            }
        }
        return true;
    }

    /**
     * SHA-256 over the ordered journal, used by tests and the simulator to prove two runs with
     * the same seed end in the same state.
     */
    public String stateHash() {
        MessageDigest digest = sha256();
        for (JournalEntry entry : journal) {
            digest.update(entry.businessEventId().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '|');
            for (Line line : entry.lines()) {
                digest.update(line.account().type().name().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) ':');
                digest.update(line.account().ref().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '=');
                digest.update(Long.toString(line.amount()).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) ';');
            }
            digest.update((byte) '\n');
        }
        for (Map.Entry<Account, Long> e : balances.entrySet()) {
            digest.update(e.getKey().ref().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '=');
            digest.update(Long.toString(e.getValue()).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) ';');
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JLS", e);
        }
    }
}
