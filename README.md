# PointLedger

**Exactly-once loyalty points and seller-funded promotions on a hash-chained, double-entry ledger.**
Java 21 service wired with Google Guice (its only runtime dependency), a React + TypeScript
dashboard, and an AWS (ECS Fargate + EFS) deployment template.

Marketplaces in Japan pay out points on almost every order, and much of that is funded by sellers
running their own campaigns ("+5% points on my store this week"). That makes points behave like
money, and the hard problems are the same ones payment systems have:

| Problem | What goes wrong in a naive system | How PointLedger handles it |
|---|---|---|
| Retries | A client times out, retries, and the customer gets points twice | `Idempotency-Key` on every write; concurrent duplicates wait for the first and get its stored response |
| Budgets under load | Two checkouts read "budget left: 100" at the same time and both spend it | Points are **reserved** from the campaign at checkout inside one atomic step, then awarded at shipment or released on cancel |
| Audit | Balances are mutable numbers nobody can reconstruct | Every change is a balanced transfer in an append-only, SHA-256 hash-chained ledger; any balance at any past entry can be queried |
| Stacking | "Take the biggest offer first" leaves points on the table when a per-order cap binds | Exact branch-and-bound optimizer for the multiple-choice knapsack (exclusive groups + platform cap) |
| Crashes | A crash mid-write leaves state half-updated | Write-ahead command log, deterministic replay, torn-tail recovery, hash-chained log lines |
| Expiry | Points expire in the wrong order | Lots are spent and expired oldest-expiry-first (FIFO), and expired points move to a breakage account |
| Tax | Points computed on the tax-inclusive price | Points are earned on the tax-exclusive price per line, with Japan's 10% and 8% (reduced) rates, rounded down to the yen |

## How points move

Every arrow is one ledger transfer, so the sum of all balances is always exactly zero.

```
seller:S --FUND--> campaign:C --RESERVE--> hold:O --AWARD--> customer:U --REDEEM--> system:redeemed
                       ^                      |                   |
                       +-------RELEASE--------+                   +--EXPIRE--> system:breakage
campaign:C --REFUND--> seller:S        (when a campaign is closed)
```

Invariants checked after every test and exposed at `GET /api/audit`:

1. Σ all balances = 0 (points are never created or destroyed);
2. the hash chain re-derives from the first entry;
3. every campaign: budget = available + reserved + awarded + refunded, and available ≥ 0;
4. every customer: balance = sum of their unexpired lots.

## Architecture

```
Guice injector (PointLedgerModule, Stage.PRODUCTION)
HTTP (JDK HttpServer, virtual thread per request)
  └─ HttpApi ── idempotency store (key → request fingerprint → stored response)
       └─ DurableEngine ── write-ahead command log (JSON lines, hash-chained, optional fsync)
            └─ PointsEngine ── campaigns, orders, lots, redemptions, expiry
                 ├─ StackingOptimizer ── exact branch-and-bound + greedy baseline
                 └─ Ledger ── single-writer, double-entry, SHA-256 hash chain, as-of queries
```

* **Single writer.** All writes pass through one lock. That gives the total order the hash chain
  needs and makes each business operation (several transfers) atomic. Reads of the HTTP layer and
  JSON work run in parallel on virtual threads.
* **Event sourcing.** The engine is deterministic given each command and its timestamp, so the
  command log is the source of truth. On restart it is replayed and must reproduce the same ledger
  head hash; an edited, reordered or missing line stops startup.
* **Dependency injection with Guice.** `PointLedgerModule` is the only place that builds the
  graph. `DurableEngine` and `HttpApi` are singletons, so one injector means one writer per log.
  The injector runs in `Stage.PRODUCTION`, so the log is replayed and verified while it is being
  created and a tampered log stops startup before the port opens. The clock is a
  `@WallClock LongSupplier`; tests swap it with `Modules.override` to run years of expiry in
  milliseconds. Core classes keep plain constructors and no Guice annotations
  ([ADR-0003](docs/adr/0003-guice-dependency-injection.md)).

## Measured results

From `./test.sh` and `./bench.sh` on a 2-vCPU Linux container (JDK 21). Client and server
share the same two cores in the HTTP test, so treat throughput as a floor, not a ceiling.

