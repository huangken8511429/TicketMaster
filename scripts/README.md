# Performance Testing Scripts

Three-tier test suite for TicketMaster: **smoke** (sanity) -> **stress** (sustained load) -> **spike** (burst).

## Prerequisites

- [k6](https://k6.io/docs/get-started/installation/) for smoke and stress tests
- [Go 1.24+](https://go.dev/dl/) for the spike test client
- A running TicketMaster instance (`./gradlew bootRun`)

## Test Types

### 1. Smoke Test (k6)

Verifies the system is alive and the reservation flow works end-to-end.

- **1 VU, 20 iterations**
- Creates a venue + event with 20 sections (400 seats each)
- Each iteration: random section, random 1-4 seats

```bash
k6 run scripts/perf/k6/smoke.js
```

### 2. Stress Test (k6)

Sustained high-throughput using `ramping-arrival-rate` (fixed RPS, independent of response time).

- **Stages**: warm-up (50%) -> ramp (100%) -> sustain (100%) -> cool-down (50%)
- **Thresholds**: P95 POST < 500ms, P95 GET < 5s, checks > 99%

```bash
# Default: 3000 peak RPS
k6 run scripts/perf/k6/stress.js

# Custom peak RPS
k6 run scripts/perf/k6/stress.js -e PEAK_RPS=1000
```

### 3. Spike Test (Go client)

Simulates a ticket-rush burst — all requests fired concurrently. Validates **zero duplicate seats** (data integrity).

```bash
cd scripts/perf/go-client
go build .

# 20 concurrent requests, 20 sections (default)
./go-client

# 5000 concurrent requests, 20 sections, 4 HTTP clients, HTTP/2
./go-client --host localhost:8080 -n 5000 -a 20 -c 4 --http2
```

**Flags:**

| Flag | Default | Description |
|------|---------|-------------|
| `--host` | `localhost:8080` | Target host:port |
| `-n` | `20` | Number of concurrent requests |
| `-a` | `20` | Number of sections |
| `-c` | `1` | Number of HTTP clients |
| `-t` | `0s` | Sleep between POST and GET |
| `-e` | `dev` | Environment (dev/prod) |
| `--http2` | `false` | Enable HTTP/2 cleartext |

## Environment Variables (k6)

| Variable | Default | Description |
|----------|---------|-------------|
| `HOST_PORT` | `localhost:8080` | Target host:port |
| `NUM_SECTIONS` | `20` | Sections per event (400 seats each) |
| `PEAK_RPS` | `3000` | Peak requests/sec (stress test only) |

## K8s Workflow

```bash
# 1. Set up test data
BASE_URL=http://api.ticketmaster.local:8080 NUM_SECTIONS=20 ./scripts/setup-test-data.sh

# 2. Run smoke test
k6 run -e HOST_PORT=api.ticketmaster.local:8080 scripts/perf/k6/smoke.js

# 3. Run stress test
k6 run -e HOST_PORT=api.ticketmaster.local:8080 -e PEAK_RPS=5000 scripts/perf/k6/stress.js

# 4. Run spike test
cd scripts/perf/go-client
./go-client --host api.ticketmaster.local:8080 -n 10000 -a 20 -c 8 --http2
```

## Reading Results

### k6 Output

- `http_req_duration`: Overall request latency (P50/P95/P99)
- `reservation_time`: Full POST+GET reservation cycle time
- `reservation_completed`: Total successful reservations
- `checks`: Percentage of passed assertions

### Go Client Output

- **P50/P95/P99**: Processing time (server) and end-to-end latency
- **Successful/Failed reservations**: Count by outcome
- **Duplicate seats**: Must be **0** (data integrity check)
- **Per-section breakdown**: Seats reserved per section

## Directory Structure

```
scripts/
├── perf/
│   ├── k6/
│   │   ├── lib/
│   │   │   ├── config.js        # Shared configuration
│   │   │   ├── setup.js         # Venue + event creation
│   │   │   └── reserve.js       # Reservation flow (POST + GET)
│   │   ├── smoke.js             # Smoke test (1 VU, 20 iters)
│   │   └── stress.js            # Stress test (ramping-arrival-rate)
│   └── go-client/
│       ├── go.mod
│       ├── go.sum
│       └── main.go              # Spike test (Go HTTP/2 burst)
├── setup-test-data.sh           # K8s data initialization
└── README.md                    # This file
```
