# Frontend MVP — API Contract

**Stage**: `/spec`
**前端視角的 API contract（既有 + 需後端新增）**

---

## 1. 通用約定

- **Base URL**: 部署時透過 env var 設定（dev `http://localhost:8080`）
- **Auth**: MVP 階段不做（無會員系統）；`userId` 暫用 anonymous UUID（localStorage 產生 + 持久化）
- **Content-Type**: `application/json`
- **錯誤格式**：後端目前不一致（有時 `{"error": "..."}`），前端需容忍兩種：plain text body 與 `{error: string}`

---

## 2. 既有 Endpoint（直接使用）

### 2.1 `GET /api/events`

列出所有活動。

**Response 200** — `EventResponse[]`
```ts
type EventResponse = {
  id: number;
  name: string;
  description: string;
  eventStartTime: string;  // ISO LocalDateTime, e.g. "2026-08-15T19:30:00"
  eventEndTime: string | null;
  venueId: number;
  venueName: string;
  performerName: string;
  totalSeats: number;
  sectionCount: number;
  // NEW (後端需補) ↓
  salesStartAt?: string;   // ISO LocalDateTime
}
```

**前端用途**：畫面 1 海報網格

---

### 2.2 `GET /api/events/{id}`

取得單一活動詳情。

**Response 200**: 同 EventResponse
**Response 404**: 不存在

**前端用途**：畫面 2 hero meta

---

### 2.3 `GET /api/venues/{id}`

取得場館資訊（含 seat_map JSON 字串）。

**Response 200** — `VenueResponse`
```ts
type VenueResponse = {
  id: number;
  name: string;
  location: string;
  seatMap: string;  // JSON-encoded string
}
```

**前端用途**：畫面 2 場館描述（MVP **不**畫 seat_map SVG）

---

### 2.4 `GET /api/tickets?eventId={eventId}`

列出該活動所有票（含已售）。

**Response 200** — `TicketResponse[]`
```ts
type TicketResponse = {
  id: number;
  eventId: number;
  eventName: string;
  section: string;
  seatRow: number;
  seatCol: number;
  status: "AVAILABLE" | "BOOKED";
  price: number;
  userId: string | null;
}
```

**前端用途**：MVP **不直接使用**（4 萬張票太大）；改用下方 §4.1 的 `/sections` aggregation endpoint

---

### 2.5 `GET /api/tickets/available?eventId={eventId}`

只列出可售票。

**前端用途**：MVP **不直接使用**（同上）

---

### 3. 既有 Booking Endpoint

### 3.1 `POST /api/bookings`

建立 booking command（非同步）。

**Request Body**
```ts
type BookingRequest = {
  eventId: number;
  section: string;       // e.g. "A"
  seatCount: number;     // 1-4
  userId: string;        // anonymous UUID from localStorage
}
```

**Response 202 Accepted**
```ts
{ bookingId: string }  // UUID
```

**Response 422 Unprocessable Entity**（Redis 預檢失敗——該票區無座位）
```ts
{ error: "No seats available" }
```

**前端用途**：畫面 2 「搶這區」CTA

---

### 3.2 `GET /api/bookings/{bookingId}`

Long-polling 等 booking 完成。後端 `DeferredResult`，timeout 10 秒。

**Response 200 OK**（booking 已完成）
```ts
type BookingResponse = {
  bookingId: string;
  eventId: number;
  section: string;
  seatCount: number;
  userId: string;
  status: "BOOKED" | "REJECTED";
  allocatedSeats: string[];   // e.g. ["A-3-5", "A-3-6"]  format: section-row-col
  createdAt: string;          // ISO Instant
}
```

**Response 202 Accepted**（long-poll timeout，仍未完成）
- body 為空，前端應立即重發

**Response 502 / 503**：暫時性錯誤，前端 backoff retry

**前端 retry 策略**：
- 202 → 立即重發（無 sleep）
- 5xx → exponential backoff (1s, 2s, 4s)
- 連續 60s 無 BOOKED → 失敗畫面

---

## 4. 需要後端新增（最小修改）

### 4.1 `GET /api/events/{id}/sections` — **必須新增**

**Why**：前端畫面 2 需要列出該活動的所有票區 + 即時狀態徽章。目前 EventResponse 只給 `sectionCount`（數字），section 列表只在 JPA entity，未對外。

**Request**
- Path: `eventId`
- Query (可選): `?subPartitionAggregate=true`（預設 true）

