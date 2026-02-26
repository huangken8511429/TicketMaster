# TicketMaster

A high-concurrency ticket booking system built with **Spring Boot** and **Kafka Streams**, designed to handle massive concurrent reservations with zero duplicate bookings.

## Motivation

This project is inspired by [ticket-master](https://github.com/tall15421542-lab/ticket-master) by MING HUNG, which demonstrates a dataflow architecture capable of processing 1,000,000 reservations within 12 seconds using raw Java + Jetty + Kafka Streams.

I wanted to rebuild this system using the **Spring Boot ecosystem** to:

- **Learn Event Streaming in depth** — understand how Kafka Streams enables stateful stream processing, exactly-once semantics, and horizontal scalability in a real-world ticket booking scenario.
- **Study high-concurrency patterns** — explore how to avoid pessimistic locks (blocking) and optimistic locks (excessive retries) using a data-flow architecture with Write-Ahead Log (WAL) for event replay.
- **Bridge theory and practice** — apply concepts from *Designing Data-Intensive Applications* in a concrete Spring Boot application with production-grade infrastructure.

## Key Differences from the Original

| Aspect | [ticket-master](https://github.com/tall15421542-lab/ticket-master) | This Project |
|--------|-------------------------------------------------------------------|--------------|
| Framework | Raw Java + Jetty | Spring Boot 4.0.2 (WebMVC) |
| Java Version | Java 24 | Java 25 with Virtual Threads |
| Build Tool | Maven | Gradle 9.3.0 |
| State Store | RocksDB (Kafka Streams) | RocksDB (Kafka Streams) + Redis cache |
| Serialization | Avro + Schema Registry | Spring-managed JSON |
| Observability | Google Cloud Trace | Grafana LGTM (Loki, Grafana, Tempo, Mimir) |
| API Docs | — | Spring REST Docs (MockMvc) |
| Local Dev | Docker Compose + manual service start | Spring Boot Docker Compose (auto-start) |
| Testing | JUnit + Maven | BDD workflow with JUnit 5 + MockMvc |

## Architecture

The system adopts a **Dataflow Architecture**, separating concerns into three runtime roles that communicate through Kafka topics:

```
                          ┌──────────────────────┐
                          │     API Service       │
  HTTP Request ──────────►│  (Spring WebMVC +     │
                          │   Virtual Threads)    │
                          └──────────┬───────────┘
                                     │ Kafka produce
                                     ▼
                     ┌───────────────────────────────┐
                     │        Kafka Cluster           │
                     │                                │
                     │  reservation-commands           │
                     │  reservation-requests           │
                     │  reservation-completed          │
                     │  section-init / section-status  │
                     └──────┬────────────────┬────────┘
                            │                │
                            ▼                ▼
               ┌────────────────┐  ┌─────────────────┐
               │  Reservation   │  │  Seat            │
               │  Processor     │  │  Processor       │
               │  (Kafka        │  │  (Kafka          │
               │   Streams)     │  │   Streams)       │
               └────────┬───────┘  └────────┬─────────┘
                        │                   │
                        ▼                   ▼
                   ┌──────────┐       ┌──────────┐
                   │PostgreSQL│       │  Redis    │
                   │          │       │  (Cache)  │
                   └──────────┘       └──────────┘
```

### Data Flow

1. **reservation-commands** → `ReservationCommandProcessor` → **reservation-requests**
2. **reservation-requests** → `SeatAllocationProcessor` → **reservation-completed**
3. **section-init** → `SectionInitProcessor` → **section-status**

### Why Dataflow?

Traditional approaches to concurrent ticket booking face fundamental trade-offs:

- **Pessimistic locking** — blocks threads, limits throughput
- **Optimistic locking** — causes excessive retries under high contention

The dataflow approach uses Kafka's partitioning to serialize writes per section, eliminating lock contention entirely while maintaining horizontal scalability.

## Tech Stack

### Core

| Technology | Version | Purpose |
|-----------|---------|---------|
| **Java** | 25 | Language runtime with Virtual Threads for lightweight concurrency |
| **Spring Boot** | 4.0.2 | Application framework (WebMVC) |
| **Kafka Streams** | — | Stateful stream processing for reservation workflow |
| **PostgreSQL** | — | Primary data store for events, venues, tickets, and reservations |
| **Redis** | — | Caching layer for high-availability ticket reads |

### Build & Testing

| Technology | Purpose |
|-----------|---------|
| **Gradle** 9.3.0 | Build tool |
| **JUnit 5** | Unit and integration testing |
| **Spring MockMvc** | API testing |
| **Spring REST Docs** | Auto-generated API documentation from tests |
| **BDD Workflow** | Behavior-Driven Development with Given/When/Then steps |

### Observability

| Technology | Purpose |
|-----------|---------|
| **OpenTelemetry** | Instrumentation (traces, metrics, logs) |
| **Grafana** | Visualization dashboards |
| **Loki** | Log aggregation |
| **Tempo** | Distributed tracing |
| **Mimir** | Metrics storage |

### Infrastructure

| Technology | Purpose |
|-----------|---------|
| **Docker Compose** | Local development (auto-started by Spring Boot) |
| **Kubernetes** | Production deployment with independent scaling per role |

## Module Structure

The project is organized into four domain modules following standard Spring layered architecture:

```
src/main/java/com/keer/ticketmaster/
├── event/          # Event management — concerts, exhibitions, CRUD operations
├── venue/          # Venue management — venue info, seat configuration
├── ticket/         # Ticket service — ticket creation, inventory, status queries (Redis-cached)
└── reservation/    # Reservation core — high-concurrency booking via Kafka Streams dataflow
```

Each module contains: `controller/`, `service/`, `repository/`, `model/`, `dto/`

## Getting Started

### Prerequisites

- Java 25
- Docker Desktop

### Run Locally

```bash
# Start the application (Docker Compose services start automatically)
./gradlew bootRun
```

### Build & Test

```bash
# Build
./gradlew build

# Run all tests
./gradlew test

# Run a specific test class
./gradlew test --tests "com.keer.ticketmaster.SomeTestClass"

# Generate API documentation
./gradlew asciidoctor
```

## Acknowledgments

- [ticket-master](https://github.com/tall15421542-lab/ticket-master) by MING HUNG — the original high-performance implementation that inspired this project
- [Designing Data-Intensive Applications](https://dataintensive.net/) by Martin Kleppmann — the theoretical foundation for the dataflow architecture
