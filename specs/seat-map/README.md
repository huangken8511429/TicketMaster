# Seat Map — Spec 總覽

**Stage**: `/spec`
**Date**: 2026-05-21
**Source artifacts**:
- `specs/handoffs/seat-map-point.md`（10 條 Open Questions + 9 條 Risks）
- `specs/handoffs/seat-map-pm-answers.md`（PM 對 3 條 blocking Q 的回答）
- `specs/frontend-mvp/` 全部 .md（既有 spec 風格 / 顆粒度）
- 後端 `po/{Venue,Section,Event}.java`
- 前端 `EventDetailPage.tsx`、`api/types.ts`、`mocks/seed.ts`

---

## 1. Scope

### Phase A — 視覺化選區（本次 spec 必交付，可立刻 build）

- 渲染**場館圖（SVG）**，使用者點某「票區」就送出搶票
- **後端搶票路徑零改動** —— 仍走既有 section-level Kafka Streams allocation
- Phase 1 壓測（1696 req/s、P95=15ms、97.92% 成功率）自動沿用
- 新增 `Venue.seatMap` JSON schema（目前 String 欄位 unused）
- 新增 `Event.bookingMode` enum（`SECTION_TEXT` / `SECTION_VISUAL` / `SEAT_LEVEL`）
- 前端 `EventDetailPage` 依 `bookingMode` 切換 renderer（既有 `<SectionList>` vs 新 `<VenueMap>`）

### Phase B — 逐座位選位（架構預留，本次 spec 不實作）

- 新增 Seat entity、新 Kafka topic、預鎖 TTL、per-seat SSE、新壓測
- 本次只列**大綱**與 Phase A 預留的 hooks（schema 欄位、enum 值）
- 詳見 `phase-b-future-work.md`

### 三模並行

| Mode | 狀態 | 渲染 | 搶票路徑 |
|------|------|------|----------|
| `SECTION_TEXT` | 既有 | `<SectionList>` 文字清單 | section-level Kafka allocation（不動） |
| `SECTION_VISUAL` | **Phase A 新增** | `<VenueMap>` SVG | section-level Kafka allocation（不動） |
| `SEAT_LEVEL` | Phase B | TBD（per-seat picker） | TBD（per-seat reservation pipeline） |

---

## 2. 規格產出檔案

| 檔案 | 內容 | 重要性 |
|------|------|--------|
| `README.md` | 本檔。Scope、檔案索引、最終決策摘要 | 入口 |
| `venue-seatmap-schema.md` | ⭐ **核心交付**：`Venue.seatMap` JSON schema、TS 型別、5 個 seed venues 範例、Phase B 預留欄位 | P0 |
| `booking-mode-design.md` | `bookingMode` 放 Event vs Venue 決策、enum 三值、向後相容、migration | P0 |
| `api-contract.md` | 新增 / 變動 / 不動 三類 endpoint、response shape before/after | P0 |
| `activity-flow.md` | 使用者流程（含 Mermaid 圖）、與既有 frontend-mvp 流程對照 | P1 |
| `component-spec.md` | `<VenueMap>` 元件 props / 互動狀態 / SSE 整合 / 切換邏輯 | P0 |
| `phase-b-future-work.md` | Phase B 大綱：Seat entity / 新 Kafka topic / 預鎖 / per-seat SSE / 新壓測 | P2 |

---

## 3. 三項關鍵決策摘要

### D1：`bookingMode` 放在 `Event`（不是 Venue）

**理由**：
- 同一個 Venue（如台北小巨蛋）可承辦不同型態活動 —— 演唱會用 `SECTION_VISUAL`，研討會用 `SECTION_TEXT`，未來付費首映可能用 `SEAT_LEVEL`
- `Venue.seatMap` 是「場館的物理事實」，`bookingMode` 是「活動的銷售策略」，兩者語意正交
- 既有 5 筆 seed event 預設 `SECTION_TEXT`，零行為改變

詳見 `booking-mode-design.md` §2。