**Response 200** — `SectionAvailability[]`
```ts
type SectionAvailability = {
  eventId: number;
  section: string;       // "A", "B", ...
  totalSeats: number;    // rows × cols
  availableCount: number;  // 跨 sub-partition aggregated（從 Redis 或 state store）
  status: "ON_SALE_PLENTY" | "ON_SALE_LIMITED" | "ON_SALE_FEW" | "SOLD_OUT" | "NOT_STARTED";
  // status 由後端 derive，前端不再算閾值（避免 client-side drift）
  // 閾值：available/total > 30% → PLENTY
  //       5% ~ 30% → LIMITED
  //       0% ~ 5% → FEW
  //       0 → SOLD_OUT
  //       若未到 salesStartAt → NOT_STARTED
}
```

**後端實作建議**（最小修改）：
- 新增 `SectionController.getSections(eventId)`
- 從 `Section` JPA 取得 sections，從 `SeatAvailabilityRedisService` 取得 aggregated `availableCount`（已有 `getSubPartitionCount` 與計數器），合併狀態

---

### 4.2 `GET /api/events/{id}/sections/stream` (SSE) — **必須新增**

**Why**：前端畫面 2 票區徽章需要即時更新。`SectionStatusEvent` 已在 `section-status` Kafka topic 廣播，但目前沒有對前端的 bridge。

**Request**
- Header: `Accept: text/event-stream`
- Path: `eventId`

**Response 200** — `text/event-stream`

**Event format**：
```
event: section-status
id: <timestamp-ms>
data: {"eventId":123,"section":"A","availableCount":1234,"status":"ON_SALE_PLENTY","subPartition":0,"totalSubPartitions":4,"timestamp":1747000000000}

event: heartbeat
data: {}
```

前端 Aggregation 規則：
- SectionStatusEvent 是 per-sub-partition 的，前端需要在記憶體累計 `Map<section, Map<subPartition, availableCount>>`，再 sum 出 `total availableCount`，重新算 status
- **替代方案（推薦）**：後端直接在 SSE bridge 側做 aggregation，推給前端的是已 aggregated 的 `SectionAvailability` shape（同 §4.1 response），前端就不需自己累計

**後端實作建議**（最小修改）：
```java
// 新增 service：在 api profile 訂閱 Kafka topic
@KafkaListener(topics = "section-status", groupId = "sse-bridge-${random.uuid}")
public void onSectionStatus(SectionStatusEvent event) {
    // 1. 累計 sub-partition counters (in-memory)
    // 2. 推給訂閱該 eventId 的 SseEmitter
    sectionStatusSseService.broadcast(event.getEventId(), aggregate(event));
}

// 新增 controller
@GetMapping(value = "/api/events/{eventId}/sections/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter stream(@PathVariable Long eventId) {
    return sectionStatusSseService.subscribe(eventId);
}
```

**Reconnect 約定**：
- 後端 emitter timeout 設 30 分鐘
- 後端每 15 秒送 `event: heartbeat` 防 proxy 斷線
- 前端 `EventSource` 自動 reconnect（瀏覽器原生行為）
- 重連時前端應重 fire `GET /api/events/{id}/sections` 拿初始狀態（不依賴 last-event-id）

---

### 4.3 Event 新增 `salesStartAt` 欄位 — **必須新增**

**Why**：MVP 需要開賣倒數，目前 Event 只有 `eventStartTime`（演出開始時間）。

**DB 變更**（erm.dbml）：
```
Table event {
  ...
  sales_start_at timestamp  // NEW
}
```

**EventResponse**：
```ts
salesStartAt: string;  // ISO LocalDateTime
```

**前端使用**：
- 畫面 1 / 畫面 2 倒數元件以此為基準
- 若為 `null`（舊資料），前端 fallback：假設「立即可賣」

---

## 5. CORS / 部署

前端為獨立 SPA，需後端開放 CORS。

**建議後端 config**：
```java
@Configuration
public class CorsConfig implements WebMvcConfigurer {
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
            .allowedOrigins("https://<frontend-host>", "http://localhost:5173")
            .allowedMethods("GET", "POST");
    }
}
```

SSE endpoint 同樣需要 CORS（瀏覽器 EventSource 受 same-origin policy 約束）。

---

## 6. 前端 API Client 結構建議

```
src/api/
├── client.ts                 # fetch wrapper（base URL, error handling）
├── events.ts                 # getEvents(), getEvent(id)
├── sections.ts               # getSections(eventId)
├── bookings.ts               # createBooking(req), pollBooking(id)
├── sections-stream.ts        # subscribeSectionStream(eventId): EventSource hook
└── types.ts                  # 所有 TypeScript types（從本文件抄）
```

---

## 7. 後端工作量總結

| 項目 | 估計 | 必要性 |
|------|------|--------|
| `GET /api/events/{id}/sections` | 0.5 day | 必須 |
| `GET /api/events/{id}/sections/stream` (SSE + KafkaListener) | 1 day | 必須 |
| `salesStartAt` 欄位 + Liquibase/JPA migration | 0.5 day | 必須 |
| CORS config | 0.1 day | 必須 |
| **合計** | **~2 days** | — |
