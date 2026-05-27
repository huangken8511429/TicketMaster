# 32-instance perf run playbook

Step-by-step recipe for running the booking stress test against the
`32-instance-perf` overlay on GKE. Validates whether the three-phase
refactor (PG removed from hot path, Redis removed, ticket-master sizing)
gets us close to ticket-master's published **84,745 RPS / p95 735 ms**.

## Pre-flight checklist

| Item | Verify with | Expected |
|------|------------|----------|
| GCP project + APIs enabled | `make enable-apis` | already done |
| Terraform infra applied | `kubectl get nodes` | n2 nodepool, ≥32 cores total |
| Confluent Cloud topics created with **32 partitions** | confluent CLI | `booking-commands`, `booking-completed`, `seat-allocation-{requests,results}`, `section-{init,status}`, `ticket-{commands,state}` |
| GKE credentials | `kubectl config current-context` | points at `gke_<PROJECT>_<REGION>_<CLUSTER>` |
| Image tag for this run | `git rev-parse --short HEAD` | e.g. `ae14509` |
| k6 ≥ v0.50 on driver host | `k6 version` | |

If any row above is unchecked, fix that first — running on a half-provisioned cluster wastes hours.

## Step 1. Build & push the image

```bash
export PROJECT_ID=<your-project>
export TAG=$(git rev-parse --short HEAD)        # ae14509

make backend-image TAG=$TAG
```

Wait for Cloud Build to finish (~5 min). Verify:

```bash
gcloud artifacts docker images list \
  asia-east1-docker.pkg.dev/$PROJECT_ID/ticket-master/ticketmaster \
  --format='value(IMAGE,TAGS)' --filter="tags:$TAG"
```

## Step 2. Apply the 32-instance overlay

Pin the image tag in the overlay before applying (kustomize image transform):

```bash
cd deployment/k8s-configs/overlays/32-instance-perf
kustomize edit set image \
  asia-east1-docker.pkg.dev/PROJECT_ID/ticket-master/ticketmaster=asia-east1-docker.pkg.dev/$PROJECT_ID/ticket-master/ticketmaster:$TAG
cd -

# Render once to eyeball before apply:
kubectl kustomize deployment/k8s-configs/overlays/32-instance-perf/ | less

# Apply:
make k8s-deploy ENV=32-instance-perf
```

Or skip the Makefile:

```bash
kubectl apply -k deployment/k8s-configs/overlays/32-instance-perf/
```

## Step 3. Wait for 96 pods (32 × 3 services) to be Ready

```bash
kubectl rollout status deployment/ticketmaster-api --timeout=10m
kubectl rollout status deployment/ticketmaster-reservation-processor --timeout=10m
kubectl rollout status deployment/ticketmaster-seat-processor --timeout=10m
```

Sanity check pod counts:

```bash
kubectl get deploy -l 'app in (ticketmaster-api,ticketmaster-reservation-processor,ticketmaster-seat-processor)' \
  -o custom-columns=NAME:.metadata.name,DESIRED:.spec.replicas,READY:.status.readyReplicas
```

All three should show **DESIRED=32 READY=32**.

Confirm Kafka Streams reached RUNNING state (no rebalance loops):

```bash
kubectl logs -l app=ticketmaster-api --tail=200 \
  | grep -E 'State transition|StreamThread' | tail -20
```

## Step 4. Get the gateway IP

```bash
export TM_HOST=$(kubectl get gateway tm-external-http \
  -o jsonpath='{.status.addresses[0].value}')
echo "Gateway: http://$TM_HOST"
```

Smoke test:

```bash
curl -s -o /dev/null -w '%{http_code}\n' http://$TM_HOST/actuator/health
# expect 200
```

## Step 5. Seed test data

Stress test's `setup()` already creates a venue + event + sections via admin API.
The Redis layer is gone, so it just produces SectionInitCommand → SeatAllocationProcessor.

For a 1M reservation run we want 40 sections × 25 seats × 1000 rows ≈ 1M seats:

```bash
# Variables for the run
export VUS=2000              # virtual users
export ITERATIONS=1000000    # total bookings
export SECTIONS=$(seq -s, 1 40 | sed 's/[0-9]*/&-A/g' | tr ',' ',')
export ROWS=25
export SEATS_PER_ROW=25
export SUB_PARTITIONS=1
```

