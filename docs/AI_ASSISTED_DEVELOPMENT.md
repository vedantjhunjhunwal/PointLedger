# AI-assisted development

This project is developed with Claude Code (an agentic coding tool) as a pair programmer. The
agent drafts; a human owns what gets merged.

## Workflow

1. **Scope.** Write the task as a small issue with acceptance criteria, e.g. "wire the service
   with Guice; one engine per command log; tests can swap the clock".
2. **Draft on a branch.** Claude Code works from [`CLAUDE.md`](../CLAUDE.md), which carries the
   build/test commands and the rules that must not be broken (ledger-only balance changes,
   deterministic engine, log-format compatibility, no invented benchmark numbers).
3. **Verify locally.** Run `./test.sh` (and `./bench.sh` for engine changes) yourself; don't rely
   on the agent's report.
4. **Open a PR.** The template asks which parts were AI-drafted. CI runs the full test suite.
5. **Review line by line** against [CODE_REVIEW.md](CODE_REVIEW.md). Anything that cannot be
   explained gets rewritten or removed, not merged on trust.
6. **Merge** only after review and green CI.

## What the agent is used for here

- First drafts of wiring and boilerplate (e.g. the Guice module and its tests in v1.1).
- Generating adversarial test cases (tampered logs, torn tails, key reuse with a new body).
- Drafting design docs and ADRs from the code, then editing them by hand.

## What it is not trusted with

- Deciding invariants or money-movement rules.
- Benchmark numbers: only figures printed by `./test.sh` / `./bench.sh` go in docs.
- Merging to `main`.
