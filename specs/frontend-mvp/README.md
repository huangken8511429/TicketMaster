# Frontend MVP — Spec 總覽

**Stage**: `/spec`
**Date**: 2026-05-18
**Target**: TicketMaster 前端搶票 MVP（4 畫面，桌面優先，editorial 視覺）
**Backend**: Java 25 + Spring Boot 4 + Kafka Streams（已通過壓測）

---

## 1. 規格產出檔案

| 檔案 | 內容 |
|------|------|
| `README.md` | 本檔。總覽 + 對接 API 清單 + 即時通道決策 + 技術棧 |
| `activity-flow.md` | 4 畫面的使用者流程（Mermaid） |
| `api-contract.md` | 前端視角的 API contract（已存在 + 需後端補做） |
| `design-tokens.md` | 色票、字體、間距、動效時間 |
| `component-spec.md` | 票區徽章、倒數、排隊動畫等關鍵元件規格 |

## 2. MVP 範圍（來自 UI/UX decision）

- 4 畫面：活動列表 → 活動詳情 → 排隊中 → 鎖位確認
- 票區式搶票（後端自動分配座位，不畫場館 SVG）
- 桌面優先，散客導向
- 對接既有後端 API + 新增最小 SSE bridge

## 3. 後端 API 對接清單

### 已存在（直接使用）

| Endpoint | Method | 用途 | Spec 章節 |
|----------|--------|------|----------|
| `/api/events` | GET | 活動列表 | api-contract §2.1 |
| `/api/events/{id}` | GET | 活動詳情 | api-contract §2.2 |
| `/api/venues/{id}` | GET | 場館資訊（seat_map JSON） | api-contract §2.3 |
| `/api/tickets?eventId=` | GET | 該活動所有票券 | api-contract §2.4 |
| `/api/tickets/available?eventId=` | GET | 可售票券 | api-contract §2.5 |
| `/api/bookings` | POST | 建立 booking（202 + bookingId） | api-contract §3.1 |
| `/api/bookings/{bookingId}` | GET | Long-polling 等 booking 完成 | api-contract §3.2 |

### 需要後端新增（最小修改）

| Endpoint | Method | 用途 | 後端工作量 |
|----------|--------|------|-----------|
| `/api/events/{id}/sections` | GET | 票區清單 + 即時 availableCount | 小（包裝既有 Section + Redis 計數） |
| `/api/events/{id}/sections/stream` | GET (SSE) | 訂閱 `section-status` Kafka topic 廣播 | 中（KafkaListener + SseEmitter） |
| Event 新增欄位 `salesStartAt` | — | 開賣時間（前端倒數用） | 小（DB column + Response field） |

詳見 `api-contract.md` §4。

## 4. 即時通道決策（Open Question #1）

### 問題
`SectionStatusEvent`（50B Kafka 訊息）目前只在後端 Kafka topology 內流動，前端無法直接訂閱 Kafka。

### 候選方案

| 方案 | 優點 | 缺點 | 推薦度 |
|------|------|------|-------|
| **SSE (Server-Sent Events)** | 單向推送、HTTP/1.1 相容、瀏覽器原生 `EventSource`、與後端 long-poll 架構一致、可走 CDN/proxy | 單向（前端→後端要另開 fetch） | **★★★ 推薦** |
| WebSocket | 雙向、低延遲 | 需要額外 STOMP/sockjs 框架、proxy 設定複雜、和現有架構（DeferredResult + SseEmitter）混搭 | ★★ |
| 純前端輪詢 | 最簡單、零後端改動 | 高頻率輪詢成本高、即時性差、N 個前端 × N 個票區 → 後端壓力放大 | ★ |

### 推薦：SSE

**理由**：
1. 後端已有 `TicketSseService`（雖然推的是 `TicketResponse[]`），SSE 基礎建設已存在
2. `SectionStatusEvent` 是後端→前端的單向廣播，沒有前端推回的需求
3. `EventSource` API 內建 auto-reconnect、event id（last-event-id）續傳能力
4. 與既有 long-poll 後端模型（DeferredResult）相容，部署簡單

### 後端最小修改建議

在 API profile 新增一個服務：

```
SectionStatusSseService（新增）
├─ @KafkaListener(topics = "section-status")  ← 訂閱 Kafka
├─ ConcurrentHashMap<Long /* eventId */, List<SseEmitter>>
└─ 收到 SectionStatusEvent → 用 emitter.send() 推給訂閱該 eventId 的前端

SectionStatusSseController（新增）
└─ GET /api/events/{id}/sections/stream → 註冊 SseEmitter
```

