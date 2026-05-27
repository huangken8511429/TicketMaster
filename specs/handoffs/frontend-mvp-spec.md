# Frontend MVP — Spec Handoff

**Stage**: `/spec` → `/plan`
**Date**: 2026-05-18
**Source artifacts**:
- `specs/handoffs/frontend-mvp-uiux-decision.md`
- `specs/handoffs/frontend-mvp-point-report.md`
- 後端 audit（controller / service / Avro schema / application.properties）

---

## 1. Spec 產出檔案清單

| 路徑 | 內容 |
|------|------|
| `specs/frontend-mvp/README.md` | 總覽、API 對接清單、決策摘要 |
| `specs/frontend-mvp/activity-flow.md` | 4 畫面流程 Mermaid + 狀態 + 路由表 |
| `specs/frontend-mvp/api-contract.md` | 既有 API + 後端需新增 endpoint contract |
| `specs/frontend-mvp/design-tokens.md` | 色票 / 字體 / 間距 / 動效 token |
| `specs/frontend-mvp/component-spec.md` | 12 個元件 + 4 個 hook 規格 |

---

## 2. 4 條 Open Questions 的最終答案

### Q1：`SectionStatusEvent` 前端通道
**答**：**SSE（Server-Sent Events）**

**理由**：
- 後端已有 `TicketSseService`（基礎建設存在）
- SectionStatusEvent 是後端→前端單向廣播，不需要 WebSocket 雙向能力
- 瀏覽器原生 `EventSource` API 內建 reconnect，零第三方依賴
- 與既有 long-poll 後端模型（DeferredResult）相容

**對後端的最小修改**（建議）：
1. 新增 `SectionStatusSseService`（在 `api` profile）：`@KafkaListener(topics = "section-status")` 訂閱 Kafka，內部維護 `Map<eventId, List<SseEmitter>>`，做 sub-partition 累計
2. 新增 `SectionStatusSseController`：`GET /api/events/{eventId}/sections/stream` 註冊 SseEmitter
3. 每 15 秒送 `event: heartbeat` 防 proxy 斷線
4. CORS 允許 SSE endpoint

詳見 `api-contract.md` §4.2。

### Q2：鎖位 TTL
**答**：**MVP 採純前端 5 分鐘 UX 倒數（Plan A）**

**理由**：
- 後端 audit 發現**沒有獨立的 RESERVED 中間狀態**——booking 完成直接 `status=BOOKED` 並回 `allocatedSeats`
- DB `ticket.status` 只有 `AVAILABLE` / `BOOKED`
- 既有 timeouts：`POLL_TIMEOUT_MS=10s`（long-poll）、`BOOKING_CACHE_TTL=5min`（Redis booking 結果快取）
- MVP 不接結帳金流，沒必要為了 demo 倒數重構後端

**前端做法**：`<HoldCountdown>` 元件用 `Date.now() + 5*60*1000`，過期顯示「保留時間已過，請重新搶票」。後端**不真的釋放座位**——這是純視覺示意。

**未來迭代**：若加結帳流程，請後端補 `RESERVED` ticket status + Redis TTL key。

### Q3：`GET /events/{id}/sections` 是否存在
**答**：**不存在，必須新增**

**現狀**：
- `EventResponse` 只回 `sectionCount: number`
- `Section` JPA entity 有 `name/rows/cols/availableSeats` 但無對外 controller
- `SeatAvailabilityRedisService` 有 sub-partition 計數器

**新 endpoint contract**（見 `api-contract.md` §4.1）：
```
GET /api/events/{id}/sections
Response: SectionAvailability[] {
  eventId, section, totalSeats,
  availableCount,                // aggregated across sub-partitions
  status: 'NOT_STARTED' | 'ON_SALE_PLENTY' | 'ON_SALE_LIMITED' | 'ON_SALE_FEW' | 'SOLD_OUT'
}
```

**重要決策**：status 由後端 derive（不讓前端算閾值），避免 client-side drift。

### Q4：開賣切換
**答**：**後端新增 `salesStartAt` 欄位 + 前端本機時鐘倒數**

**現狀**：Event 只有 `eventStartTime`（表演開始），無「開賣時間」。

**前端邏輯**：
- 倒數來源 = `salesStartAt`
- 倒數歸零 → 本機 enable CTA + fire 一次 `GET /events/{id}/sections` 同步真實狀態（防本機時鐘偏差）
- 不採輪詢，避免 N 個前端壓垮後端

**對後端的修改**：
- Event entity + DB column + EventResponse 加 `salesStartAt: LocalDateTime`
- Liquibase / JPA `ddl-auto=update` 會自動加 column

---

## 3. 技術棧推薦

