# Operations runbook

PointLedger runs as one ECS Fargate task (single writer) with its command log on EFS. The
CloudFormation template (`deploy/aws/pointledger-ecs.yaml`) creates three alarms that notify an
SNS topic (`AlarmTopicArn` output; subscribe an email with the `AlarmEmail` parameter).

## Alarms and first response

| Alarm | Fires when | First checks | Likely fix |
|---|---|---|---|
| `AuditFailureAlarm` | The startup line says `audit ok=false` (log metric filter) | `GET /api/audit`: which invariant failed, and `ledger.brokenAtSeq` | Stop writes (ECS desired count 0), copy the command log off EFS for investigation, and open an incident. Never edit the log by hand: replay verifies its hash chain and will refuse it. (The template has no EFS backup yet; enabling AWS Backup on the file system is an open item) |
| `UnhealthyTaskAlarm` | The task fails `/health` for 2 minutes | ECS events for the task; CloudWatch log group `/pointledger` for the startup line or a stack trace | `command log diverges` at startup → see above. Out of memory → raise task memory. EFS mount errors → check the mount targets and security group |
| `Http5xxAlarm` | 5 or more 5xx responses in 5 minutes | `GET /api/metrics` (`errors`), log group for exceptions | A 5xx is a bug, not a business error (those are 4xx). Roll back to the previous image if it started with a deploy |

Health: `GET /health`. Metrics: `GET /api/metrics` (requests, idempotent replays, errors, ledger entries).

## Crash drill

`scripts/crash_drill.py` kills the service with SIGKILL under concurrent load, restarts it on the
same log and checks recovery. It runs in CI on every push (1 short run); run the full drill
before changing the engine, ledger or command log:

```bash
./build.sh && python3 scripts/crash_drill.py --runs 3 --kill-after 8
```

Pass criteria per run: 0 acknowledged writes lost; every retried request answered without error;
`/api/audit` ok after restart and after retries; points awarded per campaign equal the points on
its shipped orders (no double awards); retries add no more log lines than the writes that were
still unacknowledged at the crash.

### Results (2-vCPU Linux container, JDK 21, fsync on, 16 client threads, 8 s of load per run)

| Run | Acknowledged writes | Unacknowledged at crash | Lost | Restart to healthy | Retry log lines (before fix → after) | Double-awarded points |
|---|---|---|---|---|---|---|
| Before fix, 1–3 | 11,565 / 14,678 / 13,583 | 16 / 14 / 16 | 0 | 0.90–0.93 s | ≈ one per retry (run 3: 13,599 retries → 27,187-line log for 13,588 commands) | 0 |
| After fix, 1–3 | 12,864 / 15,677 / 16,864 | 15 / 15 / 9 | 0 | 0.91–0.95 s | 14 / 14 / 8 (only the writes not applied before the crash) | 0 |

### Finding: client retries after a restart doubled the command log

After a crash the in-memory idempotency-key store is empty, so client retries reach the engine.
The engine already treated them as no-ops (same order ID and body, already shipped, same
redemption ID), but `DurableEngine` still appended and fsynced a log line for each one. The drill
showed a full client retry doubling the log, which also doubles the next restart's replay time
and adds one fsync per retry.

Fix (v1.2): `DurableEngine` asks the engine, under the same lock, whether the command is an exact
retry of one that already took effect; if so it returns the current result without logging.
The log format is unchanged and old logs replay as before. Regression test:
`durable: exact retries after a restart are not re-logged` (fails without the fix: 163 log lines
instead of 82).
