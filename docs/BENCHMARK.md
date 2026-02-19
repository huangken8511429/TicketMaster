# TicketMaster Benchmark Report

**Date:** 2026-02-19
**Environment:** Single node (MacOS), Docker Compose, 1 Kafka broker, 20 partitions

## Architecture

- Java 25 + Spring Boot 4.0.2 (Virtual Threads)
- Kafka Streams + RocksDB state store (4 stream threads)
- Avro serialization + Confluent Schema Registry
- HTTP/2 (h2c cleartext)
- DeferredResult long-polling (POST 202 → GET blocks until result)
- 20 partitions per topic
- Partition key strategy:
  - `reservationId` — reservation-commands, reservation-results, reservation-completed (distributed across all partitions)
  - `eventId-section` — seat-events, reservation-requests (co-partitioned for seat allocation consistency)

## Kafka Streams Tuning

```properties
num.stream.threads=4
producer.linger.ms=5
commit.interval.ms=30
```

## Test Suite (Three-Tier)

| Tier | Tool | Purpose |
|------|------|---------|
| Smoke | k6 (1 VU, 20 iter) | Verify basic flow correctness |
| Stress | k6 `ramping-arrival-rate` (3K RPS, 5K VUs) | Sustained throughput under controlled RPS |
| Spike | Go client (1K–10K goroutines) | Flash-crowd burst, peak latency |

## Results

### Test Data

- 100 tickets (A-001 ~ A-100), section A
- 2 seats per reservation → max 50 CONFIRMED

### Smoke Test

```
Tool:       k6 (1 VU, 20 iterations)
CONFIRMED:  20/20
Checks:     42 passed, 0 failed
E2E P95:    39ms
Result:     PASS
```

### Stress Test (ramping-arrival-rate)

```
Tool:       k6 ramping-arrival-rate
Peak RPS:   3,000 target
Actual RPS: 4,766
VUs:        5,000 pre-allocated

Reservations:
  CONFIRMED: 50
  REJECTED:  126,711
  TIMEOUT:   0
  Total:     126,761

POST latency:    P95 = 963ms
GET  latency:    P95 = 1,071ms (DeferredResult long-polling)
E2E  latency:    P95 = 1,774ms

Duplicate seats: 0 (PASS)
```

### Spike Test — 1,000 Concurrent Goroutines

```
Tool:       Go client (4 HTTP client pool, HTTP/2)
Goroutines: 1,000 (all launched simultaneously)
Duration:   0.77s

Reservations:
  CONFIRMED: 50 (expected: 50)
  REJECTED:  950
  TIMEOUT:   0
  ERRORS:    0
  Total:     1,000

POST latency:  P50 = 131ms  P95 = 231ms   P99 = 315ms
GET  latency:  P50 = 389ms  P95 = 433ms   P99 = 447ms
E2E  latency:  P50 = 538ms  P95 = 642ms   P99 = 652ms

Seats allocated: 100/100
Duplicate seats: 0 (PASS)
```

### Spike Test — 10,000 Concurrent Goroutines

```
Tool:       Go client (4 HTTP client pool, HTTP/2)
Goroutines: 10,000 (all launched simultaneously)
Duration:   13.80s

Reservations:
  CONFIRMED: 50 (expected: 50)
  REJECTED:  9,950
  TIMEOUT:   0
  ERRORS:    0
  Total:     10,000

POST latency:  P50 = 1,141ms  P95 = 4,154ms  P99 = 5,236ms
GET  latency:  P50 = 3,461ms  P95 = 5,489ms  P99 = 6,664ms
E2E  latency:  P50 = 5,194ms  P95 = 7,212ms  P99 = 8,685ms

Seats allocated: 100/100
Duplicate seats: 0 (PASS)
```

## Key Observations

1. **Correctness is absolute** — All tests produce exactly 50 CONFIRMED, 100/100 seats allocated, 0 duplicate seats, 0 timeouts, 0 errors
2. **Stress throughput exceeds target** — Actual RPS 4,766 surpasses the 3K RPS target, with E2E P95 = 1.8s
3. **DeferredResult long-polling works** — No polling loop needed; single GET blocks until result. Early-arriving results are cached to eliminate race conditions
4. **Spike burst latency** — 1K goroutines: E2E P95 = 642ms (0.77s total). 10K goroutines: E2E P95 = 7.2s (13.8s total)
5. **Partition key trade-off** — Using `reservationId` as key distributes load across 20 partitions for most hops, but seat allocation MUST be single-partition per event-section for consistency (no duplicate bookings). This is the fundamental architectural constraint.

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
```
