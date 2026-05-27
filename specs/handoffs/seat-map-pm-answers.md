# Seat Map — PM Clarifications (Round 1)

**Stage**: post-`/point`, pre-`/spec`
**Date**: 2026-05-21
**Source**: 使用者在 /athena-flow Q&A 中回答的 3 條 blocking 問題
**For**: 下一個 `/spec` agent 必讀

---

## 回答摘要

### Q1：產品意圖 — 選位粒度
**Answer**: **A 先做，B 後做**
- **Phase A（本次 spec 主目標）**: 視覺化選**區** — 渲染場館圖，使用者點某區塊（如 VIP / A 區 / B 區），**後端仍自動配座位**（沿用現行 Kafka Streams section-level allocation）。
- **Phase B（後續再做，但架構要為它預留空間）**: 逐座位選位（Ticketmaster 式）— 使用者點到「3 區 B 排 12 號」這種粒度，後端針對指定座位搶。

### Q2：舊模式存廢
**Answer**: **保留，雙模並行**
- 現行「票區式」（純文字票區清單）模式**不廢棄**。
- 新增「視覺化選區」模式（Phase A）。
- 未來「逐座位」模式（Phase B）也並存。
- 三種模式都要能共存 → 推測需要 **per-Event 或 per-Venue 的 mode flag**，spec 階段需明確設計。

### Q3：效能要求
**Answer**: **可接受 trade-off**
- Phase 1 壓測指標（1696 req/s、P95=15ms、97.92% 成功率）**不再是新 mode 的硬需求**。
- 視覺化選區（Phase A）因為仍走 section-level allocation，效能應該不會劣化。
- 逐座位（Phase B）若有性能下降，可接受。

---

## 對 spec 階段的 scope 影響

### Phase A（本次 spec 必做）
1. **資料模型**：
   - `Venue.seatMap` 從 unused String 變成有 schema 的 JSON（描述各 section 的視覺布局：位置、形狀、舞台方向）
   - **不需要** 新增 Seat entity（仍走 section-level allocation）
   - 可能需要在 `Event` 或 `Venue` 加 `bookingMode` enum（`SECTION_TEXT` / `SECTION_VISUAL` / `SEAT_LEVEL`）
2. **API**：
   - 既有 `GET /events/{id}` 回應補上 venue seatMap（或新增 `GET /venues/{id}/seatmap`）
   - 既有 booking endpoint 不變（仍是 section-level）
3. **前端**：
   - 新增 venue map renderer（SVG 為主、可從 JSON 推導）
   - EventDetailPage 視 `bookingMode` 切換為「文字票區清單」或「視覺化選區」
   - 點某區 → 仍走原本搶票流程（不增加新搶位通道）
4. **效能**：
   - 後端搶票路徑零改動 → Phase 1 壓測指標自動沿用
5. **不在 Phase A scope**：
   - Seat entity / seat-level reservation
   - per-seat SSE 廣播
   - Kafka pipeline 改動
   - 預鎖 TTL 機制

### Phase B（架構預留，本次 spec 不實作但要不擋）
- spec 階段需確保 Phase A 的 Venue.seatMap JSON schema **能向下延伸到 seat 粒度**（例如每個 section 描述 grid 時，未來能標記具體座位 ID）
- spec 階段需確保 `bookingMode` enum 的設計能容納未來的 `SEAT_LEVEL`
- spec 階段需在「Future Work」段落列出 Phase B 所需的全套改動（Seat entity、新 Kafka topic、預鎖機制、per-seat SSE、新壓測），但不展開細節

---

## 對 /point 中 10 條 Open Questions 的更新狀態

| # | Question | Status |
|---|---|---|
| 1 | 產品語意：選位 vs 選區 | ✅ 已答（A 先做、B 後做） |
| 2 | 舊模式存廢 | ✅ 已答（保留、三模並行） |
| 3 | 多選 | ⏳ Phase A 仍是 section 粒度，多選議題實質出現在 Phase B；本次 spec 可先標 N/A |
| 4 | 預鎖機制 | ⏳ Phase B 才需要，本次 spec 列入 Future Work |
| 5 | 失敗路徑 | ⏳ Phase A 失敗路徑沿用既有（section 搶完即 sold out） |
| 6 | Venue.seatMap schema | ❗ **本次 spec 必交付**（Phase A 的 critical path） |
| 7 | 定價 | ⏳ 沿用既有 `Section.basePrice`，本次 spec 不動 |
| 8 | 無障礙 / VIP / 兒童票 | ⏳ Phase A 視覺呈現上可選擇是否標記，spec 階段可決定要不要加 metadata |
| 9 | 效能目標 | ✅ 已答（可 trade-off） |
| 10 | BDD / 壓測腳本重寫 | ⏳ Phase A 不需重寫（搶票邏輯不變），Phase B 才需要 |

**結論**：本次 spec 真正需深入處理的是 #6（Venue.seatMap JSON schema）+ Phase A/B 的 mode flag 設計。其他多數議題延後到 Phase B spec。

---

## 下一個 agent（/spec）的 reading list

1. **必讀** — 本檔（PM 答案）
2. **必讀** — `specs/handoffs/seat-map-point.md`（風險清單 + 完整 Open Questions）
3. **必讀** — `specs/handoffs/frontend-mvp-final.md`（既有前端 4 畫面狀態）
4. **必讀** — 後端 entity：`po/{Venue,Section,Event}.java`
5. **必讀** — 既有 EventDetailPage（`frontend/src/pages/EventDetailPage.tsx`）—— Phase A 要在此頁切換 renderer
6. **必讀** — `CLAUDE.md` + `MEMORY.md`（Phase 1 完成現況）
