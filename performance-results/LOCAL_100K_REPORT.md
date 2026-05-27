# Local 100K Stress Test — Phase A+B+C Validation

**Run date**: 2026-05-27 13:10–13:22 (12 min)
**Commit**: `458d9b1` (Phases A+B+C) + SectionInitProcessor defensive fix
**Environment**: macOS, 8 cores / 16 GB, single JVM, 3-broker local Kafka

## Command

```bash
k6 run scripts/perf/booking-stress.js \
  -e BASE_URL=http://localhost:8080 \
  -e VUS=100 -e ITERATIONS=100000 \
  -e SECTIONS=A,B,C,D,E,F,G,H,I,J \
  -e ROWS=40 -e SEATS_PER_ROW=50 \
  -e SUB_PARTITIONS=1
```

10 sections × 40 × 50 = **20,000 seats** vs **100,000 requests** ⇒ after sold-out, the rest get `REJECTED` but still exercise the full pipeline.

## Result

| Metric | Value | Notes |
|---|---|---|
| Iterations completed | **100,000** | 0 interrupted |
| Success rate | **99.99 %** | 2 network errors, 0 timeouts |
| Total duration | **12 min 19 s** | |
| Throughput (booking/s) | **135.16** | bottlenecked by VU count × long-poll wait |
| HTTP RPS | 270 | 100K POST + 100K GET |
| CONFIRMED | 11,754 | ~59 % seat utilization before contiguous blocks ran out |
| REJECTED | 88,244 | full pipeline still exercised |
| Timeouts | 0 | |
| Errors | 2 | |
| **POST p95** | **6 ms** | fire-and-forget into Kafka (Phase B paid off) |
| POST median | 1 ms | |
| GET (long-poll) p95 | 1.11 s | dominates E2E latency |
| **E2E p95** | **1.12 s** | |
| E2E median | 672 ms | |
| Data sent / received | 32 MB / 68 MB | |

## What it validates

- ✅ Pipeline still correct after Phase A (PG ↛ Kafka KTable) and Phase B (no Redis pre-filter / cache): **0 duplicate seats, 99.99 % success, 0 timeouts**
- ✅ Phase B removed Redis from POST path → **median POST = 1 ms** (round-trip to local Kafka)
- ✅ KTable / `BookingPendingRequests` foreach wake-up works end-to-end at 100 VU concurrency

## Throughput interpretation

135 booking/s on a single JVM is **VU-bound, not capacity-bound**:

- 100 VUs × ~700 ms per iteration ⇒ ~143 RPS theoretical ceiling
- The bottleneck is the long-poll GET (waits for KTable to materialize)
- POST alone could sustain ~5,000 RPS on this hardware (1 ms median)

To unlock more throughput on the same JVM:
- Bump VUs (1000+) — single 8-core machine will run out of memory / CPU before reaching 50K
- Cleaner: deploy the 32-instance overlay on GKE — see `scripts/perf/RUN_32_INSTANCE.md`

## Comparison

| Setup | Booking/s | E2E p95 | Note |
|---|---|---|---|
| **Local single JVM** (this run) | **135** | 1.12 s | VU-bound |
| ticket-master 32-instance-perf-v.0.0.23 (published) | 84,745 | 735 ms | 32 × 6 CPU / 12 Gi |
| 50K QPS target | 50,000 | < 2 s | needs Phase C on GKE |

## Side effect: SectionInitProcessor hardening

`SectionInitProcessor.extractSubPartition` previously crashed the stream
client on any malformed key (e.g. legacy `"<eventId>-VIP"` 2-part keys
left over from dev experiments). The processor now skips such records
with a `WARN` log and keeps the stream client alive. Without this fix
the perf run could not start because the source topic had ~50 poisoned
records from earlier BDD experiments.

## Next steps

- Push branch and open PR for review
- For the real 50K validation, follow `scripts/perf/RUN_32_INSTANCE.md`
  on a GKE cluster with Confluent Cloud Kafka (32 partitions)