### D2：`Venue.seatMap` 採「分層 JSON schema」

- Top level：`stage`（舞台位置）、`viewBox`（SVG 座標系）、`sections[]`
- Section level：`name`、`shape`（polygon / rect）、`labelAnchor`、`stageOrientation`
- **Phase B 預留**：`rows[]`、`seats[]`（Phase A 不填，schema 不擋）
- 採 `schemaVersion` 欄位允許未來無痛升級

詳見 `venue-seatmap-schema.md`。

### D3：API 變更最小化

- **新增**：無 — 既有 `GET /api/venues/{id}` 已回 `seatMap` 字串
- **變動**：
  - `EventResponse` 新增 `bookingMode` enum
  - `Venue.seatMap` 從「空字串 / null」變成「有效 JSON 字串」
- **不動**：`POST /api/bookings`、`GET /api/bookings/{id}`、`GET /api/events/{id}/sections`、SSE `/stream` 全部不動

詳見 `api-contract.md` §2。

---

## 4. 對 frontend-mvp 既有規格的相容性

| frontend-mvp 元件 / API | Seat Map 改動 | 說明 |
|---|---|---|
| `<SectionBadge>` / `<SectionList>` | 不動 | 在 `SECTION_TEXT` 模式下保留為唯一 renderer |
| `<BookingConfirmModal>` | 不動 | 兩個 mode 共用同一個 modal（點 polygon 與點 badge 一樣會開 modal） |
| `<QueueOverlay>` / `<HoldCountdown>` / 畫面 3 / 畫面 4 | 不動 | 搶票後流程完全沿用 |
| `useSectionStatusStream` SSE | 不動 | `<VenueMap>` 直接訂閱同一個 hook，把 status 染回 polygon fill |
| `GET /api/events/{id}/sections` | 不動 | section status 仍由此 endpoint + SSE 提供 |
| `EventResponse` | **加一個欄位** `bookingMode` | 既有舊資料 fallback `SECTION_TEXT` |

→ 對既有 4 畫面 MVP 的 regression risk **極低**：只在 `EventDetailPage` 多一個分支。

---

## 5. 效能與 Risk Recap

| Risk（從 /point 帶過來） | Phase A 是否觸發 | 處理方式 |
|---|---|---|
| R1 與既有自動分配衝突 | **不觸發** | Phase A 仍走 section-level allocation |
| R2 產品意圖未澄清 | **已解** | PM 確認 A 先做、B 後做 |
| R3 壓測表現會被打回原形 | **不觸發** | 搶票路徑零改動 |
| R4 Venue.seatMap schema 從零設計 | 觸發 | 本 spec 的 `venue-seatmap-schema.md` |
| R5 Seat 是 Embeddable 不可獨立查詢 | **不觸發** | Phase A 不引入 Seat entity；Phase B 才動 |
| R6 SSE 廣播粒度爆炸 | **不觸發** | 仍是 section 粒度 |
| R7 多人同時選同座位 UX | **不觸發** | Phase A 是 section 粒度，不存在「點同一座位」 |
| R8 既有 BDD / k6 壓測需重寫 | **不觸發** | 搶票邏輯不變，feature 不需改 |
| R9 開賣前後狀態切換 | 已由 frontend-mvp 解決 | 沿用 `SalesCountdown` + `salesStartAt` |

→ Phase A 真正剩下的工作是「**設計 seatMap schema + 寫一個 SVG renderer + 加 mode flag**」。

---

## 6. 下一步

1. `/plan` 階段拆 Phase（建議 2-3 個 Phase，見 `specs/handoffs/seat-map-spec.md` §4）
2. 後端側補做 2 個工作項（`Event.bookingMode` 欄位 + 為 seed venues 填入合法 seatMap JSON）
3. 前端工程啟動：先建 `<VenueMap>` 元件 + 對應 seed mock data，再接 `EventDetailPage` 切換

詳見 `specs/handoffs/seat-map-spec.md`。
