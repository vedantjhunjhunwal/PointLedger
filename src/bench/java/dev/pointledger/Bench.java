package dev.pointledger;

import dev.pointledger.api.HttpApi;
import dev.pointledger.engine.DurableEngine;
import dev.pointledger.engine.Model.*;
import dev.pointledger.engine.PointsEngine;
import dev.pointledger.ledger.Ledger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Reproducible benchmarks. Numbers depend on the machine; the README lists the ones we measured. */
public final class Bench {
    private static final Duration YEAR = Duration.ofDays(365);

    public static void main(String[] args) throws Exception {
        System.out.printf("Java %s, %d CPUs%n%n", Runtime.version(), Runtime.getRuntime().availableProcessors());
        engineThroughput();
        durable(false, 50_000);
        durable(true, 2_000);
        http();
    }

    static void seed(java.util.function.Consumer<Campaign> create) {
        for (int i = 0; i < 1_000; i++) {
            create.accept(new Campaign("c" + i, "s" + (i % 200), 100 + (i % 9) * 100, i % 3 == 0 ? "g" + (i % 7) : "",
                    5_000, 50_000_000, 0, 0, 0));
        }
    }

    static OrderRequest order(Random r, String id) {
        int n = 1 + r.nextInt(4);
        List<Line> lines = new ArrayList<>();
        for (int k = 0; k < n; k++) {
            lines.add(new Line("sku" + r.nextInt(10_000), "s" + r.nextInt(200), 500 + r.nextInt(20_000),
                    1 + r.nextInt(3), r.nextInt(4) == 0 ? 8 : 10));
        }
        return new OrderRequest(id, "u" + r.nextInt(100_000), lines);
    }

    static void engineThroughput() throws Exception {
        PointsEngine e = new PointsEngine(new Ledger(), System::currentTimeMillis, 2000, YEAR);
        seed(e::createCampaign);
        int threads = 16, per = 6_250;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        long t0 = System.nanoTime();
        List<Future<?>> fs = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int tt = t;
            fs.add(pool.submit(() -> {
                Random r = new Random(tt);
                for (int i = 0; i < per; i++) {
                    String id = "o" + tt + "_" + i;
                    e.placeOrder(order(r, id));
                    if (r.nextInt(10) < 8) e.ship(id); else e.cancel(id);
                }
            }));
        }
        for (Future<?> f : fs) f.get();
        double s = (System.nanoTime() - t0) / 1e9;
        pool.shutdown();
        long orders = (long) threads * per;
        var audit = e.audit();
        System.out.printf("[engine]  %,d orders (place + ship/cancel) from %d threads over 1,000 campaigns: %,.0f orders/s, %,d ledger entries, audit ok=%s%n",
                orders, threads, orders / s, e.ledger().size(), audit.ok());
    }

    static void durable(boolean fsync, int n) throws Exception {
        Path log = Files.createTempDirectory("plb").resolve("cmd.log");
        AtomicLong clock = new AtomicLong(1_700_000_000_000L);
        long[] lat = new long[n];
        String head;
        try (DurableEngine d = new DurableEngine(log, fsync, () -> clock.addAndGet(1), 2000, YEAR)) {
            seed(d::createCampaign);
            Random r = new Random(1);
            for (int i = 0; i < n; i++) {
                long t0 = System.nanoTime();
                d.placeOrder(order(r, "o" + i));
                lat[i] = System.nanoTime() - t0;
                if (i % 2 == 0) d.ship("o" + i);
            }
            head = d.engine().ledger().headHash();
        }
        Arrays.sort(lat);
        long size = Files.size(log);
        long t0 = System.nanoTime();
        long replayed;
        boolean same;
        try (DurableEngine d2 = new DurableEngine(log, false, () -> 0, 2000, YEAR)) {
            replayed = d2.replayedCommands();
            same = d2.engine().ledger().headHash().equals(head) && d2.engine().audit().ok();
        }
        double replayMs = (System.nanoTime() - t0) / 1e6;
        System.out.printf("[durable fsync=%s] %,d orders: write p50 %.1f us, p99 %.1f us | log %.1f MB | replay of %,d commands %.0f ms, identical hash chain=%s%n",
                fsync, n, lat[n / 2] / 1e3, lat[(int) (n * 0.99)] / 1e3, size / 1e6, replayed, replayMs, same);
    }

    static void http() throws Exception {
        Path log = Files.createTempDirectory("plh").resolve("cmd.log");
        DurableEngine d = new DurableEngine(log, false, System::currentTimeMillis, 2000, YEAR);
        seed(d::createCampaign);
        HttpApi api = new HttpApi(d, null);
        int port = api.start(0);
        String url = "http://127.0.0.1:" + port + "/api/orders";
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        int clients = 64, per = 300;
        long[] lat = new long[clients * per];
        AtomicLong idx = new AtomicLong(), replays = new AtomicLong(), errors = new AtomicLong();
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        long t0 = System.nanoTime();
        List<Future<?>> fs = new ArrayList<>();
        for (int c = 0; c < clients; c++) {
            int cc = c;
            fs.add(pool.submit(() -> {
                Random r = new Random(cc);
                String lastKey = null, lastBody = null;
                for (int i = 0; i < per; i++) {
                    String key, body;
                    if (lastKey != null && r.nextInt(10) == 0) { key = lastKey; body = lastBody; } // client retry
                    else {
                        key = "k" + cc + "_" + i;
                        body = dev.pointledger.util.Json.write(order(r, "o" + cc + "_" + i));
                    }
                    long s = System.nanoTime();
                    var resp = http.send(HttpRequest.newBuilder(URI.create(url)).header("Idempotency-Key", key)
                            .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
                    lat[(int) idx.getAndIncrement()] = System.nanoTime() - s;
                    if (resp.statusCode() != 201) errors.incrementAndGet();
                    if (resp.headers().firstValue("Idempotent-Replayed").isPresent()) replays.incrementAndGet();
                    lastKey = key;
                    lastBody = body;
                }
                return null;
            }));
        }
        for (Future<?> f : fs) f.get();
        double s = (System.nanoTime() - t0) / 1e9;
        pool.shutdown();
        api.stop();
        Arrays.sort(lat);
        int n = lat.length;
        System.out.printf("[http]    %,d POST /api/orders from %d concurrent clients (%,d were retries): %,.0f req/s, p50 %.2f ms, p99 %.2f ms, errors %d, audit ok=%s%n",
                n, clients, replays.get(), n / s, lat[n / 2] / 1e6, lat[(int) (n * 0.99)] / 1e6, errors.get(), d.engine().audit().ok());
        d.close();
    }
}
