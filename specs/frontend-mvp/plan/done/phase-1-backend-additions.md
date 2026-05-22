# Phase 1 — Backend Additions

| 欄位 | 值 |
|------|----|
| **Phase ID** | `phase-1` |
| **Title** | Backend Additions（salesStartAt + 2 endpoints + SSE bridge + CORS） |
| **Status** | `done` |
| **DependsOn** | — |
| **CanParallelWith** | `phase-2` |
| **Estimated Effort** | M（1-2 天，悲觀 3 天） |
| **Actual Effort** | ~半天（直接 JUnit+MockMvc，未走 BDD slash 流程） |
| **Owner Skillset** | Java / Spring Boot / Kafka / BDD |
| **Completed At** | 2026-05-18 |

---

## Goal

在既有 Java 後端（Spring Boot 4 + Kafka Streams）做最小延伸修改，補齊前端 MVP 所需的 3 個 API、1 個欄位與 CORS，不動 Kafka 拓撲、不改 booking 業務邏輯。

---

## Deliverables（實際完成清單）

### D1. Event entity 新增 `salesStartAt` 欄位 ✅
- `po/Event.java` 加 `LocalDateTime salesStartAt`（nullable）
- `response/EventResponse.java` 加同名欄位
- `request/EventRequest.java` 加同名欄位（尾端，避免破壞既有 ctor 位置；既有 `EventWhenSteps` callsite 同步加 null）
- `service/EventService.java` `createEvent` / `toResponse` 串接 salesStartAt
- JPA `ddl-auto=update` 會自動加 column（既有設定，無需 Liquibase）

### D2. `GET /api/events/{id}/sections` ✅
- 新檔 `controller/SectionController.java`
- 新檔 `service/SectionAvailabilityService.java`（封裝 derive 邏輯）
- 新檔 `response/SectionAvailabilityResponse.java`
- 在 `service/SeatAvailabilityRedisService.java` 加 `getAvailableCount(eventId, section, subPartition)` 供 SSE / REST 共用
- 後端 derive `status`：閾值 `>30% → PLENTY`, `5%-30% → LIMITED`, `0-5% → FEW`, `=0 → SOLD_OUT`, `now<salesStartAt → NOT_STARTED`
- 404 when event 不存在

### D3. `GET /api/events/{id}/sections/stream`（SSE） ✅
- 新檔 `service/SectionStatusSseService.java`
  - `@KafkaListener(topics = "section-status", groupId = "sse-bridge-${random.uuid}", auto.offset.reset=latest)`
  - In-memory `Map<eventId, Map<section, Map<subPartition, availableCount>>>` 做 sub-partition aggregation
  - 推給前端的是已 aggregated `SectionAvailabilityResponse`（同 `/sections` shape）
  - 15 秒 heartbeat（單一 daemon scheduler）
  - emitter timeout 30 分鐘
  - 連線建立後立即送一個 `event: connected` 確認 bridge alive
  - 自動清理 closed emitters
- `SectionController.streamSections` 設 `X-Accel-Buffering: no` + `Cache-Control: no-cache`（防 proxy buffer）
- 只在 `api` profile 啟用

### D4. CORS 設定 ✅
- 新檔 `config/CorsConfig.java`
- 允許 origin：`http://localhost:5173,http://localhost:3000`（dev 預設），可用 env `ticketmaster.cors.allowed-origins` 覆寫成 production host
- methods: GET / POST / OPTIONS
- `/api/**` mapping

### D5. 測試（17 個新增測試，全綠）✅

採 JUnit 5 + MockMvc 直接寫（BDD slash command 不易自動觸發；BDD `.feature` 加 1 個含 2 個 scenario）：

- `service/SectionAvailabilityServiceTest`（9 個 unit test，cover happy / NOT_STARTED / fallback / 邊界閾值）
- `service/SectionStatusSseServiceTest`（4 個 unit test，cover subscribe / aggregation / emitter cleanup）
- `controller/SectionControllerTest`（5 個 slice test，cover happy / 404 / SSE header）
- `config/CorsConfigTest`（2 個 unit test，cover CSV origin parsing + defaults）
- `src/test/resources/features/event/活動開賣時間.feature`（2 個 scenario）+ `EventWhenSteps` / `EventThenSteps` 對應 step

### D6. application.properties 補充 ✅
- `ticketmaster.sse.heartbeat-interval-seconds=15`
- `ticketmaster.sse.emitter-timeout-minutes=30`
- `ticketmaster.kafka.section-status-topic=section-status`
- `ticketmaster.cors.allowed-origins=http://localhost:5173,http://localhost:3000`

