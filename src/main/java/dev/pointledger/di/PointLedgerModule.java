package dev.pointledger.di;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import dev.pointledger.App;
import dev.pointledger.api.HttpApi;
import dev.pointledger.engine.DurableEngine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Guice module that wires the service: config, wall clock, the durable engine (single writer)
 * and the HTTP API.
 *
 * <p>Everything is a {@link Singleton}: there must be exactly one {@link DurableEngine} per
 * command log, because the log has one writer. With {@code Stage.PRODUCTION} the singletons are
 * built eagerly, so the log is replayed and verified while the injector is being created, and a
 * corrupt log fails startup before the HTTP port opens.
 *
 * <p>Core classes ({@code Ledger}, {@code PointsEngine}, {@code StackingOptimizer}) stay free of
 * Guice annotations; only this module knows about the container. Tests swap single bindings with
 * {@code Modules.override(new PointLedgerModule(cfg)).with(...)}.
 */
public final class PointLedgerModule extends AbstractModule {

    private final App.Config config;

    public PointLedgerModule(App.Config config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    protected void configure() {
        bind(App.Config.class).toInstance(config);
    }

    @Provides
    @Singleton
    @WallClock
    LongSupplier wallClock() {
        return System::currentTimeMillis;
    }

    @Provides
    @Singleton
    DurableEngine durableEngine(App.Config cfg, @WallClock LongSupplier clock) {
        return new DurableEngine(cfg.commandLog(), cfg.fsync(), clock, cfg.maxRewardBp(), cfg.validity());
    }

    @Provides
    @Singleton
    HttpApi httpApi(DurableEngine durable, App.Config cfg) {
        return new HttpApi(durable, staticDir(cfg));
    }

    /** The dashboard directory if it exists, otherwise {@code null} (API only). */
    public static Path staticDir(App.Config cfg) {
        return Files.isDirectory(cfg.staticDir()) ? cfg.staticDir().toAbsolutePath().normalize() : null;
    }
}
