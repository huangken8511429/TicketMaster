# Seat Map — Spec Handoff

**Stage**: `/spec` → `/plan`
**Date**: 2026-05-22
**Source artifacts**:
- `specs/handoffs/seat-map-point.md`（10 條 Open Questions + 9 條 Risks，Gate Verdict: Spec First）
- `specs/handoffs/seat-map-pm-answers.md`（PM 對 3 條 blocking Q 的回答）
- 既有 `specs/frontend-mvp/` 全部 .md（既有 4 畫面 spec 風格 / 顆粒度）
- 後端 entity `po/{Venue,Section,Event}.java`、前端 `EventDetailPage.tsx`

---

## 1. Spec Summary（Phase A scope）

**目標**：在不破壞既有 section-level Kafka allocation 與 Phase 1 壓測表現的前提下，為使用者提供**視覺化選區**體驗（SVG 場館圖、點某區送出搶票）。

**範圍**：
- 後端搶票路徑**零改動** —— 既有 section-level Kafka Streams 流程完整保留
- `Venue.seatMap` 從 unused String 變成有 JSON schema 的視覺布局描述
- `Event` 新增 `bookingMode` enum（`SECTION_TEXT` / `SECTION_VISUAL` / `SEAT_LEVEL`），三模並行
- 前端 `EventDetailPage` 依 `bookingMode` 切換 renderer
- Phase B（per-seat 選位）只列大綱不實作，但 schema 與 enum 已預留 hooks

**不在 scope**：Seat entity、新 Kafka topic、預鎖 TTL、per-seat SSE、新壓測——全部延至 Phase B。

---

## 2. Artifacts Produced

| 路徑 | 內容（一行） |
|------|--------------|
| `specs/seat-map/README.md` | 總覽、檔案索引、三項關鍵決策摘要、對 frontend-mvp 既有規格的相容性 matrix |
| `specs/seat-map/venue-seatmap-schema.md` | ⭐ 核心交付：`Venue.seatMap` JSON schema（schemaVersion / viewBox / stage / sections[]）、TS 型別、5 個 seed venues 範例、Phase B 預留欄位 |
| `specs/seat-map/booking-mode-design.md` | `bookingMode` 放 Event vs Venue 的 trade-off 與最終決策（放 Event）、enum 三值定義、Java 改動 diff、向後相容 migration |
| `specs/seat-map/api-contract.md` | 新增 / 變動 / 不動三類 endpoint 清單、response shape before/after 對照 |
| `specs/seat-map/activity-flow.md` | 使用者流程（Mermaid 圖）、與既有 frontend-mvp 4 畫面流程對照 |
| `specs/seat-map/component-spec.md` | `<VenueMap>` 元件 props / 互動狀態 / SSE 整合 / `EventDetailPage` 切換邏輯 |
| `specs/seat-map/phase-b-future-work.md` | Phase B 大綱：Seat entity / 新 Kafka topic / 預鎖 TTL / per-seat SSE / 新壓測 / Phase A 已預留的 hooks |

---

## 3. Key Decisions

### D1：`bookingMode` 放在 `Event`（不是 `Venue`）
- **理由**：同 venue 可承辦不同型態活動（演唱會 SECTION_VISUAL / 研討會 SECTION_TEXT / 付費首映 SEAT_LEVEL）。`Venue.seatMap` 是「場館的物理事實」，`bookingMode` 是「活動的銷售策略」，語意正交。
- **影響**：Event entity 多 `BookingMode bookingMode` 欄位（`EnumType.STRING`、default `SECTION_TEXT`），5 筆 seed event 預設保持 legacy 行為。
- **詳見**：`booking-mode-design.md` §2。

