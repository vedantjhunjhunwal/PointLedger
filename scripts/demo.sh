#!/usr/bin/env bash
# Seeds a running PointLedger (default http://localhost:8080) with demo campaigns and orders.
set -euo pipefail
B=${1:-http://localhost:8080}
post() { curl -s -X POST "$B$1" -H 'Content-Type: application/json' -H "Idempotency-Key: $2" -d "$3"; echo; }
post /api/campaigns c-spring15 '{"id":"spring-15","sellerId":"seller-a","rateBp":1500,"group":"spring","perOrderCap":10000,"budget":200000}'
post /api/campaigns c-spring10 '{"id":"spring-10","sellerId":"seller-a","rateBp":1000,"group":"spring","perOrderCap":10000,"budget":200000}'
post /api/campaigns c-bonus8   '{"id":"bonus-8","sellerId":"seller-a","rateBp":800,"perOrderCap":10000,"budget":50000}'
post /api/campaigns c-kitchen5 '{"id":"kitchen-5","sellerId":"seller-b","rateBp":500,"perOrderCap":300,"budget":20000}'
for i in $(seq 1 12); do
  post /api/orders "o$i" "{\"orderId\":\"o$i\",\"customerId\":\"user-$((i % 3 + 1))\",\"lines\":[{\"sku\":\"pan\",\"sellerId\":\"seller-a\",\"unitPriceInclTax\":$((5500 * i)),\"qty\":1,\"taxRatePct\":10},{\"sku\":\"rice\",\"sellerId\":\"seller-b\",\"unitPriceInclTax\":2160,\"qty\":2,\"taxRatePct\":8}]}" >/dev/null
  if (( i % 4 == 0 )); then post /api/orders/o$i/cancel "x$i" '{}' >/dev/null; else post /api/orders/o$i/ship "s$i" '{}' >/dev/null; fi
done
post /api/customers/user-1/redeem r1 '{"points":500,"redemptionId":"r1"}' >/dev/null
curl -s "$B/api/audit"; echo
