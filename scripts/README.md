# Load Test Scripts

Local high-concurrency load testing for the TicketMaster reservation system.

## Prerequisites

- Application running (`./gradlew bootRun`)
- [jq](https://jqlang.github.io/jq/) — `brew install jq`
- [k6](https://k6.io/) — `brew install k6`

## Quick Start

```bash
# 1. Start the application (auto-starts Docker Compose services)
./gradlew bootRun

# 2. Initialize test data (creates venue, event, 100 tickets)
bash scripts/setup-test-data.sh

# 3. Run load test (use the EVENT_ID printed by setup script)
k6 run -e EVENT_ID=<event_id> scripts/load-test.js

# 4. (Optional) View metrics in Grafana
open http://localhost:3000
```

## What It Tests

- **50 virtual users** simultaneously compete for tickets
- Each user requests **2 consecutive seats** in section A
- 100 tickets / 2 per request = **max 50 successful reservations**
- Verifies:
  - No duplicate seat assignments
  - CONFIRMED + REJECTED = total requests
  - P95/P99 polling latency

## Configuration

### setup-test-data.sh

| Variable      | Default                   | Description                          |
|---------------|---------------------------|--------------------------------------|
| `BASE_URL`    | `http://localhost:8080`   | Application base URL                 |
| `TICKET_COUNT`| `100`                     | Number of tickets to create          |
| `PRICE`       | `2800`                    | Price per ticket                     |
| `KAFKA_WAIT`  | `3`                       | Seconds to wait for Kafka Streams    |

### load-test.js

| Variable    | Default                 | Description              |
|-------------|-------------------------|--------------------------|
| `BASE_URL`  | `http://localhost:8080` | Application base URL     |
| `EVENT_ID`  | (required)              | Event ID from setup      |

Override with `-e`: `k6 run -e EVENT_ID=1 -e BASE_URL=http://localhost:9090 scripts/load-test.js`
