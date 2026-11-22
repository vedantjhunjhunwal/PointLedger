# Code review checklist

Every change reaches `main` through a pull request that passes CI and is reviewed against this
list. Changes drafted with an AI coding agent get the same review as any other change, and the
PR says which parts were AI-drafted (see [AI-assisted development](AI_ASSISTED_DEVELOPMENT.md)).

## Money-safety (block the PR if any answer is "no" or "not sure")

- [ ] Every new movement of points is a `Ledger.transfer` between two accounts. No balance is
      stored or edited directly.
- [ ] Multi-transfer operations run inside one `atomically` block / one command.
- [ ] New write endpoints accept `Idempotency-Key`, and the engine is idempotent on the business
      ID (order ID, redemption ID) as well.
- [ ] New commands are deterministic given their arguments and timestamp (no `System.currentTimeMillis()`,
      `Random` or I/O inside the engine), so replay reproduces the same hash chain.
- [ ] Rounding is down to the yen and happens on the tax-exclusive amount.
- [ ] `GET /api/audit` still reports `ok: true` after the new test scenario.

## Correctness and tests

- [ ] A test fails without the change and passes with it.
- [ ] Concurrency changes have a multi-threaded test, not only a single-threaded one.
- [ ] Error paths return the right status (400 bad input, 404 unknown ID, 409 conflict,
      422 insufficient points or idempotency-key reuse with a different body).

## Design and operability

- [ ] A change in behaviour or architecture updates the design doc or adds an ADR in `docs/`.
- [ ] Guice stays in `dev.pointledger.di`; core classes keep plain constructors.
- [ ] New failure modes are visible: a metric, a log line, or an audit check.
- [ ] Deploy impact is stated (log format change? replay compatibility with existing logs?).

## Security

- [ ] Request bodies stay size-limited; account names stay validated by `Ledger`.
- [ ] No secrets, keys or customer data in code, logs, tests or the PR description.