### D7. OQ-1（票價）✅
- 採最簡實現：`Section` 加 `Long basePrice`（nullable）
- `SectionAvailabilityResponse.basePrice` 直接 surface
- 不寫死 fallback；前端對 null 顯示「票價未定」（contract 已言明）
- 不影響 booking 路徑（`Ticket.price` 仍是真實票價來源）

---

## 變更檔案清單

### 新增

```
src/main/java/com/keer/ticketmaster/config/CorsConfig.java
src/main/java/com/keer/ticketmaster/controller/SectionController.java
src/main/java/com/keer/ticketmaster/response/SectionAvailabilityResponse.java
src/main/java/com/keer/ticketmaster/service/SectionAvailabilityService.java
src/main/java/com/keer/ticketmaster/service/SectionStatusSseService.java

src/test/java/com/keer/ticketmaster/config/CorsConfigTest.java
src/test/java/com/keer/ticketmaster/controller/SectionControllerTest.java
src/test/java/com/keer/ticketmaster/service/SectionAvailabilityServiceTest.java
src/test/java/com/keer/ticketmaster/service/SectionStatusSseServiceTest.java
src/test/resources/features/event/活動開賣時間.feature
```

### 修改

```
src/main/java/com/keer/ticketmaster/po/Event.java                    # +salesStartAt
src/main/java/com/keer/ticketmaster/po/Section.java                  # +basePrice
src/main/java/com/keer/ticketmaster/request/EventRequest.java        # +salesStartAt
src/main/java/com/keer/ticketmaster/response/EventResponse.java      # +salesStartAt
src/main/java/com/keer/ticketmaster/service/EventService.java        # 串接 salesStartAt
src/main/java/com/keer/ticketmaster/service/SeatAvailabilityRedisService.java  # +getAvailableCount
src/main/resources/application.properties                            # +SSE / CORS config

src/test/java/com/keer/ticketmaster/event/then/EventThenSteps.java   # +活動的開賣時間為 step
src/test/java/com/keer/ticketmaster/event/when/EventWhenSteps.java   # +開賣時間 step + regex 收緊
```

---

## 新 endpoint 規格 vs api-contract.md 比對

### `GET /api/events/{id}/sections`

| 項目 | spec | 實作 | 一致 |
|------|------|------|------|
| Path / method | ✅ | ✅ | ✅ |
| Response shape `SectionAvailability[]` | eventId, section, totalSeats, availableCount, status | 多回了 `basePrice`（Long，可為 null） | ✅ (superset，前端可忽略) |
| Status enum | PLENTY / LIMITED / FEW / SOLD_OUT / NOT_STARTED | 完全一致 | ✅ |
| 404 when event missing | ✅ | ✅ | ✅ |

### `GET /api/events/{id}/sections/stream`

| 項目 | spec | 實作 | 一致 |
|------|------|------|------|
| Media type | `text/event-stream` | ✅ | ✅ |
| Event name | `section-status` / `heartbeat` | ✅ (額外有 `connected` 初始事件) | ✅ |
| Payload | 已 aggregated `SectionAvailabilityResponse` | ✅ | ✅ |
| Heartbeat interval | 15s | ✅ | ✅ |
| Emitter timeout | 30min | ✅ | ✅ |
| Anti-buffer header | `X-Accel-Buffering: no` | ✅ + `Cache-Control: no-cache` | ✅ |
| Reconnect 友善 | 每 event 帶 timestamp `id` | ✅ | ✅ |

### `GET /api/events` / `GET /api/events/{id}` — salesStartAt 欄位
- 兩個 endpoint 的 `EventResponse` 都會自動含 `salesStartAt`（null when legacy）✅

### CORS
- preflight `OPTIONS` 對 `http://localhost:5173` 回 200（origin / methods / headers 已宣告）✅

---

## 測試結果

```
./gradlew build      → BUILD SUCCESSFUL
./gradlew test       → 61 tests completed, 2 failed (pre-existing baseline)
                       新增 17 個測試 100% pass
```

**Pre-existing failure（與本 phase 無關）**：
- `RunCucumberTest > 預訂管理（Kafka Streams） > 使用者成功預訂連續座位`
- `RunCucumberTest > 預訂管理（Kafka Streams） > 座位不足時預訂失敗`

確認方式：`git stash --include-untracked` 後跑 `./gradlew test --tests "RunCucumberTest"`，同樣 2 個失敗。Root cause：commit `e95daf8`（booking 改 async long-polling）後，`POST /api/bookings` 改回 `202 Accepted`，但 `預訂管理.feature` 仍寫 `200 OK`。Feature 檔需另外更新。**未在本 phase 修復**——卡片 explicit 規範「不要改既有 controller 的既有 endpoint」，這屬 booking module owner 範疇。