**前端 client**：用瀏覽器原生 `EventSource`（不需第三方套件）。

---

## 5. 鎖位 TTL 決策（Open Question #2）

### 後端現狀
- **沒有獨立的「鎖位 → 確認」階段**。後端 `BookingService` 直接回 `BOOKED` 狀態 + `allocatedSeats`
- DB 的 `ticket.status` 只有 `AVAILABLE` / `BOOKED`（無 `RESERVED`）
- 只有 `POLL_TIMEOUT_MS = 10_000`（long-poll 等 booking 結果的 timeout）和 `BOOKING_CACHE_TTL = 5min`（Redis booking 結果快取）

### MVP 推薦做法

**Plan A（推薦，零後端改動）**：畫面 4「鎖位確認」改名為「分配完成」，
- 倒數元件用「**前端 UX 倒數 5 分鐘**」（純視覺，過期則前端引導使用者「重新搜尋」回畫面 1）
- 訊息是「請在 5 分鐘內前往結帳（結帳流程不在本 MVP）」
- 倒數來源：前端在 booking 完成時刻 + 5 分鐘
- 過期：UI 顯示「保留時間已過，請重新搶票」按鈕（demo 用，不真的釋放後端座位）

**Plan B（若後端願意新增 RESERVED 中間態）**：未來迭代再做，這次不擋。

**MVP 決策：Plan A**

詳見 `component-spec.md` §3「鎖位倒數元件」。

---

## 6. `GET /events/{id}/sections` 決策（Open Question #3）

### 後端現狀
- `EventResponse` 已有 `sectionCount` 整數，但**沒有逐 section 的 name / availability**
- `Section` JPA entity 有 `name` / `rows` / `cols` / `availableSeats`，但**沒有對外 endpoint**
- Redis 已有 sub-partition 的 atomic counter（`SeatAvailabilityRedisService.findAndDecrement`）

### 結論：**後端必須新增 `GET /events/{id}/sections`**

詳見 `api-contract.md` §4.1，包含建議的 Request/Response schema。

---

## 7. 開賣切換決策（Open Question #4）

### 後端現狀
- Event 沒有 `salesStartAt` 欄位
- 只有 `eventStartTime`（活動開始時間，例如演唱會 19:30）

### 結論

**後端必須新增 `salesStartAt: timestamp`** 到 Event entity 與 EventResponse。

**前端切換邏輯**：
- 活動列表 / 詳情 → 倒數元件用 `salesStartAt`
- 倒數到 0 → 前端自動啟用「搶這區」CTA（不需輪詢，本機時鐘觸發）
- 為避免使用者本機時鐘偏差，前端在「倒數到 0」當下 fire 一次 `GET /api/events/{id}/sections` 同步真實狀態
- 若使用者跨時鐘異常進入頁面（已過開賣時間），直接 enable CTA

詳見 `api-contract.md` §4.3。

---

## 8. 技術棧推薦

| 項目 | 推薦 | 一句理由 |
|------|------|---------|
| 框架 | **React 18 + TypeScript + Vite** | 生態最廣，editorial UI 元件選擇多，Vite 啟動快 |
| Styling | **Tailwind CSS + 自訂 design tokens** | 避開 MUI/AntD 制式感，自訂 utility 對齊 editorial 需求 |
| State / Server cache | **TanStack Query (React Query)** | API caching、retry、suspense 內建，搭配 long-poll 與 SSE 友善 |
| 即時通道 client | **瀏覽器原生 `EventSource`**（封裝 hook） | 不引第三方 SSE 套件，零依賴 |
| Long-poll client | **fetch + AbortController + retry-with-backoff** | 與 `EventSource` 一致用原生 |
| Routing | **React Router v6** | 4 個畫面標準路由需求 |
| Animation | **Framer Motion** | 排隊動畫、倒數脈衝、票區徽章狀態切換的微動效 |
| Build / 部署 | **Vite + 獨立 SPA**（與後端解耦） | 後端 Spring Boot，前端獨立部署到 CDN/static host |
| 字體 | **Inter Tight (UI) + JetBrains Mono (倒數)** | Inter Tight 是 editorial 取向 Sans-Serif；JetBrains Mono 等寬適合倒數儀式感 |

詳見 `design-tokens.md` §字體選擇。

---

## 9. 下一步

1. `/plan` 階段拆 Phase（建議 3-4 個 Phase，見 handoff 檔）
2. 後端側補做 3 個工作項（見 §3 表格）—— 是否要單獨拆 backend ticket 由 /plan 決定
3. 前端工程啟動：先建專案骨架 + design tokens + API client，再做畫面
