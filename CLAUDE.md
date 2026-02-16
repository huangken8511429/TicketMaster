# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

TicketMaster is a high-concurrency ticket booking system. It prioritizes high availability for ticket reads and strong consistency for booking (no duplicate bookings). It uses Kafka Streams with a data-flow approach to avoid pessimistic locks (blocking) and optimistic locks (excessive retries) under high concurrency, with WAL for event replay.

## Tech Stack

- **Java 25** with Virtual Threads
- **Spring Boot 4.0.2** (WebMVC)
- **Gradle 9.3.0** (build tool)
- **Kafka Streams** for concurrent booking flow
- **PostgreSQL** for persistence
- **Redis** for caching
- **OpenTelemetry** for observability (Grafana LGTM stack)
- **Lombok** for boilerplate reduction
- **Spring REST Docs** (MockMvc) for API documentation

## Build & Test Commands

```bash
# Build the project
./gradlew build

# Run tests
./gradlew test

# Run a single test class
./gradlew test --tests "com.keer.ticketmaster.SomeTestClass"

# Run a single test method
./gradlew test --tests "com.keer.ticketmaster.SomeTestClass.someMethod"

# Run the application (starts Docker Compose services automatically via spring-boot-docker-compose)
./gradlew bootRun

# Generate REST Docs
./gradlew asciidoctor
```

## Local Infrastructure

Docker Compose (`compose.yaml`) provides local services, auto-started by `spring-boot-docker-compose`:
- **PostgreSQL** — db: `mydatabase`, user: `myuser`, password: `secret`
- **Redis** — default config
- **Grafana LGTM** — observability UI on port 3000, OTLP endpoints on 4317/4318

## Architecture

Base package: `com.keer.ticketmaster`

### Module Structure (Spring 標準微服務分層)

專案按業務領域拆分為 4 個 service module，每個 module 採用標準分層架構：

```
src/main/java/com/keer/ticketmaster/
├── ticket/          # 票券服務 — 票券的建立、庫存管理、狀態查詢
│   ├── controller/  # REST API 端點
│   ├── service/     # 業務邏輯
│   ├── repository/  # 資料存取層 (JPA/Spring Data)
│   ├── model/       # Entity / Domain Model
│   └── dto/         # Request/Response DTO
├── reservation/     # 預定服務 — 搶票、訂單建立、付款流程（高一致性核心）
│   ├── controller/
│   ├── service/
│   ├── repository/
│   ├── model/
│   └── dto/
├── venue/           # 場館服務 — 場館資訊、座位配置管理
│   ├── controller/
│   ├── service/
│   ├── repository/
│   ├── model/
│   └── dto/
└── event/           # 活動服務 — 活動建立、場次管理、活動查詢
    ├── controller/
    ├── service/
    ├── repository/
    ├── model/
    └── dto/
```

### Module 職責與關係

- **event** — 管理活動（演唱會、展覽等）的 CRUD，關聯 venue
- **venue** — 管理場館與座位配置，供 event 和 ticket 引用
- **ticket** — 依據 event + venue 產生票券，透過 Redis 快取提供高可用讀取
- **reservation** — 搶票核心，透過 Kafka Streams data-flow 處理高併發訂票，確保不重複訂票

### 測試結構

```
src/test/java/com/keer/ticketmaster/
├── ticket/
├── reservation/
├── venue/
└── event/
```

- 使用 JUnit 5 + Spring MockMvc
- REST Docs snippets 產生至 `build/generated-snippets`

## BDD Workflow

This project is part of a BDD (Behavior-Driven Development) workshop. Use the BDD skills in order:
1. `/BDD-GIVEN` — Generate Given step class from feature description
2. `/BDD-WHEN` — Generate When step with MockMvc API tests
3. `/BDD-THEN` — Generate Then step for result verification
4. `/BDD-TEST_VERIFY` — Verify all steps compile and run (should fail on business logic, not compilation)
5. `/BDD-Implement` — Write production code until BDD tests pass
