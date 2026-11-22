package dev.pointledger.engine;

import dev.pointledger.util.Json;
import dev.pointledger.engine.Model.*;
import dev.pointledger.ledger.Ledger;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Makes the {@link PointsEngine} crash-safe by event sourcing its commands.
 *
 * <p>Each write command is appended to a command log <em>before</em> it runs (write-ahead), with
 * the timestamp it runs at and the ledger head hash it expects to start from. The engine is
 * deterministic given those inputs, so on restart the log is replayed into a fresh engine and
 * must reproduce the exact same ledger hash chain. The log lines are hash-chained too, so an
 * edited, reordered or deleted line is caught even when it would not change any balance, and a
 * replay that drifts from the original ledger is caught by the recorded head hash. Either way
 * startup fails loudly instead of serving wrong balances. A torn final
 * line (the process died mid-write) is detected and discarded. Commands that failed the first
 * time (for example, not enough points) fail again on replay, so they leave no trace either way.
 */
public final class DurableEngine implements Closeable {

    private final PointsEngine engine;
    private final Ledger ledger;
    private final LongSupplier wallClock;
    private final Writer writer;
    private final FileChannel channel;
    private final boolean fsync;
    private long commandTime;
    private String logHead = Ledger.GENESIS;
    private final long replayed;
    private long logged;

    public DurableEngine(Path logFile, boolean fsync, LongSupplier wallClock, long maxRewardBp, Duration validity) {
        this.ledger = new Ledger();
        this.engine = new PointsEngine(ledger, () -> commandTime, maxRewardBp, validity);
        this.wallClock = wallClock;
        this.fsync = fsync;
        try {
            if (logFile.getParent() != null) Files.createDirectories(logFile.getParent());
            truncateTornTail(logFile);
            this.replayed = replay(logFile);
            this.logged = replayed;
            FileOutputStream out = new FileOutputStream(logFile.toFile(), true);
            this.channel = out.getChannel();
            this.writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public long replayedCommands() { return replayed; }

    public PointsEngine engine() { return engine; }

    public CampaignView createCampaign(Campaign c) {
        return run("createCampaign", c, () -> engine.createCampaign(c));
    }

    public CampaignView closeCampaign(String id) {
        return run("closeCampaign", Map.of("id", id), () -> engine.closeCampaign(id));
    }

    // An exact retry of a command that already took effect is answered from current state and is
    // not logged: logging it would change nothing on replay, but would grow the log and cost an
    // fsync per retry (found by scripts/crash_drill.py: client retries after a restart doubled the
    // log). The check and the command run under the same lock, so nothing can change in between.

    public OrderView placeOrder(OrderRequest r) {
        return ledger.atomically(() -> engine.retryOfPlaceOrder(r)
                .orElseGet(() -> run("placeOrder", r, () -> engine.placeOrder(r))));
    }

    public OrderView ship(String orderId) {
        return ledger.atomically(() -> engine.retryOfStatus(orderId, OrderStatus.SHIPPED)
                .orElseGet(() -> run("ship", Map.of("id", orderId), () -> engine.ship(orderId))));
    }

    public OrderView cancel(String orderId) {
        return ledger.atomically(() -> engine.retryOfStatus(orderId, OrderStatus.CANCELLED)
                .orElseGet(() -> run("cancel", Map.of("id", orderId), () -> engine.cancel(orderId))));
    }

    public CustomerView redeem(String customerId, long points, String redemptionId) {
        return ledger.atomically(() -> engine.retryOfRedeem(customerId, points, redemptionId)
                .orElseGet(() -> run("redeem", Map.of("customerId", customerId, "points", points, "redemptionId", redemptionId),
                        () -> engine.redeem(customerId, points, redemptionId))));
    }

    /** Number of lines in the command log (replayed at startup plus appended since). */
    public long loggedCommands() { return ledger.atomically(() -> logged); }

    public long expireAll() {
        return run("expire", Map.of(), engine::expireAll);
    }

    private <T> T run(String op, Object args, Supplier<T> body) {
        return ledger.atomically(() -> {
            commandTime = wallClock.getAsLong();
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("t", commandTime);
            line.put("op", op);
            line.put("head", ledger.headHash());
            line.put("args", args);
            line.put("prev", logHead);
            String h = sha256(logHead + Json.write(line));
            line.put("h", h);
            append(Json.write(line));
            logHead = h;
            logged++;
            return body.get();
        });
    }

    private void append(String line) {
        try {
            writer.write(line);
            writer.write('\n');
            writer.flush();
            if (fsync) channel.force(false);
        } catch (IOException e) {
            throw new UncheckedIOException("command log write failed; command not applied", e);
        }
    }

    private long replay(Path file) throws IOException {
        if (!Files.exists(file)) return 0;
        long n = 0;
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String raw;
            while ((raw = r.readLine()) != null) {
                if (raw.isBlank()) continue;
                n++;
                Map<String, Object> line = Json.obj(Json.parse(raw));
                String h = Json.str(line, "h");
                line.remove("h");
                if (!Json.str(line, "prev").equals(logHead) || !sha256(logHead + Json.write(line)).equals(h)) {
                    throw new IllegalStateException("command log diverges at line " + n + ": line hash chain broken (edited or reordered)");
                }
                logHead = h;
                String head = Json.str(line, "head");
                if (!head.equals(ledger.headHash())) {
                    throw new IllegalStateException("command log diverges at line " + n
                            + ": expected ledger head " + head + " but replay produced " + ledger.headHash());
                }
                commandTime = Json.num(line, "t");
                Map<String, Object> a = Json.obj(line.get("args"));
                try {
                    switch (Json.str(line, "op")) {
                        case "createCampaign" -> engine.createCampaign(Codec.campaign(a));
                        case "closeCampaign" -> engine.closeCampaign(Json.str(a, "id"));
                        case "placeOrder" -> engine.placeOrder(Codec.order(a));
                        case "ship" -> engine.ship(Json.str(a, "id"));
                        case "cancel" -> engine.cancel(Json.str(a, "id"));
                        case "redeem" -> engine.redeem(Json.str(a, "customerId"), Json.num(a, "points"), Json.str(a, "redemptionId"));
                        case "expire" -> engine.expireAll();
                        default -> throw new IllegalStateException("unknown op at line " + n);
                    }
                } catch (RuntimeException businessError) {
                    if (businessError instanceof IllegalStateException ise && ise.getMessage().startsWith("unknown op")) throw ise;
                    // The command failed when it first ran too; it changed nothing then and nothing now.
                }
            }
        }
        return n;
    }

    private static String sha256(String s) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static void truncateTornTail(Path path) throws IOException {
        if (!Files.exists(path) || Files.size(path) == 0) return;
        try (RandomAccessFile f = new RandomAccessFile(path.toFile(), "rw")) {
            long len = f.length();
            f.seek(len - 1);
            if (f.read() == '\n') return;
            long pos = len - 1;
            while (pos > 0) {
                f.seek(pos - 1);
                if (f.read() == '\n') break;
                pos--;
            }
            f.setLength(pos);
        }
    }

    @Override
    public void close() throws IOException { writer.close(); }
}
