# ADR-0001: Single writer with a write-ahead command log

* Status: Accepted
* Context: [design doc §3.2](../design/0001-pointledger-design.md)

## Context

A checkout must read a campaign's remaining budget and reserve from it atomically, often several
campaigns at once. The ledger's hash chain also needs one total order of entries, and a crash must
not leave a business operation half-applied.

## Decision

All writes go through one lock. Each command (with its timestamp) is appended to a hash-chained
JSON-lines log before the call returns, with optional `fsync`. The engine is deterministic given
the command and timestamp, so on restart the log is replayed and must reproduce the same ledger
head hash. An edited, reordered or missing line stops startup; a torn final line from a crash is
dropped.

## Consequences

* Every business operation is atomic and the hash chain has a natural total order.
* No overspend by construction (verified with 64 threads × 2,000 orders against one budget).
* Throughput is capped by one writer (~11,700 orders/s measured on 2 vCPUs); scale-out means
  sharding by seller.
* Deploys must never run two tasks on the same log (ECS deployment config enforces it).
* After a crash the HTTP idempotency store is empty, so retries reach the engine. Since v1.2 an
  exact retry of a command that already took effect is answered without a new log line (found
  by the crash drill; see [RUNBOOK.md](../RUNBOOK.md)).

## Alternatives

Optimistic concurrency per campaign, and row-level locks in a relational database. Both need a
separate mechanism to order the hash chain, and optimistic retries degrade on hot campaigns.
