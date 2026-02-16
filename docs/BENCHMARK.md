# TicketMaster Benchmark Report

**Date:** 2026-02-17
**Environment:** Single node (MacOS), Docker Compose, 1 Kafka broker, 20 partitions

## Architecture

- Java 25 + Spring Boot 4.0.2 (Virtual Threads)
- Kafka Streams + RocksDB state store
- Avro serialization + Confluent Schema Registry
- HTTP/2 (h2c cleartext)
- DeferredResult long-polling (POST 202 → GET blocks until result)
- 20 partitions per topic, `eventId` as partition key

## Test Suite (Three-Tier)

| Tier | Tool | Purpose |
|------|------|---------|
| Smoke | k6 (1 VU, 20 iter) | Verify basic flow correctness |
| Stress | k6 `ramping-arrival-rate` (3K RPS, 5K VUs) | Sustained throughput under controlled RPS |
| Spike | Go client (1K–10K goroutines) | Flash-crowd burst, peak latency |
| Fire-and-forget | k6 (500 VUs, 100K iter) | Pure POST throughput (no polling) |

## Results

### Test Data

- 100 tickets (A-001 ~ A-100), section A
- 2 seats per reservation → max 50 CONFIRMED

### Smoke Test

```
Tool:       k6 (1 VU, 20 iterations)
CONFIRMED:  20/20
Checks:     42 passed, 0 failed
E2E P95:    235ms
Result:     PASS
```

### Stress Test (ramping-arrival-rate)

```
Tool:       k6 ramping-arrival-rate
Peak RPS:   3,000 target
Actual RPS: 2,427
VUs:        5,000 pre-allocated

Reservations:
  CONFIRMED: 50
  REJECTED:  67,361
  TIMEOUT:   0
  Total:     67,411

POST latency:    P95 = 88ms
GET  latency:    P95 = 4,879ms (DeferredResult long-polling)
E2E  latency:    P95 = 4,887ms

Duplicate seats: 0 (PASS)
```

### Spike Test — 1,000 Concurrent Goroutines

```
Tool:       Go client (4 HTTP client pool, HTTP/2)
Goroutines: 1,000 (all launched simultaneously)
Duration:   0.72s

Reservations:
  CONFIRMED: 50 (expected: 50)
  REJECTED:  950
  ERRORS:    0

POST latency:  P50 = 47ms   P95 = 67ms    P99 = 69ms
GET  latency:  P50 = 429ms  P95 = 437ms   P99 = 438ms
E2E  latency:  P50 = 476ms  P95 = 497ms   P99 = 506ms

Seats allocated: 100/100
Duplicate seats: 0 (PASS)
```

### Spike Test — 10,000 Concurrent Goroutines

```
Tool:       Go client (4 HTTP client pool, HTTP/2)
Goroutines: 10,000 (all launched simultaneously)
Duration:   12.81s

Reservations:
  CONFIRMED: 50 (expected: 50)
  REJECTED:  9,950
  ERRORS:    0

POST latency:  P50 = 810ms   P95 = 3,150ms  P99 = 3,277ms
GET  latency:  P50 = 6,404ms P95 = 8,420ms  P99 = 9,467ms
E2E  latency:  P50 = 8,798ms P95 = 9,566ms  P99 = 11,178ms

Seats allocated: 100/100
Duplicate seats: 0 (PASS)
```

### Fire-and-Forget (POST only, no polling)

```
Tool:       k6 shared-iterations (500 VUs, 100K iterations)
Duration:   7.9s
RPS:        12,658
Accepted:   100,000 (100% HTTP 202)
POST P95:   83ms
```

### Full Polling Test (100K with GET verification)

```
Tool:       k6 shared-iterations (500 VUs, 100K iterations)
Duration:   1m 41s
RPS:        1,972 (bottleneck: 500ms client-side sleep in poll loop)

Reservations:
  CONFIRMED: 50
  REJECTED:  99,950
  TIMEOUT:   0

Duplicate seats: 0 (PASS)
```

## Key Observations

1. **Correctness is absolute** — All tests produce exactly 50 CONFIRMED, 0 duplicate seats, 0 timeouts
2. **POST throughput** — ~12.7K RPS (fire-and-forget) on single node
3. **DeferredResult long-polling works** — No polling loop needed; single GET blocks until result
4. **Bottleneck at scale** — Single eventId = single Kafka partition = single-thread processing. 10K concurrent requests queue up, causing GET P95 to reach 8.4s
5. **100K goroutines exceeds single-node capacity** — Connection refused at TCP level. Reference project uses 32 K8s instances for 1M requests

## How to Run

```bash
# 1. Start application
./gradlew bootRun

# 2. Smoke test
k6 run scripts/perf/smoke-test.js

# 3. Stress test
k6 run scripts/perf/stress-test.js
k6 run -e PEAK_RPS=5000 -e VUS=8000 scripts/perf/stress-test.js

# 4. Go spike test
cd scripts/perf/go-client
go run main.go -host http://localhost:8080 -n 1000 -c 4 -seats 100
go run main.go -host http://localhost:8080 -n 10000 -c 4 -seats 100

# 5. Fire-and-forget (pure POST throughput)
bash scripts/setup-test-data.sh
k6 run -e EVENT_ID=1 scripts/load-test-fire.js

# 6. Full polling test
k6 run -e EVENT_ID=1 scripts/load-test-100k.js
```
