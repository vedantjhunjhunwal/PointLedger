#!/usr/bin/env python3
"""Crash drill: kill -9 PointLedger under concurrent load, restart it, and check that
nothing acknowledged was lost and client retries cause no double awards.

    ./build.sh && python3 scripts/crash_drill.py --runs 3

Each run:
  1. starts the service with fsync on and a fresh command log, creates campaigns;
  2. N client threads place orders, then ship or cancel them, each with an Idempotency-Key;
  3. after --kill-after seconds the JVM is killed with SIGKILL (no shutdown hook runs);
  4. the service is restarted on the same log and the restart time is measured;
  5. every acknowledged write is checked, then every attempted request is retried with the
     same key and body, as a client would after a timeout (the in-memory idempotency store is
     gone after a crash, so retries reach the engine, which must answer them without re-logging);
  6. invariants: /api/audit ok, and for every campaign, points awarded == the sum of points
     on its SHIPPED orders (a double award would break this).
Only the Python standard library is used.
"""
import argparse
import json
import os
import random
import re
import signal
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CAMPAIGNS = [
    {"id": "spring-15", "sellerId": "seller-a", "rateBp": 1500, "group": "spring", "perOrderCap": 800, "budget": 50_000_000},
    {"id": "spring-10", "sellerId": "seller-a", "rateBp": 1000, "group": "spring", "perOrderCap": 800, "budget": 50_000_000},
    {"id": "bonus-8", "sellerId": "seller-a", "rateBp": 800, "perOrderCap": 400, "budget": 50_000_000},
    {"id": "kitchen-5", "sellerId": "seller-b", "rateBp": 500, "perOrderCap": 300, "budget": 50_000_000},
]


def call(base, method, path, body=None, key=None, timeout=5):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(base + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if key:
        req.add_header("Idempotency-Key", key)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, json.loads(r.read() or b"null")
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"null")


def order_body(i, rnd):
    return {"orderId": f"o{i}", "customerId": f"user-{i % 50}", "lines": [
        {"sku": "pan", "sellerId": "seller-a", "unitPriceInclTax": 1100 * (1 + rnd.randrange(40)), "qty": 1, "taxRatePct": 10},
        {"sku": "rice", "sellerId": "seller-b", "unitPriceInclTax": 2160, "qty": 1 + rnd.randrange(3), "taxRatePct": 8},
    ]}


