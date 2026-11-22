# Design: PointLedger, a seller points and promotions engine

| | |
|---|---|
| Status | Accepted, implemented in v1.0–1.1 |
| Author | Vedant Jhunjhunwala |
| Decision records | [ADR-0001](../adr/0001-single-writer-command-log.md), [ADR-0002](../adr/0002-exact-stacking-optimizer.md), [ADR-0003](../adr/0003-guice-dependency-injection.md) |

## 1. Context

Marketplaces in Japan award loyalty points on almost every order, and much of that is funded by
sellers through their own campaigns ("+5% points on my store this week"). Once sellers fund
points, points behave like money: a double award, a budget overspend or an unexplainable balance
costs either the seller or the platform.

This document covers how sellers fund campaigns, how an order picks campaigns and reserves
points, and how points are awarded, redeemed, expired and audited.

## 2. Requirements

**Functional**

1. A seller funds a campaign with a budget, a rate (basis points), an optional exclusive group,
   a per-order cap, a minimum subtotal and an active window. Closing a campaign refunds the
   unspent budget.
2. At checkout an order picks the best set of eligible campaigns, subject to exclusive groups
   and a platform-wide cap (`MAX_REWARD_BP`, default 20% of the order).
3. Points are reserved at checkout, awarded at shipment and released on cancel.
4. Customers redeem oldest-expiring points first; points expire after a validity period.
5. Points are earned on the tax-exclusive price per line (10% standard and 8% reduced rates),
   rounded down to the yen.

**Non-functional**

| Property | Target |
|---|---|
| Correctness | No double award under client retries; no campaign overspend under concurrency |
| Auditability | Any balance at any past point is reconstructable; tampering is detectable |
| Durability | A crash loses no acknowledged write; restart reproduces identical state |
| Latency | Durable write p99 ≤ 2 ms with fsync on commodity disks |
| Operability | One container, health check, metrics endpoint, structured startup line |

**Non-goals (v1)**: multi-region writes, clawing points back after a post-shipment return,
customer wallets that span shards.

## 3. Proposed design

### 3.1 Ledger as the source of truth

Every movement of points is a balanced transfer between two accounts in an append-only,
double-entry ledger (`seller:`, `campaign:`, `hold:`, `customer:`, `system:`). Balances are
derived, never stored as mutable numbers. Each entry carries the SHA-256 hash of the previous
entry, so editing history breaks the chain at a known sequence number.

```
seller:S --FUND--> campaign:C --RESERVE--> hold:O --AWARD--> customer:U --REDEEM--> system:redeemed
                       ^                      |                   |
                       +-------RELEASE--------+                   +--EXPIRE--> system:breakage
```

Invariants, checked after every test and exposed at `GET /api/audit`:
Σ balances = 0; the hash chain re-derives; per campaign, budget = available + reserved + awarded +
refunded with available ≥ 0; per customer, balance = sum of unexpired lots.

### 3.2 Concurrency and durability

All writes pass through a single writer (one lock) and a write-ahead command log. See ADR-0001
for the alternatives considered. Reads, JSON work and HTTP handling run in parallel on virtual
threads.

### 3.3 Exactly-once API

Every POST accepts `Idempotency-Key`. The HTTP layer stores key → (request fingerprint, future
of the response). A concurrent duplicate waits on the first request's future; a later retry gets
the stored response with `Idempotent-Replayed: true`; a reused key with a different body gets
HTTP 422. Below the API, order and redemption IDs are also idempotent in the engine, so dedupe
survives restarts even though the key store is in memory.

### 3.4 Campaign selection

Choosing campaigns is a multiple-choice knapsack (one campaign per exclusive group, a global
cap). It is solved exactly by branch-and-bound; a greedy baseline is computed alongside for
comparison. See ADR-0002.

### 3.5 Wiring

Guice builds the object graph in one module (`PointLedgerModule`). See ADR-0003.

## 4. Alternatives considered

| Option | Why not chosen |
|---|---|
| Mutable balance rows with `UPDATE … SET balance = balance - x` | No history, no audit; correctness depends on every caller locking correctly |
| Optimistic concurrency (version check + retry) per campaign | Hot campaigns retry storm under flash sales; the hash chain still needs one total order |
| Greedy "largest rate first" campaign pick | Measured suboptimal on 36.1% of 20,000 random orders (≈33 points short when it was) |
| An external ILP/MIP solver | Heavy dependency for problems with ≤ tens of campaigns; branch-and-bound is exact and fast enough |

## 5. Operations

* **Health:** `/health` is used by the ALB target group (15 s interval).
* **Metrics:** `GET /api/metrics` reports requests, idempotent replays and errors; the startup
  line reports commands replayed, replay time and audit status.
* **Fail-fast startup:** a corrupt or edited command log stops injector creation before the HTTP
  port opens (tested in `di: a tampered log fails injector creation before serving`).
* **Deploy:** CloudFormation (`deploy/aws/pointledger-ecs.yaml`), one ECS Fargate task, command log
  on encrypted EFS. Deploys stop the old task before starting the new one, because the log has a
  single writer.
* **Alarms (in the template, to an SNS topic with optional email):** ledger audit failed at
  startup (log metric filter on `audit ok=false`); task failing `/health` for 2 minutes;
  5 or more 5xx responses in 5 minutes.
* **Not yet monitored:** replay time on restart (a log-growth signal) and idempotency replay rate.
* **Crash drill:** `scripts/crash_drill.py` kills the service under load and checks recovery (no
  acknowledged write lost, no double award, audit ok). It found that client retries after a
  restart were re-logged and doubled the log; fixed in v1.2. Procedures and results:
  [RUNBOOK.md](../RUNBOOK.md).

## 6. Testing

`./test.sh` runs 21 tests: conservation over 200,000 random transfers, tamper detection,
time-travel balances, optimizer = brute force on 20,000 orders, tax rounding, FIFO redemption,
64-thread overspend test, replay to an identical hash chain, torn-tail recovery, HTTP retries
under one key, exact retries after a restart not being re-logged, a strict JSON parser, and three
dependency-injection tests. CI also runs a short crash drill on every push.

## 7. Risks and open questions

1. **Single writer ceiling.** Measured ~11,700 orders/s on 2 vCPUs. The scale-out path is to
   shard by seller (budgets are per seller), one log per shard; cross-shard customer wallets
   would then need a two-phase transfer.
2. **Log growth.** Replay time grows with history (76,000 commands in 3.5 s). Exact retries are
   no longer logged (v1.2); periodic snapshots with the head hash would bound restart time.
3. **Post-shipment returns** are not modelled: should a return claw points back from the
   customer, and what if they were already redeemed?