| What | Result |
|---|---|
| Test suite | 20/20 passing |
| Optimizer vs brute force | identical optimum on 20,000 random orders |
| Greedy baseline | suboptimal on 36.1% of those orders (≈33 points short when it was) |
| Concurrency | 64 threads × 2,000 orders against one budget: zero overspend, all invariants hold |
| Engine throughput | ~11,700 orders/s (place + ship/cancel) over 1,000 campaigns, 16 threads, ~1.6 M ledger entries |
| Durable write (no fsync) | p50 98 µs, p99 340 µs per order |
| Durable write (fsync) | p50 382 µs, p99 1.1 ms per order |
| Restart | 76,000 commands replayed in 3.5 s to the identical hash chain |
| HTTP | 19,200 orders from 64 concurrent clients incl. 1,928 retries: ~1,200 req/s, p50 47 ms, p99 121 ms, 0 errors, 0 double awards |

The test suite also covers tamper detection (an edited entry is found at the exact sequence
number), time-travel balances, FIFO redemption, tax rounding, cancel-before-ship, a torn final
log line, key reuse with a different body (HTTP 422), a strict JSON parser, and the Guice wiring
(one engine per log, clock override, fail-fast startup on a tampered log).

## Run it

Needs JDK 21+ and Maven (once, to fetch Guice into `lib/`); Node 20+ only for the dashboard.

```bash
./scripts/fetch-deps.sh   # Guice jars into lib/ (build.sh runs this if lib/ is empty)
./test.sh                 # 20 tests
./bench.sh                # benchmarks
./run.sh                  # builds the dashboard if needed, serves API + UI on :8080
./scripts/demo.sh         # seeds demo campaigns and orders into a running server
```

Windows: `build.cmd`, then `java -cp "out\main;out\test;lib\*" dev.pointledger.TestMain`.

Docker: `docker build -t pointledger . && docker run -p 8080:8080 -v pl-data:/data pointledger`.

AWS: push the image to ECR, then deploy `deploy/aws/pointledger-ecs.yaml` (CloudFormation:
ECS Fargate task behind an ALB, command log on encrypted EFS, CloudWatch logs, and CloudWatch alarms
for a failed ledger audit, an unhealthy task and 5xx responses, sent to an SNS topic). The service runs
one task and deploys stop the old task before starting the new one, because the command log has a
single writer.

## API

| Method | Path | Notes |
|---|---|---|
| POST | `/api/campaigns` | `{id, sellerId, rateBp, group?, perOrderCap, budget, minSubtotal?, startsAt?, endsAt?}` funds the campaign from the seller |
| POST | `/api/campaigns/{id}/close` | refunds the unspent budget to the seller |
| POST | `/api/orders` | `{orderId, customerId, lines:[{sku, sellerId, unitPriceInclTax, qty, taxRatePct}]}` picks campaigns and reserves points |
| POST | `/api/orders/{id}/ship` / `cancel` | award to the customer / release to the campaign |
| GET | `/api/orders/{id}`, `/api/customers/{id}` | order with chosen vs greedy points; wallet with lots |
| POST | `/api/customers/{id}/redeem` | `{points, redemptionId}` spends oldest-expiring lots first |
| POST | `/api/admin/expire` | moves due lots to breakage |
| GET | `/api/audit`, `/api/ledger?limit=`, `/api/ledger/balance?account=&asOf=`, `/api/metrics` | audit and history |

Every POST accepts `Idempotency-Key`. A replayed response carries `Idempotent-Replayed: true`.

## Limits and next steps

* One writer node. Scaling out would shard by seller (campaign budgets are per seller) with one
  log per shard; customer wallets that span shards would then need a two-phase transfer.
* The idempotency store is in memory with a size cap; across restarts, order and redemption IDs
  still dedupe because the engine itself is idempotent on them.
* Returns after shipment (clawing points back) are not modelled.
* Benchmarks use synthetic data.

## Engineering process

* [Design doc](docs/design/0001-pointledger-design.md): requirements, alternatives, operations, risks.
* Decision records: [single writer](docs/adr/0001-single-writer-command-log.md),
  [exact optimizer](docs/adr/0002-exact-stacking-optimizer.md),
  [Guice](docs/adr/0003-guice-dependency-injection.md).
* Every change lands through a PR reviewed against [CODE_REVIEW.md](docs/CODE_REVIEW.md), with CI green.
* Built with Claude Code as a pair programmer under the rules in [CLAUDE.md](CLAUDE.md); see
  [AI-assisted development](docs/AI_ASSISTED_DEVELOPMENT.md) for what the agent does and doesn't do.

## License

MIT
