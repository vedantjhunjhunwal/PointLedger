package dev.pointledger.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.pointledger.engine.Codec;
import dev.pointledger.util.Json;
import dev.pointledger.engine.DurableEngine;
import dev.pointledger.engine.Model.ConflictException;
import dev.pointledger.engine.Model.NotFoundException;
import dev.pointledger.engine.PointsEngine;
import dev.pointledger.ledger.InsufficientFundsException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * REST API on the JDK's built-in HTTP server, one virtual thread per request.
 *
 * <p>Every POST may carry an {@code Idempotency-Key} header. The first request with a key runs;
 * any retry with the same key and the same body gets the stored response back (marked with
 * {@code Idempotent-Replayed: true}) without running again, and a concurrent duplicate waits for
 * the first one instead of racing it. Reusing a key with a different body is rejected with 422.
 */
public final class HttpApi {

    private static final int MAX_BODY = 1 << 20;
    private static final int MAX_KEYS = 200_000;

    private final DurableEngine durable;
    private final PointsEngine engine;
    private final Path staticDir;
    private final ConcurrentHashMap<String, Stored> idem = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<String> idemOrder = new ConcurrentLinkedQueue<>();
    private final AtomicLong requests = new AtomicLong(), replays = new AtomicLong(), errors = new AtomicLong();
    private HttpServer server;

    private record Response(int status, String body) {}

    private record Stored(String fingerprint, CompletableFuture<Response> result) {}

    public HttpApi(DurableEngine durable, Path staticDir) {
        this.durable = durable;
        this.engine = durable.engine();
        this.staticDir = staticDir;
    }

