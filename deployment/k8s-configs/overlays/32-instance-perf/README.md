# 32-instance perf overlay

Mirrors ticket-master's [32-instance-perf-v.0.0.23][ref] sizing — the
configuration that achieved **84,745 RPS / p95 735 ms** processing 1M
reservations in 11.8 s.

[ref]: https://github.com/tall15421542-lab/ticket-master/tree/main/deployment/k8s-configs/overlays/32-instance-perf-v.0.0.23

## What it does

| Component                | Replicas | CPU req → limit | Memory | JVM heap |
| ------------------------ | -------- | --------------- | ------ | -------- |
| ticketmaster-api         | **32**   | 4000m → 6000m   | 12 Gi  | -Xmx11g  |
| reservation-processor    | **32**   | 1000m → 2000m   | 2 Gi   | -Xmx1500m |
| seat-processor           | **32**   | 2000m → 2000m   | 4 Gi   | -Xmx3g   |

Plus:

- **HPA on api pinned to min=max=32** — disables autoscaling for predictable
  perf baselines.
- **`num.stream.threads=1`** via `application-cloud.properties` override —
  32 replicas × 1 thread = 32 active stream tasks (1:1 partition ownership).
  The base default (16, for local dev) would spawn 32 × 16 idle standby
  tasks per app-id otherwise.

## Apply

```bash
kubectl apply -k deployment/k8s-configs/overlays/32-instance-perf/
```

## Validate before applying

```bash
kubectl kustomize deployment/k8s-configs/overlays/32-instance-perf/ | less
```

## Tear down

```bash
kubectl delete -k deployment/k8s-configs/overlays/32-instance-perf/
```

## Notes

- This overlay assumes Kafka topics already exist with **32 partitions**
  (matches the base topology configuration `ticketmaster.kafka.partitions=32`).
  In Confluent Cloud, create topics with the matching partition count
  before applying.
- The api pods still depend on the Cloud SQL Auth Proxy sidecar for
  admin metadata (Venue / Performer / Event CRUD). Phase A removed PG
  from the booking hot path, not the admin path.
- For higher throughput beyond ~85K RPS, scale Kafka brokers (Confluent
  Cloud type → Standard/Dedicated) before adding more replicas.
