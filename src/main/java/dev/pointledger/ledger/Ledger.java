package dev.pointledger.ledger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Append-only, double-entry points ledger.
 *
 * <p>Every change is a balanced transfer between two accounts, so the sum of all balances is
 * always zero (points are never created or destroyed, only moved). Entries are hash-chained
 * (SHA-256 over the previous hash and the entry's fields), so any edit to history is detectable.
 *
 * <p>Single-writer design: all writes go through one lock, which gives a total order of entries
 * (needed for the hash chain) and lets callers group several transfers into one atomic step via
 * {@link #atomically}. Accounts whose names start with {@code seller:} or {@code system:} may go
 * negative (they are funding/sink accounts); every other account has a no-overdraft guarantee.
 */
public final class Ledger {

    /** One immutable ledger line. Points move from {@code from} to {@code to}. */
    public record Entry(long seq, long timestamp, String from, String to, long amount,
                        String kind, String ref, String prevHash, String hash) {}

    public static final String GENESIS = "0".repeat(64);

    private final ReentrantLock lock = new ReentrantLock();
    private final List<Entry> entries = new ArrayList<>();
    private final Map<String, Long> balances = new HashMap<>();
    /** Per-account balance history: parallel arrays of (seq, balance after seq). */
    private final Map<String, long[][]> history = new HashMap<>();
    private final Map<String, Integer> historySize = new HashMap<>();
    private String headHash = GENESIS;

    public Ledger() {}

    /** Runs {@code body} while holding the write lock, so a group of transfers is atomic. */
    public <T> T atomically(Supplier<T> body) {
        lock.lock();
        try { return body.get(); } finally { lock.unlock(); }
    }

    public Entry transfer(String from, String to, long amount, String kind, String ref, long timestamp) {
        if (amount <= 0) throw new IllegalArgumentException("amount must be positive: " + amount);
        if (from.equals(to)) throw new IllegalArgumentException("from == to: " + from);
        checkName(from); checkName(to); checkName(kind); checkName(ref);
        lock.lock();
        try {
            long fromBal = balances.getOrDefault(from, 0L);
            if (!mayOverdraw(from) && fromBal < amount) {
                throw new InsufficientFundsException(from, fromBal, amount);
            }
            long seq = entries.size() + 1L;
            String hash = hash(headHash, seq, timestamp, from, to, amount, kind, ref);
            Entry e = new Entry(seq, timestamp, from, to, amount, kind, ref, headHash, hash);
            apply(e);
            return e;
        } finally {
            lock.unlock();
        }
    }

    public long balance(String account) {
        lock.lock();
        try { return balances.getOrDefault(account, 0L); } finally { lock.unlock(); }
    }

    /** Balance of {@code account} right after entry {@code seq} (time-travel query, O(log n)). */
    public long balanceAsOf(String account, long seq) {
        lock.lock();
        try {
            long[][] h = history.get(account);
            if (h == null) return 0;
            int n = historySize.get(account);
            int lo = 0, hi = n - 1, ans = -1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (h[0][mid] <= seq) { ans = mid; lo = mid + 1; } else hi = mid - 1;
            }
            return ans < 0 ? 0 : h[1][ans];
        } finally { lock.unlock(); }
    }

    public long size() {
        lock.lock();
        try { return entries.size(); } finally { lock.unlock(); }
    }

    public String headHash() {
        lock.lock();
        try { return headHash; } finally { lock.unlock(); }
    }

    public List<Entry> tail(int limit) {
        lock.lock();
        try {
            int from = Math.max(0, entries.size() - limit);
            List<Entry> out = new ArrayList<>(entries.subList(from, entries.size()));
            Collections.reverse(out);
            return out;
        } finally { lock.unlock(); }
    }

    public Map<String, Long> balancesSnapshot() {
        lock.lock();
        try { return new TreeMap<>(balances); } finally { lock.unlock(); }
    }

    /** Full audit: re-derives the hash chain and every balance from scratch. */
    public VerifyReport verify() {
        lock.lock();
        try {
            String prev = GENESIS;
            Map<String, Long> recomputed = new HashMap<>();
            for (Entry e : entries) {
                String h = hash(prev, e.seq(), e.timestamp(), e.from(), e.to(), e.amount(), e.kind(), e.ref());
                if (!e.prevHash().equals(prev) || !e.hash().equals(h)) {
                    return new VerifyReport(false, e.seq(), sum(balances), entries.size(), headHash,
                            "hash chain broken at entry " + e.seq());
                }
                recomputed.merge(e.from(), -e.amount(), Long::sum);
                recomputed.merge(e.to(), e.amount(), Long::sum);
                prev = h;
            }
            for (var acct : recomputed.entrySet()) {
                if (!acct.getValue().equals(balances.getOrDefault(acct.getKey(), 0L))) {
                    return new VerifyReport(false, -1, sum(balances), entries.size(), headHash,
                            "cached balance differs for " + acct.getKey());
                }
            }
            long total = sum(balances);
            return new VerifyReport(total == 0, -1, total, entries.size(), headHash,
                    total == 0 ? "ok" : "conservation violated: sum=" + total);
        } finally { lock.unlock(); }
    }

    public record VerifyReport(boolean ok, long brokenAtSeq, long sumOfBalances, long entries,
                               String headHash, String message) {}

    /** Test hook: simulates someone editing history in storage. */
    public void tamperForTest(int index, long newAmount) {
        Entry e = entries.get(index);
        entries.set(index, new Entry(e.seq(), e.timestamp(), e.from(), e.to(), newAmount,
                e.kind(), e.ref(), e.prevHash(), e.hash()));
    }

    // ---------------------------------------------------------------------------------------

    private void apply(Entry e) {
        entries.add(e);
        balances.merge(e.from(), -e.amount(), Long::sum);
        balances.merge(e.to(), e.amount(), Long::sum);
        record(e.from(), e.seq());
        record(e.to(), e.seq());
        headHash = e.hash();
    }

    private void record(String account, long seq) {
        long[][] h = history.computeIfAbsent(account, k -> new long[2][8]);
        int n = historySize.getOrDefault(account, 0);
        if (n == h[0].length) {
            h[0] = Arrays.copyOf(h[0], n * 2);
            h[1] = Arrays.copyOf(h[1], n * 2);
        }
        h[0][n] = seq;
        h[1][n] = balances.get(account);
        historySize.put(account, n + 1);
    }

    private static final java.util.regex.Pattern NAME = java.util.regex.Pattern.compile("[A-Za-z0-9:_.\\-]{1,128}");

    /** Names go into the journal and the hash, so only a safe character set is allowed. */
    public static void checkName(String s) {
        if (s == null || !NAME.matcher(s).matches()) throw new IllegalArgumentException("invalid name: " + s);
    }

    static boolean mayOverdraw(String account) {
        return account.startsWith("seller:") || account.startsWith("system:");
    }

    private static long sum(Map<String, Long> m) {
        long s = 0;
        for (long v : m.values()) s += v;
        return s;
    }

    private static final ThreadLocal<MessageDigest> SHA = ThreadLocal.withInitial(() -> {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    });

    static String hash(String prev, long seq, long ts, String from, String to, long amount, String kind, String ref) {
        String s = prev + '|' + seq + '|' + ts + '|' + from + '|' + to + '|' + amount + '|' + kind + '|' + ref;
        byte[] d = SHA.get().digest(s.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(d);
    }
}
