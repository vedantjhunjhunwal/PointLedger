package dev.pointledger;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Stage;
import dev.pointledger.api.HttpApi;
import dev.pointledger.di.PointLedgerModule;
import dev.pointledger.engine.DurableEngine;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Entry point. The object graph is wired by Guice in {@link PointLedgerModule}; this class only
 * reads configuration, builds the injector and starts the HTTP server.
 */
public final class App {

    public record Config(int port, Path commandLog, boolean fsync, long maxRewardBp, Duration validity, Path staticDir) {
        static Config fromEnv() {
            return new Config(
                    Integer.parseInt(env("PORT", "8080")),
                    Path.of(env("COMMAND_LOG", "data/commands.log")),
                    Boolean.parseBoolean(env("FSYNC", "true")),
                    Long.parseLong(env("MAX_REWARD_BP", "2000")),
                    Duration.ofDays(Long.parseLong(env("POINT_VALIDITY_DAYS", "365"))),
                    Path.of(env("STATIC_DIR", "dashboard/dist")));
        }

        private static String env(String k, String d) {
            String v = System.getenv(k);
            return v == null || v.isBlank() ? d : v;
        }
    }

    public static void main(String[] args) throws Exception {
        Config cfg = Config.fromEnv();
        long t0 = System.nanoTime();
        // PRODUCTION stage builds singletons eagerly: the command log is replayed and verified here.
        Injector injector = Guice.createInjector(Stage.PRODUCTION, new PointLedgerModule(cfg));
        DurableEngine durable = injector.getInstance(DurableEngine.class);
        HttpApi api = injector.getInstance(HttpApi.class);
        int port = api.start(cfg.port());
        var audit = durable.engine().audit();
        Path staticDir = PointLedgerModule.staticDir(cfg);
        System.out.printf("PointLedger on :%d | replayed %d commands in %d ms | audit ok=%s | dashboard=%s%n",
                port, durable.replayedCommands(), (System.nanoTime() - t0) / 1_000_000, audit.ok(),
                staticDir == null ? "off" : staticDir);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            api.stop();
            try { durable.close(); } catch (Exception ignored) {}
        }));
    }
}