| 項目 | 推薦 | 理由 |
|------|------|------|
| 框架 | **React 18 + TypeScript + Vite** | 生態最廣，editorial UI 元件選擇多，Vite 啟動快 |
| Styling | **Tailwind CSS + 自訂 design tokens** | 避開 MUI/AntD 制式感 |
| Server state | **TanStack Query (React Query)** | API caching + retry + suspense 內建 |
| 即時通道 | **瀏覽器原生 `EventSource`** | 零依賴、內建 reconnect |
| Long-poll | **fetch + AbortController** | 原生 + 自訂 hook |
| Routing | **React Router v6** | 標準 4 畫面路由需求 |
| Animation | **Framer Motion** | 排隊動畫 / 倒數 snap / 徽章脈衝 |
| 部署 | **獨立 SPA**（CDN 或 static host） | 與 Spring Boot 後端解耦 |
| 字體 | **Inter Tight + JetBrains Mono** | editorial Sans + 倒數等寬 |
| Accent color | **`#D6FF3D` Acid Lime** | 不撞色、editorial、高對比 |

詳見 `design-tokens.md`。

---

## 4. 給 /plan agent 的提示

### 建議 Phase 拆分（4 個 Phase）

**Phase 1：後端最小修改（並行）** — `~2 day`
- `salesStartAt` 欄位 + migration
- `GET /api/events/{id}/sections` endpoint
- `GET /api/events/{id}/sections/stream` SSE bridge + KafkaListener
- CORS config

**Phase 2：前端基礎建設** — `~2 day`
- Vite + React + TS + Tailwind + design tokens 落地
- API client（fetch wrapper + types）
- React Query setup
- 共用元件：`<Button>` / `<Toast>` / `<StatusPill>` / `<SalesCountdown>` / `<HoldCountdown>`
- Hooks：`useCountdown`、`useAnonymousUserId`、`useBookingPoll`、`useSectionStatusStream`

**Phase 3：4 畫面實作** — `~3-4 day`
- 畫面 1（活動列表）：`<EventCard>` + grid layout
- 畫面 2（活動詳情）：`<SectionBadge>` + `<BookingConfirmModal>` + SSE 整合
- 畫面 3（排隊中）：`<QueueOverlay>` + long-poll 整合
- 畫面 4（鎖位確認）：`<HoldCountdown>` + 座位顯示

**Phase 4：E2E + Polish** — `~1-2 day`
- 整合測試（Playwright：4 畫面 happy path）
- A11y 檢查（keyboard、reduced motion）
- 響應式 fallback（768px 不破版）
- 部署設定（Vite build + CORS 驗證）

### 優先順序

1. **Phase 1（後端）和 Phase 2（前端基礎建設）可並行**——前端在 Phase 2 用 mock data，Phase 1 完成後切換真實 API
2. Phase 3 內部依序：畫面 1 → 2 → 3 → 4（按使用者旅程）
3. Phase 4 在主流程跑通後再做

### Plan 階段需要再決策的細項

- Mock data 用 MSW (Mock Service Worker) 或單純 fixtures？建議 **MSW**（與真實 API client 共享 contract）
- Storybook 是否要建？建議 **不建**（MVP scope 太小，元件不多）
- TypeScript strict mode：**啟用**
- E2E 工具：**Playwright**（與後端壓測團隊一致經驗）

---

## 5. Risks / Unresolved Issues

| Risk | 嚴重度 | 緩解 |
|------|--------|------|
| 後端 3 個新 endpoint 未做 → 前端被阻塞 | 高 | Phase 1/2 並行，前端先用 MSW mock 開發 |
| SSE 在 K8s / nginx 反向代理下可能被 buffer | 中 | 後端送 heartbeat、檢查 `X-Accel-Buffering: no` header |
| SectionStatus aggregation 跨 sub-partition：後端 SSE bridge 是否做？ | 中 | 推薦後端做（前端拿 aggregated 結果），需在 Plan 階段確認 |
| 5min UX 倒數與「沒有真實 TTL」會被 demo 觀眾質疑 | 低 | demo script 說明「結帳流程不在本 MVP」 |
| anonymous userId 沒有真認證 → 同人多 booking | 低 | MVP 接受（未來補登入） |
| `seatMap` JSON 字串 schema 未定義 | 低 | MVP 不用 seat map，畫面 2 只顯示場館名 |
| 字體 CJK 字重 loading 性能 | 低 | preload + subset，加 system fallback |

### Unresolved（不擋 /plan，但 build 階段需釐清）
1. **票價來源**：`<BookingConfirmModal>` 顯示金額。`Section` 沒有票價，`Ticket` 才有。Plan 階段決定：每個 section 是否用第一張 ticket 的票價代表？還是新增 `Section.basePrice`？
2. **活動海報圖片**：MVP 後端沒有圖片欄位。先用純色塊或 unsplash placeholder？
3. **Long-poll 跨多次失敗的最終文案**：誰負責寫客服話術？

---

## 6. 一鍵延續流程

```
/plan  ← fresh agent，讀本檔 + frontend-mvp/ 目錄，產出 Phase 拆分 plan.md
```

預期 /plan 會產出 4 個 Phase 卡片，並建議 Phase 1（後端）和 Phase 2（前端）並行。