    public int start(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 1024);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", this::handle);
        server.start();
        return server.getAddress().getPort();
    }

    public void stop() { if (server != null) server.stop(0); }

    private void handle(HttpExchange ex) throws IOException {
        requests.incrementAndGet();
        try (ex) {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            if (!path.startsWith("/api/") && !path.equals("/health")) {
                serveStatic(ex, path);
                return;
            }
            byte[] body = readBody(ex.getRequestBody());
            String key = ex.getRequestHeaders().getFirst("Idempotency-Key");
            Response r;
            if (method.equals("POST") && key != null && !key.isBlank()) {
                r = idempotent(key, method + " " + path + " " + sha256(body), () -> route(method, path, ex, body), ex);
            } else {
                r = safely(() -> route(method, path, ex, body));
            }
            send(ex, r);
        }
    }

    private Response idempotent(String key, String fingerprint, Callable<Response> work, HttpExchange ex) {
        if (key.length() > 128) return new Response(400, err("Idempotency-Key too long"));
        CompletableFuture<Response> mine = new CompletableFuture<>();
        Stored prior = idem.putIfAbsent(key, new Stored(fingerprint, mine));
        if (prior != null) {
            if (!prior.fingerprint().equals(fingerprint)) {
                return new Response(422, err("Idempotency-Key reused with a different request"));
            }
            replays.incrementAndGet();
            ex.getResponseHeaders().set("Idempotent-Replayed", "true");
            return prior.result().join(); // waits if the first request is still running
        }
        idemOrder.add(key);
        while (idem.size() > MAX_KEYS) {
            String old = idemOrder.poll();
            if (old == null) break;
            idem.remove(old);
        }
        Response r = safely(work);
        mine.complete(r);
        return r;
    }

    private Response safely(Callable<Response> work) {
        try {
            return work.call();
        } catch (NotFoundException e) {
            return fail(404, e);
        } catch (ConflictException e) {
            return fail(409, e);
        } catch (InsufficientFundsException e) {
            return fail(422, e);
        } catch (IllegalArgumentException e) {
            return fail(400, e);
        } catch (Exception e) {
            return fail(500, e);
        }
    }

    private Response fail(int status, Exception e) {
        errors.incrementAndGet();
        return new Response(status, err(e.getMessage() == null ? e.toString() : e.getMessage()));
    }

    private Response route(String method, String path, HttpExchange ex, byte[] body) {
        String[] p = path.substring(1).split("/");
        Map<String, String> q = query(ex.getRequestURI().getRawQuery());
        String route = method + " " + String.join("/", Arrays.copyOfRange(p, 0, Math.min(p.length, 2)));
        return switch (route) {
            case "GET health" -> ok(Map.of("status", "up"));
            case "GET api/campaigns" -> ok(engine.campaigns());
            case "POST api/campaigns" -> {
                if (p.length == 2) yield created(durable.createCampaign(Codec.campaign(json(body))));
                if (p.length == 4 && p[3].equals("close")) yield ok(durable.closeCampaign(p[2]));
                yield notFound();
            }
            case "POST api/orders" -> {
                if (p.length == 2) yield created(durable.placeOrder(Codec.order(json(body))));
                if (p.length == 4 && p[3].equals("ship")) yield ok(durable.ship(p[2]));
                if (p.length == 4 && p[3].equals("cancel")) yield ok(durable.cancel(p[2]));
                yield notFound();
            }
            case "GET api/orders" -> p.length == 3 ? ok(engine.orderView(p[2])) : notFound();
            case "GET api/customers" -> p.length == 3 ? ok(engine.customerView(p[2])) : notFound();
            case "POST api/customers" -> {
                if (p.length != 4 || !p[3].equals("redeem")) yield notFound();
                Map<String, Object> m = json(body);
                yield ok(durable.redeem(p[2], Json.num(m, "points"), Json.str(m, "redemptionId")));
            }
            case "POST api/admin" -> p.length == 3 && p[2].equals("expire")
                    ? ok(Map.of("expiredPoints", durable.expireAll())) : notFound();
            case "GET api/audit" -> ok(engine.audit());
            case "GET api/ledger" -> {
                if (p.length == 3 && p[2].equals("balance")) {
                    String acct = q.getOrDefault("account", "");
                    long asOf = Long.parseLong(q.getOrDefault("asOf", String.valueOf(engine.ledger().size())));
                    yield ok(Map.of("account", acct, "asOf", asOf, "balance", engine.ledger().balanceAsOf(acct, asOf)));
                }
                int limit = Math.min(500, Integer.parseInt(q.getOrDefault("limit", "50")));
                yield ok(Map.of("size", engine.ledger().size(), "head", engine.ledger().headHash(),
                        "entries", engine.ledger().tail(limit)));
            }
            case "GET api/metrics" -> ok(Map.of("requests", requests.get(), "idempotentReplays", replays.get(),
                    "errors", errors.get(), "ledgerEntries", engine.ledger().size()));
            default -> notFound();
        };
    }

    // ---------------------------------------------------------------- helpers

    private static Map<String, Object> json(byte[] body) {
        return Json.obj(Json.parse(new String(body, StandardCharsets.UTF_8)));
    }

    private static Response ok(Object o) { return new Response(200, Json.write(o)); }
    private static Response created(Object o) { return new Response(201, Json.write(o)); }
    private static Response notFound() { return new Response(404, err("no such route")); }
    private static String err(String m) { return Json.write(Map.of("error", m)); }

    private static byte[] readBody(InputStream in) throws IOException {
        byte[] b = in.readNBytes(MAX_BODY + 1);
        if (b.length > MAX_BODY) throw new IOException("body too large");
        return b;
    }

    private static void send(HttpExchange ex, Response r) throws IOException {
        byte[] b = r.body().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(r.status(), b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    private void serveStatic(HttpExchange ex, String path) throws IOException {
        if (staticDir == null) { send(ex, notFound()); return; }
        Path f = staticDir.resolve(path.equals("/") ? "index.html" : path.substring(1)).normalize();
        if (!f.startsWith(staticDir) || !Files.isRegularFile(f)) f = staticDir.resolve("index.html");
        if (!Files.isRegularFile(f)) { send(ex, notFound()); return; }
        String name = f.getFileName().toString();
        String type = name.endsWith(".html") ? "text/html" : name.endsWith(".js") ? "text/javascript"
                : name.endsWith(".css") ? "text/css" : name.endsWith(".svg") ? "image/svg+xml" : "application/octet-stream";
        byte[] b = Files.readAllBytes(f);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(200, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null) return m;
        for (String kv : raw.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0) m.put(URLDecoder.decode(kv.substring(0, i), StandardCharsets.UTF_8),
                    URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    private static String sha256(byte[] b) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