def start(port, log):
    env = dict(os.environ, PORT=str(port), COMMAND_LOG=log, FSYNC="true", STATIC_DIR="/nonexistent")
    cp = os.pathsep.join([os.path.join(ROOT, "out", "main"), os.path.join(ROOT, "lib", "*")])
    proc = subprocess.Popen(["java", "-cp", cp, "dev.pointledger.App"], env=env, cwd=ROOT,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    t0 = time.monotonic()
    line = ""
    while True:  # the startup line is printed once the port is open
        line = proc.stdout.readline()
        if not line:
            raise RuntimeError("service exited during startup")
        if line.startswith("PointLedger on"):
            break
    base = f"http://127.0.0.1:{port}"
    while call(base, "GET", "/health")[0] != 200:
        time.sleep(0.01)
    return proc, base, time.monotonic() - t0, line.strip()


def run_once(run, args):
    log = os.path.join(tempfile.mkdtemp(prefix="pl-drill-"), "commands.log")
    proc, base, _, _ = start(args.port, log)
    for c in CAMPAIGNS:
        assert call(base, "POST", "/api/campaigns", c, key="camp-" + c["id"])[0] == 201

    acked, attempted, lock = {}, {}, threading.Lock()   # op key -> (method, path, body)
    stop = threading.Event()
    counter = iter(range(10**9))

    def worker(seed):
        rnd = random.Random(seed)
        while not stop.is_set():
            with lock:
                i = next(counter)
            ops = [("POST", "/api/orders", order_body(i, rnd), f"place-o{i}")]
            follow = "cancel" if i % 4 == 0 else "ship"
            ops.append(("POST", f"/api/orders/o{i}/{follow}", {}, f"{follow}-o{i}"))
            for method, path, body, key in ops:
                with lock:
                    attempted[key] = (method, path, body)
                try:
                    status, _ = call(base, method, path, body, key=key)
                except Exception:
                    return  # server gone
                if status in (200, 201):
                    with lock:
                        acked[key] = (method, path, body)
                else:
                    return

    threads = [threading.Thread(target=worker, args=(run * 1000 + k,)) for k in range(args.threads)]
    for t in threads:
        t.start()
    time.sleep(args.kill_after)
    os.kill(proc.pid, signal.SIGKILL)          # hard crash: no shutdown hook, no flush
    stop.set()
    for t in threads:
        t.join()
    proc.wait()

    proc, base, restart_s, startup = start(args.port, log)
    try:
        replayed = int(re.search(r"replayed (\d+) commands", startup).group(1))
        audit_ok_on_start = "audit ok=true" in startup

        # 1) nothing acknowledged was lost
        lost = 0
        for key, (method, path, body) in acked.items():
            oid = body["orderId"] if key.startswith("place-") else path.split("/")[3]
            status, view = call(base, "GET", f"/api/orders/{oid}")
            want = {"place": None, "ship": "SHIPPED", "cancel": "CANCELLED"}[key.split("-")[0]]
            if status != 200 or (want and view["status"] != want):
                lost += 1

        # 2) clients retry everything they attempted (same key, same body)
        lines_before_retries = sum(1 for _ in open(log))
        retry_errors = 0
        for key, (method, path, body) in sorted(attempted.items(), key=lambda kv: not kv[0].startswith("place-")):
            status, _ = call(base, method, path, body, key=key)
            retry_errors += status not in (200, 201)

        # 3) invariants: audit, and awarded points == points on shipped orders, per campaign
        log_lines_added_by_retries = sum(1 for _ in open(log)) - lines_before_retries
        status, audit = call(base, "GET", "/api/audit")
        _, campaigns = call(base, "GET", "/api/campaigns")
        awarded = {c["campaign"]["id"]: c["awarded"] for c in campaigns}
        expected = {cid: 0 for cid in awarded}
        orders = {body["orderId"] for k, (_, _, body) in attempted.items() if k.startswith("place-")}
        for oid in orders:
            s, view = call(base, "GET", f"/api/orders/{oid}")
            if s == 200 and view["status"] == "SHIPPED":
                for a in view["awards"]:
                    expected[a["campaignId"]] += a["points"]
        double_award_points = sum(awarded[c] - expected[c] for c in awarded)
        return {
            "acknowledged_writes": len(acked), "attempted_writes": len(attempted),
            "unacknowledged_at_crash": len(attempted) - len(acked),
            "commands_replayed": replayed, "restart_to_healthy_s": round(restart_s, 2),
            "audit_ok_on_restart": audit_ok_on_start, "lost_acknowledged_writes": lost,
            "retry_errors": retry_errors, "log_lines_added_by_retries": log_lines_added_by_retries,
            "audit_ok_after_retries": audit.get("ok") is True,
            "awarded_minus_shipped_points": double_award_points,
        }
    finally:
        proc.kill()
        proc.wait()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--runs", type=int, default=3)
    ap.add_argument("--threads", type=int, default=16)
    ap.add_argument("--kill-after", type=float, default=8.0, help="seconds of load before SIGKILL")
    ap.add_argument("--port", type=int, default=18181)
    args = ap.parse_args()
    results = []
    for r in range(args.runs):
        res = run_once(r, args)
        results.append(res)
        print(json.dumps({"run": r + 1, **res}), flush=True)
    ok = all(x["lost_acknowledged_writes"] == 0 and x["retry_errors"] == 0 and x["audit_ok_after_retries"]
             and x["audit_ok_on_restart"] and x["awarded_minus_shipped_points"] == 0
             and x["log_lines_added_by_retries"] <= x["unacknowledged_at_crash"] for x in results)
    print("DRILL", "PASSED" if ok else "FAILED")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