### D2：`Venue.seatMap` 採「分層 JSON schema」
- **頂層**：`{ schemaVersion: 1, viewBox: "0 0 800 600", stage: {...}, sections: [...], legend?: [], meta?: {} }`
- **Section level**：`{ name, displayName?, tier?, shape: "polygon"|"rect"|"circle", polygon?|rect?|circle?, labelAnchor?, stageFacing?, /* Phase B 預留 */ rows?, seatGrid?, accessibilityZones?, blockedSeats? }`
- **join key**：`sections[].name` 與後端 `Section.name` 1:1 對應，SSE section status 推送可直接 lookup 染色。
- **Phase B 友善**：`schemaVersion` 欄位允許升版；Phase A renderer 不讀預留欄位但 schema 允許其存在。
- **詳見**：`venue-seatmap-schema.md` §2、§3。

### D3：API 變更最小化（變動策略）
- **新增 endpoint**：0 個
- **變動 endpoint**：`EventResponse` 新增 `bookingMode` 欄位；`Venue.seatMap` 從「空字串/null」變成「有效 JSON 字串」
- **不動 endpoint**：`POST /api/bookings`、`GET /api/bookings/{id}`、`GET /api/events/{id}/sections`、SSE `/stream` 全部不動 —— Phase 1 壓測表現自動沿用
- **詳見**：`api-contract.md` §2。

### D4：三模並行（向後相容）
- `SECTION_TEXT`（既有）/ `SECTION_VISUAL`（Phase A 新增）/ `SEAT_LEVEL`（Phase B 預留）三種模式同時存在於 codebase，由 `bookingMode` 決定渲染分支。
- 既有 4 畫面 MVP regression risk **極低**：只在 `EventDetailPage` 多一個分支；`<SectionBadge>` / `<BookingConfirmModal>` / `<QueueOverlay>` / `<HoldCountdown>` 全部不動。
- 既有 BDD `.feature` 與 k6 壓測**不需重寫**。

### D5：Phase B 全套延後但不阻擋
- Seat entity、新 Kafka topic、per-seat SSE、預鎖 TTL、新壓測**集中**列在 `phase-b-future-work.md`。
- Phase A schema 已預留 `rows` / `seatGrid` / `blockedSeats` / `accessibilityZones` 欄位 + `SEAT_LEVEL` enum 值，Phase B 啟動時不需要回頭改 Phase A 任何決策。

---

## 4. Gate Verdict

**PASS** ✅

理由：
1. 三個 PM blocking question 全部已回答並落實到 spec（mode flag 位置、舊模式存廢、效能 trade-off）
2. 六份規格檔內容自洽：README §3 摘要的三項決策與 booking-mode-design / venue-seatmap-schema / api-contract 內文一致；component-spec 的 switcher 邏輯與 booking-mode-design 的 enum 三值對齊
3. 對既有 Phase 1 壓測與 frontend-mvp 4 畫面的相容性已 matrix 化（README §4、§5），regression risk 已逐條對應 /point 的 9 條 Risk
4. Phase B 大綱已在 `phase-b-future-work.md` 完整列出，且 Phase A 已預留的 hooks 已明列，未來啟動 Phase B 不需要回頭改 Phase A
5. 內容明確且機械可譯，下一個 /plan agent 可直接拆 Phase

---

## 5. Open Risks

### 從 /point 帶過來（Phase A 仍需關注）
| Risk | Phase A 是否觸發 | 狀態 |
|------|------------------|------|
| R4 Venue.seatMap schema 從零設計 | 觸發 | 已交付（`venue-seatmap-schema.md`），需 build 階段為 5 筆 seed venues 各填一份合法 JSON |
| 其他 R1/R2/R3/R5-R9 | 不觸發 | Phase A 搶票路徑零改動，全部 deferred 到 Phase B |

