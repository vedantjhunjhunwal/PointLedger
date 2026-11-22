## What and why

<!-- One paragraph. Link the design doc / ADR if behaviour or architecture changes. -->

## How it was tested

<!-- Which tests were added or changed; paste the ./test.sh summary line. -->

## AI assistance

- [ ] No AI-drafted code in this PR
- [ ] Parts were drafted with an AI coding agent (list files/areas below); I reviewed every line,
      ran the tests myself and can explain each change

## Review checklist

See [docs/CODE_REVIEW.md](../docs/CODE_REVIEW.md). Tick what applies:

- [ ] Points move only through balanced ledger transfers, atomically
- [ ] Writes are idempotent (key and business ID); commands replay deterministically
- [ ] Tests fail without the change; concurrency changes have a multi-threaded test
- [ ] `/api/audit` still ok; design doc / ADR updated if needed
- [ ] Deploy impact stated (log format, replay compatibility)
