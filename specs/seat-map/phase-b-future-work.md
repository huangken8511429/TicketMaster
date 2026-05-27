# Phase B — Future Work（per-seat 逐座位選位）

**Stage**: `/spec` — 大綱層級，不展開細節
**Status**: 架構預留，本次 spec **不實作**
**Trigger**: 當 PM 決定要做「使用者點到 3 區 B 排 12 號」這種粒度的搶票體驗時啟動

---

## 1. Phase B 範圍重申

Phase B 的核心使用者故事一句話：

> 「使用者在 SVG 場館圖上 zoom 進某一區後，看到一格一格的座位（含已售/可售狀態），**親手點選 3 區 B 排 12 號**，後端鎖定該座位 N 秒，逾時自動釋放。」

這是 Ticketmaster / 兩廳院售票系統的標準體驗，**與 Phase A 的「選區後由後端自動配座位」本質不同**：
- Phase A：使用者選 section，後端從 bitmap 取下一個可售座位 → section-level allocation
- Phase B：使用者選具體 seat（含 row / col），後端鎖定指定座位 → seat-level reservation

---

## 2. 需要新增 / 改動的工作項

### 2.1 資料模型
- `Seat` 從目前 `@Embeddable`（嵌在 `Section` 內）抽出為**獨立 `@Entity`**
- 新增 `status` 欄位：`AVAILABLE` / `HELD` / `SOLD`
- `Seat` 直接持有 `sectionId` FK、`rowLabel`、`colNumber`、`price`（若採 per-seat pricing）、`holdExpiresAt`、`holderUserId`

### 2.2 DB schema
- 新增 `seat` table、`seat_status` column、與 `section` 的 FK
- 視查詢模式建立 `(section_id, row_label, col_number)` 複合索引
- 若採「viewport 載入」優化：可額外加 spatial / range index

### 2.3 Kafka pipeline
- 新增 **seat-level command / event topic**（與既有 `reservation-requests` / `reservation-completed` 並存，**不取代**）
- 新增 Kafka Streams processor 處理 per-seat reservation（hold / release / commit）
- 既有 section-level pipeline 不動 —— 三模並行的核心保障

### 2.4 預鎖（Hold）機制
- 點選座位後 hold N 秒（建議 60-180s，PM 決定）
- TTL 失效自動釋放 —— 可由 Kafka punctuator 或 Redis TTL key 觸發
- UX 倒數提示：與既有 `<HoldCountdown>` 整合，但這次倒數**對應真實後端 TTL**（不像 Phase A 的純 UX 5min）

### 2.5 SSE 廣播
- 新增 **per-seat status event**（與既有 50B `SectionStatusEvent` 並存或取代）
- **必須考慮 viewport-based 訂閱**：一個演唱會數萬座位，全廣播會把 SSE 壓垮
  - 候選做法：`?section=X` 訂閱、`?bounds=x1,y1,x2,y2` viewport bbox、客戶端 zoom 才訂閱該區段
- 與既有 `useSectionStatusStream` hook 整合或新增 `useSeatStatusStream`

### 2.6 API contract
- `POST /api/seats/{id}/hold` —— 鎖定座位（回傳 hold token + 倒數時間）
- `POST /api/reservations` —— 帶 `seatId[]` list 完成預訂（既有 endpoint 擴充或新版）
- `GET /api/events/{id}/seats?section=X` —— 含每個座位的狀態（AVAILABLE / HELD / SOLD）
- `DELETE /api/seats/{id}/hold` —— 主動放棄 hold

### 2.7 前端 UX
- 座位圖 **zoom / pan**（建議用 `react-zoom-pan-pinch` 或自寫 viewBox manipulation）
- 點選座位即 hold + 倒數
- Hold 失敗（被別人搶先）→ UI 顯示「已被選走」並提示重選
- 多座位選擇（家庭 4 張）：需設計選滿釋放、購物車式 UX

### 2.8 壓力測試
- 必須 **完整重跑**——Phase 1 的 1696 req/s、P95=15ms、97.92% 成功率**不再保證**
- PM 已同意 trade-off（見 `seat-map-pm-answers.md` Q3）
- 新指標待 Phase B spec 階段定義

### 2.9 BDD 測試
- 既有 `.feature` 全部以 section-level 撰寫，**繼續保留**（cover Phase A 路徑）
- **新增** seat-level scenarios：選座、hold 過期、衝突鎖、多選釋放、viewport 訂閱
- 對應的 Given / When / Then step class 需新增

### 2.10 定價模型
- 可能擴展為 per-row / per-seat pricing（如「正中央前排比邊角貴」）
- 影響 `Seat.price` 或新表 `seat_price_rule`
- API response 需回傳 per-seat price

---

## 3. Phase A spec 已預留的 hooks（Phase B 可零阻擋接續）

| Hook | 出處 | Phase B 如何利用 |
|------|------|------------------|
| `Venue.seatMap` JSON schema 容納 seat-level metadata | `venue-seatmap-schema.md` §3、§5 預留欄位（`rows`、`seatGrid`、`blockedSeats`、`accessibilityZones`） | Phase B 直接填這幾個欄位，**不需要 schema breaking change**；只需把 `schemaVersion` 從 1 升 2 |
| `bookingMode` enum 已預留 `SEAT_LEVEL` 值 | `booking-mode-design.md` §3.1 | 後端只需在 Event 新增該值對應的 Event，即可使該 Event 走 Phase B 路徑，其他 Event 不受影響 |
| `EventDetailPage` 三分支 renderer 設計 | `component-spec.md` switcher 邏輯 | Phase B 只需新增 `<SeatPicker>` 分支，Phase A `<VenueMap>` 與既有 `<SectionList>` 完全不動 |
| 既有 section-level Kafka pipeline 不改 | `api-contract.md` §2「不動」清單 | Phase B 新增**並列** pipeline，三模並行（既有 Phase 1 壓測表現自動保留給 Phase A 與 SECTION_TEXT mode） |
| SSE hook 介面分離 | `useSectionStatusStream`（既有） | Phase B 新增 `useSeatStatusStream`，互不干擾，前端可同頁混用 |

---

## 4. 風險前瞻（在啟動 Phase B 前需先回答）

| # | 問題 | 須在 Phase B spec 階段回答 |
|---|------|---------------------------|
| 1 | Hold TTL 多久？（30s / 60s / 180s / 5min） | PM |
| 2 | 一次最多選幾個座位？（4 / 8 / 動態） | PM |
| 3 | 同 user 多 hold 是否允許跨 section？ | PM / 風控 |
| 4 | viewport-based SSE 還是 section-based SSE？ | RD（看壓測） |
| 5 | 既有壓測指標放寬到多少可接受？ | PM + RD |
| 6 | Phase A 與 Phase B 同時存在時，混合搶票會不會撞 section bitmap？（同一場活動是否會同時用兩種 mode？） | PM（多半答「同活動只用一種 mode」即可規避） |

---

## 5. 不在 Phase B Future Work scope 的議題

以下擺到 **Phase C 或更後**，本檔不展開：
- 動態定價（依剩餘量浮動）
- 二手票交易市場
- 多場次套票 / 季票
- 行動裝置原生 App（本系列仍 Web 優先）

---

**結論**：Phase A 的 spec 已完整為 Phase B 鋪好 schema / enum / renderer 三條鋼軌，Phase B 啟動時只需新建 Seat entity、新 Kafka topic、新 SSE 廣播器、新前端 picker —— **不需要回頭改 Phase A 的任何決策**。