### Spec 階段新發現
1. **seed venue JSON 填寫工時**：5 個 venue × 平均 6-12 個 section × polygon 座標手填，需 ~半天，建議由前端工程在 build 階段一次做完（或寫 seed script）
2. **CORS for venue static asset**：若未來把 seatMap JSON 抽出後端走 CDN，需另設 CORS / cache header（Phase A 仍走 `GET /api/venues/{id}` inline 回傳，暫不觸發）
3. **`Section.basePrice` 仍未存在**：`<BookingConfirmModal>` 顯示金額這條 frontend-mvp 的 unresolved 問題 Phase A 不解決，沿用其結論
4. **i18n**：`section.displayName` 與 `legend[].label` 目前是字串，未來 i18n 化的 key strategy 未定，Phase A 可接受硬編
5. **舊資料 migration**：既有 Event 沒有 `bookingMode` 欄位，DB 升 schema 後 default `SECTION_TEXT` —— 需確認 JPA `ddl-auto=update` 是否能正確處理 enum default，否則需顯式 migration script

---

## 6. 給 /plan agent 的提示

### 建議 Phase 拆分（2-3 個 Phase）

**Phase 1：後端 schema 落地** — `~1 day`
- `BookingMode` enum 新增
- `Event.bookingMode` 欄位 + migration（確認 `ddl-auto=update` 行為）
- `EventResponse` 加 `bookingMode` 欄位
- 為 5 筆 seed venues 各填一份合法 `seatMap` JSON
- 既有 endpoint 不動、既有 BDD / k6 不動

**Phase 2：前端 `<VenueMap>` 元件 + 整合** — `~2-3 day`
- 新增 `frontend/src/types/venueSeatMap.ts`（與 schema 對齊的 TS 型別）
- 新增 `<VenueMap>` SVG renderer（讀 seatMap JSON → 渲染 stage + sections polygon）
- 整合 `useSectionStatusStream` hook → polygon fill 依 section status 染色
- `EventDetailPage` 加 switcher：`bookingMode === 'SECTION_VISUAL'` 時 render `<VenueMap>`，否則 render 既有 `<SectionList>`
- 點 polygon → 開既有 `<BookingConfirmModal>`（重用既有搶票流程）
- MSW seed 資料同步加 venue seatMap mock

**Phase 3（可選）：E2E + Polish** — `~1 day`
- Playwright：新增 SECTION_VISUAL 模式的 happy path scenario
- A11y：keyboard 操作 polygon 選區、screen reader label
- 響應式：768px 場館圖縮放策略

### 優先順序
1. Phase 1 + Phase 2 可並行（前端 Phase 2 先用 MSW mock data，Phase 1 完成後切換真實 API）
2. Phase 3 在主流程跑通後再做

---

## 7. 下一個 agent（`/plan`）的 reading list

1. **必讀** — 本檔（spec handoff，含 Key Decisions 與建議 Phase 拆分）
2. **必讀** — `specs/seat-map/README.md`（spec 總覽 + 相容性 matrix）
3. **必讀** — `specs/seat-map/venue-seatmap-schema.md`（schema 細節，Phase 2 元件實作直接依據）
4. **必讀** — `specs/seat-map/booking-mode-design.md`（enum 三值、Event 改動 diff）
5. **必讀** — `specs/seat-map/api-contract.md`（變動/不動 endpoint 清單）
6. **必讀** — `specs/seat-map/component-spec.md`（`<VenueMap>` props / switcher 邏輯）
7. **必讀** — `specs/seat-map/phase-b-future-work.md`（確認 Phase A 不誤踩 Phase B 範圍）
8. **必讀** — `specs/handoffs/frontend-mvp-final.md`（既有 4 畫面狀態，避免 regression）
9. **參考** — `CLAUDE.md` + `MEMORY.md`（Phase 1 壓測現況：1696 req/s、P95=15ms、97.92%）

---

## 8. 一鍵延續流程

```
/plan  ← fresh agent，讀本檔 + specs/seat-map/ 全 7 個檔案，產出 Phase 拆分 plan.md
```

預期 /plan 會產出 2-3 個 Phase 卡片，並建議 Phase 1（後端 schema）與 Phase 2（前端 `<VenueMap>`）並行。