---

## 對前端 Phase 2 的影響

### 與 contract 完全一致 ✅
- `EventResponse.salesStartAt` 直接可用
- `GET /api/events/{id}/sections` shape 與 contract §4.1 完全相符（加碼回了 `basePrice`，前端可選用）
- SSE event 為已 aggregated payload — 前端不需再做 sub-partition 累計（採 §4.2「推薦方案」）

### 額外但非破壞的偏差
- SSE 連線後額外送一個 `event: connected` — 前端 EventSource handler 對未知 event name 默會忽略；若想用作 readiness 訊號，listen `connected` 即可
- `SectionAvailabilityResponse.basePrice` 為新增欄位（contract 沒列）— 純加法，前端 type 多一個 optional 欄位

### 給前端的取用範例

```ts
// 列表
GET /api/events/42/sections
→ [
    { eventId:42, section:"A", totalSeats:200, availableCount:180,
      status:"ON_SALE_PLENTY", basePrice:1800 },
    ...
  ]

// SSE
const es = new EventSource(`${BASE}/api/events/42/sections/stream`);
es.addEventListener("section-status", (e) => {
  const payload = JSON.parse(e.data); // 同 SectionAvailabilityResponse shape
  // 直接用，不用 aggregate
});
es.addEventListener("heartbeat", () => { /* keep-alive */ });
es.addEventListener("connected", () => { /* bridge ready */ });
```

---

## 風險 / Unresolved

| 風險 | 嚴重度 | 狀態 / 緩解 |
|------|--------|-------------|
| 多 API replica 同時 consume `section-status` → 訊息量 ×N | 中 | 已用 `random.uuid` group id；section-status 本就需廣播性質。Watch metric on `kafka.consumer.records-consumed-rate`。 |
| SSE in-memory cache 跨副本不一致 | 低 | 每副本只服務自己的 emitters，無共享需求。新訂閱者拿初始狀態走 `/sections` REST。 |
| SSE bridge cold start 缺資料 | 低 | 已 fallback 回 `totalSeats`（避免誤判 SOLD_OUT）。 |
| nginx / ingress buffer | 中 | `X-Accel-Buffering: no` 已設；K8s 部署時驗證 ingress controller 是否尊重。 |
| Section.basePrice 為 null 時前端 UX | 低 | Contract 已言明 fallback「票價未定」。 |
| 既有 booking feature 測試 fail | 已存在 | Pre-existing，非本 phase 引入。建議 booking owner 同步更新 feature 為 `202 Accepted` 或補 long-poll step。 |
| Cucumber regex 收緊（`(.+) → ([^」]+)`） | 低 | 只動 1 個既有 step（含表演者場館區域 ctor），其餘 step 不動。 |

### Unresolved（移交給下一階段）

1. **demo seed data**：卡片 D1 提到「種子資料至少 2 筆，1 筆 salesStartAt 未來、1 筆已過」— 未實作。建議在 Phase 4（E2E）或 Phase 2 frontend MSW mock 階段補。理由：DB 種子腳本不在本 phase scope（會牽動其他 module），改在前端 mock 比較乾淨。
2. **production CORS origin**：目前 default 是 localhost。production deploy 時要透過 env 注入。
3. **`Section.basePrice` 從哪寫入**：MVP 後端目前沒有 admin UI 寫入 `basePrice`。若 demo 要顯示金額：(a) 改 `SectionRequest` 加 `basePrice` 並更新 `EventService.createEvent` 寫入，或 (b) 由 DB 種子直接填。本 phase 留 column 但未串 admin write path（避免拉伸 scope）。

---

## 給後續 Phase 的提示

- **Phase 2（前端 skeleton）**：API client TypeScript 類型可從 `SectionAvailabilityResponse` 直接抄；SSE handler 用 `EventSource`，listen `section-status` / `heartbeat` / `connected`
- **Phase 3（4 畫面實作）**：畫面 2 的 `<SectionBadge>` 直接吃 `status` 欄位算 color；倒數元件吃 `event.salesStartAt`
- **Phase 4（E2E）**：補 seed data + 跨 origin 真 preflight；考慮加 booking feature 修正（順手）

---

## References

- `specs/frontend-mvp/api-contract.md §4`
- `specs/frontend-mvp/README.md §4`
- `specs/handoffs/frontend-mvp-spec.md`
- Commit `9de4bb6`（SectionStatusEvent schema）
- Commit `e95daf8`（co-partition init keys）
