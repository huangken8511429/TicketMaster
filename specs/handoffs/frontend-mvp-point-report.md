# Frontend MVP — Point Report

**Stage**: `/point`
**Date**: 2026-05-18
**Source artifact**: `specs/handoffs/frontend-mvp-uiux-decision.md`
**Next Stage**: `/spec`

---

## 1. Request Summary

為現有的 TicketMaster Java 後端（Spring Boot 4 + Kafka Streams，已通過壓測）建立 **greenfield 前端搶票介面 MVP**：

- 4 個畫面：活動列表 → 活動詳情 → 排隊中 → 鎖位確認
- 票區式搶票（不畫場館 SVG，後端自動分配座位）
- 桌面優先（Desktop-first），散客導向
- 對接既有 REST API + long-polling + `SectionStatusEvent` 即時廣播
- 視覺要求高（editorial、深色高對比、避開制式 SaaS 感）
- **技術棧未定**（待 /spec 確認 React + Tailwind / WebSocket vs SSE / build tool）

## 2. Scorecard

| 維度 | 分數 | 理由 |
|------|------|------|
| Requirement Clarity | 3/5 | 畫面/邊界/視覺方向已清楚，但 Open Questions 仍有 4 條未答（鎖位 TTL 秒數、是否要搜尋、`GET /events/{id}/sections` 是否已含庫存、開賣前倒數機制）。技術棧未綁定。 |
| Domain Rule Complexity | 3/5 | 多條規則：票區徽章四級閾值（30% / 5% / 0）、long-polling 排隊體驗（不顯示精確位置）、鎖位 TTL 倒數確認流程、開賣前後狀態切換。非單一文案規則。 |
| Impact Radius | 4/5 | Greenfield 前端，全新 codebase，跨多畫面 + 跨多後端 endpoint（event / section / reservation / long-poll / status broadcast）。為共享核心通道。 |
| Contract / Schema Change | 3/5 | 不新增後端 entity 或 endpoint，但需要 **前端 API client contract** 從零定義；可能需要 SSE/WebSocket channel 對接 `SectionStatusEvent`，前端側為新 contract。 |
| Regression Risk | 1/5 | Greenfield 前端，無既有前端可破壞；對後端僅是 read-only consumer，不改後端邏輯。 |
| Knowledge Dependency | 3/5 | 需要查證既有後端 API shape（events / sections / reservations / long-polling endpoint、`SectionStatusEvent` schema 50B、async booking 回應契約），才能寫前端 API client。 |
| **Total** | **17/30** | 落在 `Spec First` 區間（15-30） |

## 3. Knowledge Base Needed

**Yes** — `/spec` 階段必須查證：

- `event` module 的 REST endpoint shape
- `venue` / section 的查詢 API（是否已有 `GET /events/{id}/sections` 含庫存）
- `reservation` 的 async booking + long-polling endpoint 契約（commit `e95daf8` 提到 long-polling）
- `SectionStatusEvent` 廣播通道（目前是 Kafka topic，前端要透過什麼通道接收？SSE? WebSocket? 還是輪詢？）
- 鎖位 TTL 設定（commit `eeb1af5` 提到 booking refactor，TTL 應在後端設定中）

## 4. Route Decision

**Route C: Spec First**

### 觸發條件
- Total 17/30 落入 Spec First 區間（>=15）
- Impact Radius >= 3 → 至少 Build With Verify
- 多項 Open Questions 未答（Requirement Clarity 影響）
- 前端 API contract 需要從後端反推（Knowledge Dependency 中度）
- Greenfield + 視覺設計品質要求 → 需要先固化 design system 與資訊架構

### 未觸發 Hard Stop（為何不是 spec 強制）
- Requirement Clarity = 3（< 4，未硬觸發）
- Domain Rule Complexity = 3（< 4，未硬觸發）
- 仍因 total 分數進入 Spec First

## 5. Why Spec First (而非 Direct Build)

1. **Greenfield 前端 = design system 決策不可逆**：技術棧（React/Vue/Svelte）、styling（Tailwind / vanilla / CSS-in-JS）、即時通道（WebSocket / SSE / long-poll）若直接 build，後續難改。
2. **視覺品質要求高**：`impeccable + bolder`、editorial 風格、避開制式 AI 美感——必須先 spec 出 design tokens、字體、accent color、倒數元件樣式，才能保證一致性。
3. **4 個畫面共享狀態管理**：票區徽章即時更新、排隊狀態、鎖位 TTL 倒數——需要先設計狀態流。
4. **後端 API 契約需反推**：前端要對接的 4-5 個 endpoint 與 `SectionStatusEvent` 通道，需要在 spec 階段明確列出 request/response shape。
5. **4 條 Open Questions 必須答完**才能 build：TTL 秒數、搜尋是否 MVP、sections API 形狀、開賣前後切換機制。

## 6. Risks / Red Flags

| 風險 | 說明 | 緩解建議 |
|------|------|---------|
| `SectionStatusEvent` 對前端通道未定 | 後端是 Kafka 廣播，前端無法直接訂閱 Kafka——需要 SSE / WebSocket bridge | spec 階段明確指定通道 + 後端是否需要新增 endpoint |
| 鎖位 TTL UX 倒數失敗處理未定 | TTL 過期 → 釋放座位 → 前端要回到哪個畫面？錯誤文案？ | spec 階段補使用者旅程的失敗路徑 |
| Editorial 設計品質難以「規格化」 | 風格主觀，spec 寫不清楚仍會做出 generic UI | spec 階段建議產出 mood board 或 reference URL，並指定 font / accent / spacing scale |
| `GET /events/{id}/sections` 可能不存在 | 後端目前 API surface 不明，可能需要新增 endpoint —— 若如此會擴大 scope 到後端 | spec 階段先 audit 既有 controller，必要時拆出後端 ticket |
| Long-polling timeout 與 retry 策略 | 前端要等多久？timeout 後是否自動重連？ | spec 階段補通訊細節 |

## 7. Gate Verdict

**`PASS-SPEC-FIRST`**

## 8. Allowed Next Commands

- `/spec`（強制下一步）
- spec 完成後再評估 `/plan`

## 9. Next Agent Reading List

下一個 fresh agent（`/spec` 階段）應讀：

1. **必讀** — `specs/handoffs/frontend-mvp-uiux-decision.md`（UI/UX 討論固化）
2. **必讀** — `specs/handoffs/frontend-mvp-point-report.md`（本檔，含風險與 Open Questions）
3. **必讀** — 後端 controller 列表（`src/main/java/com/keer/ticketmaster/*/controller/`）以反推前端 API contract
4. **必讀** — `SectionStatusEvent` schema 定義（commit `9de4bb6` 引入的 50B event）
5. **建議讀** — commit `e95daf8`（long-polling 實作）與 `eeb1af5`（async booking + TTL）以掌握後端通訊模型
6. **建議讀** — `CLAUDE.md` 與 MEMORY.md 取得專案脈絡（Phase 1 已完成壓測，技術棧為 Java 25 / Spring Boot 4）
