# Instructions for Claude Code in this repository

PointLedger moves money-like points. Correctness beats speed. Read
`docs/design/0001-pointledger-design.md` before changing the engine.

## Commands

- Build: `./build.sh` (fetches Guice into `lib/` on first run via `./scripts/fetch-deps.sh`)
- Test: `./test.sh`. All tests must pass before you say a change is done
- Benchmarks: `./bench.sh` (only when a change touches the engine, ledger or log)
- Dashboard: `cd dashboard && npm ci && npm run build`

## Rules

1. Work on a feature branch. Never commit to `main`; changes land through a reviewed PR.
2. Never change balances directly: every movement of points is one `Ledger.transfer`.
3. Engine code must stay deterministic: no wall clock, `Random` or I/O inside `PointsEngine`
   or `Ledger`. Time comes from the injected `@WallClock` via `DurableEngine`.
4. Do not change the command-log line format without a replay-compatibility test against a log
   written by the previous version.
5. Guice belongs in `dev.pointledger.di` only. Keep core classes on plain constructors.
6. Add or update a test that fails without your change. For concurrency changes, a
   multi-threaded test.
7. Behaviour or architecture changes need a design-doc update or a new ADR in `docs/adr/`.
8. Do not invent benchmark numbers. Quote only what `./test.sh` or `./bench.sh` printed.

## When you finish

Summarise what changed, which tests you added, the `./test.sh` result, and anything the reviewer
should look at closely (use `docs/CODE_REVIEW.md`).