Actually the existing `booking-stress.js` defaults are fine for a 1-section
sanity warm-up. Pick a profile:

| Profile          | VUS  | ITERATIONS | SECTIONS    | ROWS | SEATS_PER_ROW | TOTAL_SEATS |
|------------------|------|-----------:|-------------|-----:|--------------:|------------:|
| smoke            | 50   | 1,000      | A,B,C,D,E   | 20   | 25            | 2,500       |
| warm-up          | 200  | 10,000     | A,B,C,D,E   | 40   | 50            | 10,000      |
| sustained 5K RPS | 500  | 100,000    | 10 sections | 40   | 50            | 20,000      |
| **1M reservations** | **2000** | **1,000,000** | **40 sections** | **40** | **50** | **80,000** |

## Step 6. Run the stress test

```bash
# Warm-up (~5 min)
k6 run scripts/perf/booking-stress.js \
  -e BASE_URL=http://$TM_HOST \
  -e VUS=200 -e ITERATIONS=10000 \
  -e SECTIONS=A,B,C,D,E -e ROWS=40 -e SEATS_PER_ROW=50

# Real run — 1M reservations matching ticket-master's published number
k6 run scripts/perf/booking-stress.js \
  -e BASE_URL=http://$TM_HOST \
  -e VUS=2000 -e ITERATIONS=1000000 \
  -e SECTIONS=$(seq -s, 1 40 | sed 's/[0-9]*/&-S/g') \
  -e ROWS=40 -e SEATS_PER_ROW=50 \
  --summary-export=results-1m-$TAG.json \
  --out json=raw-1m-$TAG.json
```

## Step 7. Pass / fail criteria

| Metric                              | Target | ticket-master baseline |
|-------------------------------------|--------|------------------------|
| `booking_success_rate`              | > 0.95 | 0.99                   |
| `e2e_duration` p95                  | < 2 s  | 735 ms                 |
| `e2e_duration` p99                  | < 3 s  | 1.961 s                |
| `iterations/s` (effective RPS)      | > 50,000 | 84,745               |
| Duplicate seats (check via admin)   | 0      | 0                      |

## Step 8. Collect server-side traces

```bash
# OTEL/Tempo dashboard (forward to localhost):
gcloud beta run services proxy grafana --region=$REGION --port=3000

# Open http://localhost:3000 → Tempo → search for:
# - POST /api/bookings span rate / p95
# - GET /api/bookings/{id} span rate / p95
# - Kafka Streams `seat-allocation-requests` consume lag
# - Kafka producer publish latency
```

## Step 9. Tear down

```bash
make k8s-destroy ENV=32-instance-perf
```

The 32-replica overlay burns roughly:

| Service | vCPU·hr | Mem GiB·hr |
|---------|--------:|-----------:|
| api 32 × 6c × 12Gi | 192 | 384 |
| reservation 32 × 2c × 2Gi | 64 | 64 |
| seat 32 × 2c × 4Gi | 64 | 128 |
| **Total per hour** | **320** | **576** |

At GKE Autopilot pricing (≈ $0.06 vCPU·hr + $0.007 GiB·hr) → **~$25/hr**.
Don't forget to teardown after the run.

## Troubleshooting

- **`READY < DESIRED` stuck at startup**: usually Confluent Cloud topic
  partition mismatch. Run `confluent kafka topic describe booking-commands`
  and verify 32 partitions.
- **All requests return 503**: api → Cloud SQL Auth Proxy sidecar may not
  be ready; check `kubectl logs <api-pod> -c cloud-sql-proxy`.
- **`booking_timeout` spikes**: check `seat-processor` consumer lag —
  may need to increase Confluent Cloud throughput tier (Basic → Standard).
- **`StreamsException: shutdown_client`** in api/processor logs: usually
  a Kafka Streams uncaught exception. Look 50 lines above for `Caused by`.

## Reference

- ticket-master 32-instance-perf-v.0.0.23 results:
  https://github.com/tall15421542-lab/ticket-master/tree/main/deployment/k8s-configs/overlays/32-instance-perf-v.0.0.23
- Our overlay: `deployment/k8s-configs/overlays/32-instance-perf/`
