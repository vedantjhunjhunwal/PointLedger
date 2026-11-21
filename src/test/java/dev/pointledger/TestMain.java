package dev.pointledger;

import com.google.inject.AbstractModule;
import com.google.inject.CreationException;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Provides;
import com.google.inject.Stage;
import com.google.inject.util.Modules;
import dev.pointledger.api.HttpApi;
import dev.pointledger.di.PointLedgerModule;
import dev.pointledger.di.WallClock;
import dev.pointledger.engine.DurableEngine;
import dev.pointledger.engine.Model.*;
import dev.pointledger.engine.PointsEngine;
import dev.pointledger.ledger.InsufficientFundsException;
import dev.pointledger.ledger.Ledger;
import dev.pointledger.promo.StackingOptimizer;
import dev.pointledger.promo.StackingOptimizer.Candidate;
import dev.pointledger.util.Json;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/** Plain-Java test runner (no test framework): {@code java -cp "out/main:out/test:lib/*" dev.pointledger.TestMain}. */
public final class TestMain {

    interface Test { void run() throws Exception; }

    private static final Map<String, Test> TESTS = new LinkedHashMap<>();
    private static final Duration YEAR = Duration.ofDays(365);
    private static final long DAY = 86_400_000L;

    public static void main(String[] args) {
        TESTS.put("ledger: points are conserved over 200,000 random transfers", TestMain::conservation);
        TESTS.put("ledger: editing any past entry is detected", TestMain::tamperDetected);
        TestMain.TESTS.put("ledger: balance as of any past entry (time travel)", TestMain::asOf);
        TESTS.put("ledger: rejects unsafe account names", TestMain::names);
        TESTS.put("optimizer: equals brute force on 20,000 random orders", TestMain::optimizerExact);
        TESTS.put("tax: points are earned on the tax-exclusive price (8% and 10%)", TestMain::taxRounding);
        TESTS.put("engine: place -> ship -> redeem -> expire keeps every invariant", TestMain::lifecycle);
        TESTS.put("engine: redemption spends the oldest-expiring points first", TestMain::fifo);
        TESTS.put("engine: cancel before shipment returns points to the budget", TestMain::cancelReleases);
        TESTS.put("engine: same order twice is one order; different body is refused", TestMain::orderIdempotent);
        TESTS.put("engine: exclusive campaigns never stack; platform cap respected", TestMain::stackingRules);
        TESTS.put("engine: 64 threads x 2,000 orders never oversell a budget", TestMain::noOverspend);
        TESTS.put("durable: restart replays the log to the identical hash chain", TestMain::replay);
        TESTS.put("durable: an edited log line refuses to load", TestMain::replayTamper);
        TESTS.put("durable: a torn last line from a crash is dropped", TestMain::tornTail);
        TESTS.put("http: concurrent retries with one Idempotency-Key run once", TestMain::httpIdempotency);
        TESTS.put("json: strict parser round-trips and rejects bad input", TestMain::json);
        TESTS.put("di: injector shares one engine and API per command log", TestMain::diSingletons);
        TESTS.put("di: Modules.override swaps the wall clock for tests", TestMain::diClockOverride);
        TESTS.put("di: a tampered log fails injector creation before serving", TestMain::diFailFast);

        int pass = 0, fail = 0;
        for (var t : TESTS.entrySet()) {
            long t0 = System.nanoTime();
            try {
                t.getValue().run();
                pass++;
                System.out.printf("PASS  %-70s %6d ms%n", t.getKey(), (System.nanoTime() - t0) / 1_000_000);
            } catch (Throwable e) {
                fail++;
                System.out.printf("FAIL  %s%n      %s%n", t.getKey(), e);
                e.printStackTrace(System.out);
            }
        }
        System.out.printf("%n%d passed, %d failed%n", pass, fail);
        System.exit(fail == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------ ledger

    static void conservation() {
        Ledger l = new Ledger();
        Random r = new Random(7);
        String[] accts = {"seller:a", "seller:b", "system:mint", "campaign:x", "campaign:y", "customer:1", "customer:2", "hold:o"};
        int rejected = 0;
        for (int i = 0; i < 200_000; i++) {
            String from = accts[r.nextInt(accts.length)], to = accts[r.nextInt(accts.length)];
            if (from.equals(to)) continue;
            try { l.transfer(from, to, 1 + r.nextInt(500), "T", "r" + i, i); }
            catch (InsufficientFundsException e) { rejected++; }
        }
        Ledger.VerifyReport v = l.verify();
        check(v.ok(), "verify: " + v.message());
        check(v.sumOfBalances() == 0, "sum must be 0");
        for (var e : l.balancesSnapshot().entrySet()) {
            boolean mayGoNegative = e.getKey().startsWith("seller:") || e.getKey().startsWith("system:");
            check(mayGoNegative || e.getValue() >= 0, e.getKey() + " overdrawn: " + e.getValue());
        }
        check(rejected > 0, "expected some overdrafts to be refused");
    }

    static void tamperDetected() {
        Ledger l = new Ledger();
        for (int i = 0; i < 1000; i++) l.transfer("seller:s", "customer:c", 10, "T", "r" + i, i);
        check(l.verify().ok(), "clean ledger should verify");
        l.tamperForTest(500, 999);
        Ledger.VerifyReport v = l.verify();
        check(!v.ok() && v.brokenAtSeq() == 501, "tamper must be found at entry 501, got " + v);
    }

    static void asOf() {
        Ledger l = new Ledger();
        long expected = 0;
        Map<Long, Long> truth = new HashMap<>();
        Random r = new Random(3);
        for (int i = 0; i < 5000; i++) {
            long amt = 1 + r.nextInt(20);
            if (r.nextBoolean()) { l.transfer("seller:s", "customer:c", amt, "T", "x", i); expected += amt; }
            else { l.transfer("seller:s", "customer:d", amt, "T", "x", i); }
            truth.put(l.size(), expected);
        }
        for (int k = 0; k < 2000; k++) {
            long seq = 1 + r.nextInt((int) l.size());
            check(l.balanceAsOf("customer:c", seq) == truth.get(seq), "as-of mismatch at " + seq);
        }
        check(l.balanceAsOf("customer:c", 0) == 0, "before first entry must be 0");
    }

    static void names() {
        Ledger l = new Ledger();
        expect(IllegalArgumentException.class, () -> l.transfer("seller:a\tb", "customer:c", 1, "T", "r", 0));
        expect(IllegalArgumentException.class, () -> l.transfer("seller:a", "customer:c\n", 1, "T", "r", 0));
        expect(IllegalArgumentException.class, () -> l.transfer("seller:a", "customer:c", 0, "T", "r", 0));
    }

    // ------------------------------------------------------------------ optimizer

    static void optimizerExact() {
        Random r = new Random(42);
        int greedyWorse = 0;
        long lost = 0;
        for (int i = 0; i < 20_000; i++) {
            int n = 1 + r.nextInt(12);
            List<Candidate> cs = new ArrayList<>();
            for (int j = 0; j < n; j++) {
                String g = r.nextInt(3) == 0 ? "" : "g" + r.nextInt(4);
                cs.add(new Candidate("c" + j, g, 1 + r.nextInt(400)));
            }
            long cap = 50 + r.nextInt(1200);
            var best = StackingOptimizer.best(cs, cap);
            long brute = StackingOptimizer.bruteForce(cs, cap);
            check(best.totalPoints() == brute, "instance " + i + ": B&B " + best.totalPoints() + " != brute " + brute);
            check(best.totalPoints() <= cap, "cap exceeded");
            Set<String> groups = new HashSet<>();
            for (Candidate c : best.chosen()) if (!c.group().isEmpty()) check(groups.add(c.group()), "group stacked");
            long g = StackingOptimizer.greedy(cs, cap).totalPoints();
            check(g <= brute, "greedy cannot beat optimum");
            if (g < brute) { greedyWorse++; lost += brute - g; }
        }
        System.out.printf("      greedy was suboptimal on %d / 20000 orders (%.1f%%), avg %.1f points short when it was%n",
                greedyWorse, greedyWorse / 200.0, greedyWorse == 0 ? 0 : (double) lost / greedyWorse);
    }

    // ------------------------------------------------------------------ engine

    static PointsEngine engine(AtomicLong clock) {
        return new PointsEngine(new Ledger(), clock::get, 2000, YEAR);
    }

    static Campaign camp(String id, String seller, int bp, String group, long cap, long budget) {
        return new Campaign(id, seller, bp, group, cap, budget, 0, 0, 0);
    }

    static OrderRequest order(String id, String cust, String seller, long priceIncl, int qty) {
        return new OrderRequest(id, cust, List.of(new Line("sku-" + id, seller, priceIncl, qty, 10)));
    }

    static void taxRounding() {
        check(new Line("a", "s", 1100, 1, 10).taxExclusive() == 1000, "10%");
        check(new Line("a", "s", 1080, 1, 8).taxExclusive() == 1000, "8%");
        check(new Line("a", "s", 1099, 1, 10).taxExclusive() == 999, "floor 10%");
        check(new Line("a", "s", 333, 3, 8).taxExclusive() == 925, "floor qty");
        AtomicLong clock = new AtomicLong(0);
        PointsEngine e = engine(clock);
        e.createCampaign(camp("c1", "s1", 500, "", 10_000, 100_000));
        OrderView o = e.placeOrder(new OrderRequest("o1", "u1",
                List.of(new Line("rice", "s1", 1080, 1, 8), new Line("pan", "s1", 2200, 1, 10))));
        check(o.taxExclusiveTotal() == 3000 && o.totalPoints() == 150, "5% of 3000 = 150, got " + o);
    }

    static void lifecycle() {
        AtomicLong clock = new AtomicLong(1_000);
        PointsEngine e = engine(clock);
        e.createCampaign(camp("c1", "s1", 1000, "", 500, 10_000));
        e.placeOrder(order("o1", "u1", "s1", 5500, 1)); // 5000 excl -> 500 pts (cap 500)
        check(e.customerView("u1").balance() == 0, "points must not be spendable before shipment");
        e.ship("o1");
        check(e.customerView("u1").balance() == 500, "after ship");
        e.redeem("u1", 120, "r1");
        check(e.customerView("u1").balance() == 380, "after redeem");
        clock.addAndGet(YEAR.toMillis() + 1);
        check(e.expireAll() == 380, "remaining lot expires");
        e.closeCampaign("c1");
        CampaignView cv = e.campaigns().get(0);
        check(cv.awarded() == 500 && cv.refunded() == 9_500 && cv.available() == 0, "campaign accounting " + cv);
        check(e.ledger().balance("system:breakage") == 380 && e.ledger().balance("system:redeemed") == 120, "sinks");
        check(e.audit().ok(), "audit " + e.audit());
    }

    static void fifo() {
        AtomicLong clock = new AtomicLong(0);
        PointsEngine e = engine(clock);
        e.createCampaign(camp("c1", "s1", 1000, "", 100_000, 1_000_000));
        e.placeOrder(order("o1", "u1", "s1", 1100, 1)); e.ship("o1"); // 100 pts, expires day 365
        clock.set(10 * DAY);
        e.placeOrder(order("o2", "u1", "s1", 2200, 1)); e.ship("o2"); // 200 pts, expires day 375
        e.redeem("u1", 150, "r1"); // takes 100 from o1 lot, 50 from o2
        List<LotView> lots = e.customerView("u1").lots();
        check(lots.size() == 1 && lots.get(0).orderId().equals("o2") && lots.get(0).remaining() == 150, "lots " + lots);
        clock.set(370 * DAY);
        check(e.expireAll() == 0, "o2 lot not due yet");
        clock.set(376 * DAY);
        check(e.expireAll() == 150, "o2 lot due");
        check(e.audit().ok(), "audit");
    }

    static void cancelReleases() {
        AtomicLong clock = new AtomicLong(0);
        PointsEngine e = engine(clock);
        e.createCampaign(camp("c1", "s1", 1000, "", 1000, 300));
        e.placeOrder(order("o1", "u1", "s1", 3300, 1)); // 300 pts reserved: budget now empty
        OrderView o2 = e.placeOrder(order("o2", "u2", "s1", 3300, 1));
        check(o2.totalPoints() == 0, "budget exhausted: no points for o2");
        e.cancel("o1");
        check(e.campaigns().get(0).available() == 300, "released");
        check(e.placeOrder(order("o3", "u3", "s1", 3300, 1)).totalPoints() == 300, "budget reusable");
        e.ship("o3");
        expect(ConflictException.class, () -> e.cancel("o3"));
        check(e.audit().ok(), "audit");
    }

    static void orderIdempotent() {
        AtomicLong clock = new AtomicLong(0);
        PointsEngine e = engine(clock);
        e.createCampaign(camp("c1", "s1", 500, "", 1000, 100_000));
        OrderView a = e.placeOrder(order("o1", "u1", "s1", 2200, 2));
        long size = e.ledger().size();
        OrderView b = e.placeOrder(order("o1", "u1", "s1", 2200, 2));
        check(a.equals(b) && e.ledger().size() == size, "retry must not create entries");
        expect(ConflictException.class, () -> e.placeOrder(order("o1", "u1", "s1", 9999, 1)));
        e.ship("o1"); e.ship("o1");
        check(e.customerView("u1").balance() == a.totalPoints(), "double ship awards once");
    }

    static void stackingRules() {
        AtomicLong clock = new AtomicLong(0);
        PointsEngine e = engine(clock); // platform cap: 20% of tax-exclusive total
        e.createCampaign(camp("big", "s1", 1500, "spring", 10_000, 1_000_000));   // 15%
        e.createCampaign(camp("small", "s1", 1000, "spring", 10_000, 1_000_000)); // 10%, same group
        e.createCampaign(camp("bonus", "s1", 800, "", 10_000, 1_000_000));        // 8%, stacks
        OrderView o = e.placeOrder(order("o1", "u1", "s1", 11_000, 1));           // 10,000 excl, cap 2,000
        // options: big+bonus = 2,300 > cap; small+bonus = 1,800; big alone = 1,500 -> optimum 1,800
        check(o.totalPoints() == 1800, "expected small+bonus=1800, got " + o);
        check(o.greedyPoints() == 1500, "greedy takes 'big' first and is stuck at 1500, got " + o.greedyPoints());
    }

    static void noOverspend() throws Exception {
        AtomicLong clock = new AtomicLong(0);
        PointsEngine e = engine(clock);
        long budget = 50_000;
        e.createCampaign(camp("hot", "s1", 1000, "", 100, budget)); // each order earns 100
        int threads = 64, perThread = 2000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> fs = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int tt = t;
            fs.add(pool.submit(() -> {
                Random r = new Random(tt);
                for (int i = 0; i < perThread; i++) {
                    String id = "o" + tt + "-" + i;
                    e.placeOrder(order(id, "u" + r.nextInt(500), "s1", 1100, 1));
                    int dice = r.nextInt(10);
                    if (dice < 6) e.ship(id); else if (dice < 8) e.cancel(id);
                }
            }));
        }
        for (Future<?> f : fs) f.get();
        pool.shutdown();
        CampaignView cv = e.campaigns().get(0);
        check(cv.available() >= 0, "campaign overdrawn: " + cv);
        check(cv.reserved() + cv.awarded() + cv.available() == budget, "budget leak " + cv);
        check(e.audit().ok(), "audit " + e.audit());
        System.out.printf("      %d orders, %d ledger entries; budget %d = awarded %d + reserved %d + available %d%n",
                threads * perThread, e.ledger().size(), budget, cv.awarded(), cv.reserved(), cv.available());
    }

    // ------------------------------------------------------------------ durability

    static void workload(DurableEngine d, AtomicLong clock) {
        d.createCampaign(camp("c1", "s1", 500, "g", 400, 50_000));
        d.createCampaign(camp("c2", "s2", 800, "", 300, 20_000));
        Random r = new Random(11);
        for (int i = 0; i < 500; i++) {
            clock.addAndGet(DAY / 10);
            String id = "o" + i;
            d.placeOrder(new OrderRequest(id, "u" + (i % 37), List.of(
                    new Line("a", "s1", 1000 + r.nextInt(9000), 1 + r.nextInt(3), 10),
                    new Line("b", "s2", 500 + r.nextInt(3000), 1, 8))));
            if (i % 5 != 0) d.ship(id); else d.cancel(id);
            if (i % 7 == 0) {
                try { d.redeem("u" + (i % 37), 1 + r.nextInt(200), "r" + i); }
                catch (InsufficientFundsException ignored) { }
            }
        }
        clock.addAndGet(400 * DAY);
        d.expireAll();
        d.closeCampaign("c2");
    }

    static void replay() throws Exception {
        Path log = Files.createTempDirectory("pl").resolve("cmd.log");
        AtomicLong clock = new AtomicLong(1_700_000_000_000L);
        String head;
        long entries;
        try (DurableEngine d = new DurableEngine(log, false, clock::get, 2000, YEAR)) {
            workload(d, clock);
            head = d.engine().ledger().headHash();
            entries = d.engine().ledger().size();
            check(d.engine().audit().ok(), "audit before restart");
        }
        try (DurableEngine d2 = new DurableEngine(log, false, () -> { throw new AssertionError("clock used during replay"); }, 2000, YEAR)) {
            check(d2.engine().ledger().headHash().equals(head), "head hash differs after replay");
            check(d2.engine().ledger().size() == entries, "entry count differs");
            check(d2.engine().audit().ok(), "audit after restart");
        }
    }

    static void replayTamper() throws Exception {
        Path log = Files.createTempDirectory("pl").resolve("cmd.log");
        AtomicLong clock = new AtomicLong(1_700_000_000_000L);
        try (DurableEngine d = new DurableEngine(log, false, clock::get, 2000, YEAR)) { workload(d, clock); }
        List<String> lines = new ArrayList<>(Files.readAllLines(log));
        int idx = lines.size() / 2;
        String changed = lines.get(idx).replaceFirst("\"unitPriceInclTax\":(\\d+)", "\"unitPriceInclTax\":99999");
        if (changed.equals(lines.get(idx))) changed = lines.get(idx).replaceFirst("\"t\":(\\d+)", "\"t\":1");
        lines.set(idx, changed);
        Files.write(log, lines);
        try {
            new DurableEngine(log, false, clock::get, 2000, YEAR).close();
            throw new AssertionError("edited log should not load");
        } catch (IllegalStateException expected) {
            check(expected.getMessage().contains("diverges"), "message: " + expected.getMessage());
        }
    }

    static void tornTail() throws Exception {
        Path log = Files.createTempDirectory("pl").resolve("cmd.log");
        AtomicLong clock = new AtomicLong(1_700_000_000_000L);
        String head;
        try (DurableEngine d = new DurableEngine(log, false, clock::get, 2000, YEAR)) {
            workload(d, clock);
            head = d.engine().ledger().headHash();
        }
        Files.writeString(log, "{\"t\":1,\"op\":\"ship\",\"he", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
        try (DurableEngine d = new DurableEngine(log, false, clock::get, 2000, YEAR)) {
            check(d.engine().ledger().headHash().equals(head), "state after torn tail");
        }
    }

    // ------------------------------------------------------------------ http

    static void httpIdempotency() throws Exception {
        Path log = Files.createTempDirectory("pl").resolve("cmd.log");
        DurableEngine d = new DurableEngine(log, false, System::currentTimeMillis, 2000, YEAR);
        HttpApi api = new HttpApi(d, null);
        int port = api.start(0);
        HttpClient http = HttpClient.newHttpClient();
        try {
            String base = "http://127.0.0.1:" + port;
            var c = post(http, base + "/api/campaigns", null,
                    "{\"id\":\"c1\",\"sellerId\":\"s1\",\"rateBp\":500,\"perOrderCap\":1000,\"budget\":100000}");
            check(c.statusCode() == 201, "create campaign " + c.body());
            String body = "{\"orderId\":\"o1\",\"customerId\":\"u1\",\"lines\":[{\"sku\":\"x\",\"sellerId\":\"s1\",\"unitPriceInclTax\":11000,\"qty\":1}]}";
            long before = d.engine().ledger().size();
            ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
            List<Future<HttpResponse<String>>> fs = new ArrayList<>();
            for (int i = 0; i < 50; i++) fs.add(pool.submit(() -> post(http, base + "/api/orders", "key-1", body)));
            int replayed = 0;
            Set<String> bodies = new HashSet<>();
            for (var f : fs) {
                var r = f.get();
                check(r.statusCode() == 201, "status " + r.statusCode() + " " + r.body());
                bodies.add(r.body());
                if (r.headers().firstValue("Idempotent-Replayed").isPresent()) replayed++;
            }
            pool.shutdown();
            check(bodies.size() == 1 && replayed == 49, "one execution, 49 replays; got replays=" + replayed);
            check(d.engine().ledger().size() == before + 1, "exactly one RESERVE entry");
            var bad = post(http, base + "/api/orders", "key-1", body.replace("11000", "12000"));
            check(bad.statusCode() == 422, "key reuse with new body must be 422, got " + bad.statusCode());
            var nf = http.send(HttpRequest.newBuilder(URI.create(base + "/api/orders/nope")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            check(nf.statusCode() == 404, "404");
            var audit = http.send(HttpRequest.newBuilder(URI.create(base + "/api/audit")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            check(audit.body().contains("\"ok\":true"), "audit over http " + audit.body());
        } finally {
            api.stop();
            d.close();
        }
    }

    static HttpResponse<String> post(HttpClient http, String url, String key, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (key != null) b.header("Idempotency-Key", key);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static void json() {
        Object o = Json.parse("{\"a\":[1,2.5,\"x\\\"y\\u0041\",true,null,{}],\"b\":-3}");
        check(Json.write(o).equals("{\"a\":[1,2.5,\"x\\\"yA\",true,null,{}],\"b\":-3}"), Json.write(o));
        for (String bad : new String[]{"{", "{\"a\":}", "[1,]", "{\"a\":1} x", "\"open", "{a:1}", "-"}) {
            expect(IllegalArgumentException.class, () -> Json.parse(bad));
        }
        expect(IllegalArgumentException.class, () -> Json.parse("[".repeat(100) + "]".repeat(100)));
    }

    // ------------------------------------------------------------------ dependency injection

    static App.Config config(Path log) {
        return new App.Config(0, log, false, 2000, YEAR, log.resolveSibling("no-dashboard"));
    }

    static void diSingletons() throws Exception {
        Path log = Files.createTempDirectory("pl").resolve("cmd.log");
        AtomicLong clock = new AtomicLong(1_700_000_000_000L);
        String head;
        try (DurableEngine d = new DurableEngine(log, false, clock::get, 2000, YEAR)) {
            workload(d, clock);
            head = d.engine().ledger().headHash();
        }
        Injector injector = Guice.createInjector(Stage.PRODUCTION, new PointLedgerModule(config(log)));
        DurableEngine a = injector.getInstance(DurableEngine.class), b = injector.getInstance(DurableEngine.class);
        try {
            check(a == b, "DurableEngine must be a singleton (one writer per log)");
            check(injector.getInstance(HttpApi.class) == injector.getInstance(HttpApi.class), "HttpApi singleton");
            check(a.replayedCommands() > 0, "injector-built engine replayed the log");
            check(a.engine().ledger().headHash().equals(head), "same hash chain as the engine that wrote it");
            check(a.engine().audit().ok(), "audit");
        } finally {
            a.close();
        }
    }

    static void diClockOverride() throws Exception {
        Path log = Files.createTempDirectory("pl").resolve("cmd.log");
        long start = 1_700_000_000_000L;
        AtomicLong fake = new AtomicLong(start);
        Injector injector = Guice.createInjector(Stage.PRODUCTION,
                Modules.override(new PointLedgerModule(config(log))).with(new AbstractModule() {
                    @Provides @WallClock LongSupplier clock() { return fake::get; }
                }));
        try (DurableEngine d = injector.getInstance(DurableEngine.class)) {
            workload(d, fake); // advances the fake clock ~450 days and expires lots
            check(d.engine().audit().ok(), "audit");
            check(d.engine().ledger().size() > 0, "ledger written");
        }
        List<String> lines = Files.readAllLines(log);
        long first = ((Number) ((Map<?, ?>) Json.parse(lines.get(0))).get("t")).longValue();
        long last = ((Number) ((Map<?, ?>) Json.parse(lines.get(lines.size() - 1))).get("t")).longValue();
        check(first == start, "first command stamped by the injected clock, got " + first);
        check(last - first >= 400 * DAY, "fake clock moved commands >400 days in milliseconds of real time");
    }

    static void diFailFast() throws Exception {
        Path log = Files.createTempDirectory("pl").resolve("cmd.log");
        AtomicLong clock = new AtomicLong(1_700_000_000_000L);
        try (DurableEngine d = new DurableEngine(log, false, clock::get, 2000, YEAR)) { workload(d, clock); }
        List<String> lines = new ArrayList<>(Files.readAllLines(log));
        lines.set(1, lines.get(1).replaceFirst("\"t\":(\\d+)", "\"t\":1"));
        Files.write(log, lines);
        try {
            Guice.createInjector(Stage.PRODUCTION, new PointLedgerModule(config(log)));
            throw new AssertionError("a tampered log must stop startup");
        } catch (CreationException expected) {
            Throwable cause = expected.getErrorMessages().iterator().next().getCause();
            check(cause instanceof IllegalStateException && cause.getMessage().contains("diverges"),
                    "root cause should be the replay check, got " + cause);
        }
    }

    // ------------------------------------------------------------------ assertions

    static void check(boolean cond, String msg) { if (!cond) throw new AssertionError(msg); }

    static void expect(Class<? extends Throwable> type, Runnable r) {
        try { r.run(); }
        catch (Throwable t) {
            if (type.isInstance(t)) return;
            throw new AssertionError("expected " + type.getSimpleName() + " but got " + t);
        }
        throw new AssertionError("expected " + type.getSimpleName());
    }
}
