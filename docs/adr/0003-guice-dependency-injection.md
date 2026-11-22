# ADR-0003: Wire the service with Guice

* Status: Accepted (v1.1)
* Context: [design doc §3.5](../design/0001-pointledger-design.md)

## Context

`App.main` built every object by hand. That worked, but tests that needed a different clock or log
path constructed the graph themselves, and nothing stopped two `DurableEngine` instances from
being created on the same command log (which would break the single-writer rule in ADR-0001).

## Decision

Use Google Guice. `PointLedgerModule` is the only place that knows the container:

* `App.Config` is bound as an instance; the wall clock is a `@WallClock LongSupplier`.
* `DurableEngine` and `HttpApi` are `@Provides @Singleton`, so one injector means one writer.
* The injector is created in `Stage.PRODUCTION`, so singletons are built eagerly: the log is
  replayed and verified during injector creation, and a corrupt log fails startup before the
  HTTP port opens.
* Core classes (`Ledger`, `PointsEngine`, `StackingOptimizer`) keep plain constructors with no
  Guice annotations, so they stay unit-testable without a container.
* Tests replace single bindings with `Modules.override(...)`, e.g. a fake clock that moves a
  year in milliseconds.

## Consequences

* One runtime dependency (Guice 7 and its transitive jars), fetched into `lib/` by Maven.
* Three tests cover the wiring: singleton per log, clock override, fail-fast on a tampered log.

## Alternatives

* **Keep manual wiring.** Fine at this size, but singleton-per-log is a convention, not enforced.
* **Dagger.** Compile-time graph and no reflection, but needs an annotation processor in a
  build that is deliberately plain `javac`. Worth revisiting if startup time matters.
* **Spring.** Far more than this service needs.
