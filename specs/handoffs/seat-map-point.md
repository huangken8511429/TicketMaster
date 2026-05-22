# Seat Map (Venue Seat Selection) — Point Report

**Stage**: `/point`
**Date**: 2026-05-21
**Source artifact**: 使用者口語需求（無 PRD），既有上下文：`specs/handoffs/frontend-mvp-final.md`、`CLAUDE.md`、後端 `po/{Venue,Section,Seat,Event}.java`、`service/SeatAvailabilityRedisService.java`、`service/SectionStatusSseService.java`
**Next Stage**: `/spec`（強制）

---

## 1. Request Summary

使用者希望為每個場館（Venue）建立**可視化座位圖**，讓搶票者在前端**自行點選「第幾區、第幾排、第幾號」**的特定座位，再針對該指定座位送出搶票請求。

**與現況的關鍵衝突**：
- 目前系統是 **section-level 自動分配** —— 使用者只選票區，後端透過 Kafka Streams data-flow 在 `SectionSeatState` bitmap 內部分配座位，**座位對外不可見**。
- `Venue.seatMap` 雖然欄位存在（JSON string），但**目前未渲染、未使用**；`Seat` 是 `@Embeddable`（嵌入 Ticket/Reservation），**沒有獨立的可查詢 seat entity 與庫存狀態**。
- 既有壓測（97.92% 成功率、1696 req/s、P95=15ms）建立在 **bitmap 內部分配 + 不需要對外曝光個別座位**的前提上。一旦改為「使用者選位」，整個併發模型、API contract、UI 都要重新設計。

這不是「加一張座位圖頁面」的 UI 任務，而是**業務模型轉變**：從「自動分配」變成「使用者指定」。

## 2. Scorecard

| 維度 | 分數 | 理由 |
|------|------|------|
| Requirement Clarity | 4/5 | 一句話需求。未定義：座位狀態粒度（available / locked / sold？）、是否多選、選位後是否仍走排隊、TTL 行為、定價（per-seat vs per-section）、無障礙座位、相鄰座位推薦、Venue.seatMap JSON schema、開賣前是否可預覽座位圖。**Hard Stop 觸發**（>= 4）。 |
| Domain Rule Complexity | 5/5 | 重新定義 booking 核心規則：座位選擇的**樂觀鎖**（兩人同時點同一座位怎麼辦？）、座位 hold TTL、釋放策略、不再是「先到先得的票區」而是「指定座位的競爭」、與既有 Kafka data-flow（為 section 設計）的根本衝突。**Hard Stop 觸發**。 |
| Impact Radius | 5/5 | 影響面：(1) 新 entity / table（seat 庫存與狀態）、(2) Kafka Streams pipeline 重寫（從 section-level allocation 改為 seat-level reservation）、(3) Redis state store schema、(4) `SectionSeatState` bitmap 語意改變、(5) SSE 廣播粒度（section → seat）、(6) long-polling booking 通道、(7) 前端 4 個畫面 + 新增「座位圖選位」頁面、(8) Venue.seatMap 從 unused 變成 critical path。 |
| Contract / Schema Change | 5/5 | 新 endpoint（`GET /venues/{id}/seatmap`、`GET /events/{id}/seats?section=X` 含逐座位狀態、`POST /reservations` 改為帶 seatId 列表、可能需要 `POST /seats/{id}/hold` 預鎖）、新 entity（Seat 從 Embeddable 變成獨立 Entity + 狀態欄位）、新 schema migration（seat table、seat_status、可能拆分 section）、SSE event schema 從 `SectionStatusEvent`（50B）變成 per-seat update。**Hard Stop 觸發**。 |
| Regression Risk | 5/5 | 直接破壞已壓測通過的核心搶票流程。Bitmap 50B / 2000x 壓縮、3x 吞吐優化、co-partition init key、horizontal scaling 都依賴「section 自動分配」前提。改為使用者選位將使先前 Phase 1 的所有性能優化重新洗牌。**Hard Stop 觸發**（>= 3）。 |
| Knowledge Dependency | 4/5 | 需要查證：`Venue.seatMap` JSON schema 設計（不存在）、現行 `SectionSeatState` bitmap 與座位編號映射（`Seat` Embeddable 的 row/col 是否就是 user-facing 座位編號）、是否要支援不規則場館（非矩形 row×col）、無障礙座位 / VIP 區的規則、產品端是否真的要走「使用者選位」（vs. 只是要在 UI 上「展示」座位圖讓使用者選**區**）。**Hard Stop 觸發**（>= 4）。 |
| **Total** | **28/30** | 落在 `Spec First` 區間（>=15），且觸發**多項 Hard Stop**。 |

## 3. Knowledge Base Needed

**Yes** — 必查項目：

1. **產品意圖澄清**：使用者真的要「user picks exact seat」還是「visual venue map for choosing a section」？兩者的工程量差兩個數量級。
2. `Venue.seatMap` 欄位的**原始設計意圖** —— 是 SVG path / 矩形 grid / Ticketmaster-style 座位佈局？目前未渲染、未使用，需從零定義 JSON schema。
3. `SectionSeatState` 內部 bitmap 的座位編號規則（commit `e17a44d` 引入 sub-partition / bitmap state）—— 是否能對外曝光？
4. Kafka Streams pipeline 是否支援 seat-level command（目前是 section-level）？是否需要新增 topic？
5. 壓測前提下，**seat-level lock 是否仍能達成 1000+ req/s**？併發模型可能要從「無鎖 bitmap allocation」轉為「per-seat optimistic / pessimistic lock」。
6. 既有 BDD `.feature` 是否仍適用，或要全面改寫。

## 4. Route Decision

**Route C: Spec First**

### 觸發條件

- Total 28/30 遠超 Spec First 門檻（>=15）
- **5 項 Hard Stop 同時命中**：
  - Requirement Clarity = 4（>=4 → Spec First）
  - Domain Rule Complexity = 5（>=4 → Spec First）
  - Contract / Schema Change = 5（>=4 → Spec First）
  - Knowledge Dependency = 4（>=4 → Spec First）
  - Regression Risk = 5（>=3 → 至少 Build With Verify，已被 Spec First 覆蓋）
- 新 entity、新 schema migration、新 endpoint、改變核心 booking 業務模型 —— 完全命中「Hard Stops」清單

### 為何**絕對不能**直接 build

1. **業務模型衝突未解**：使用者選位 vs. 自動分配，不是技術選型差異，是**兩種完全不同的搶票產品**。先不問 PM 就 build，必定報廢。
2. **已壓測過的性能優化會被推翻**：Phase 1 的 bitmap state、sub-partition、co-partition init keys 全為 section-level 設計。
3. **Venue.seatMap schema 從未設計**：直接寫前端會綁死一個沒對齊後端的格式。
4. **無 PRD、無 acceptance criteria、無 wireframe**：連「能否多選」「TTL 幾秒」「失敗 fallback」都未定義。

## 5. Risks / Red Flags

| # | 風險 | 嚴重度 | 說明 | 緩解建議 |
|---|------|--------|------|---------|
| R1 | **與既有自動分配機制根本衝突** | 極高 | 現行 Kafka Streams pipeline 為 section-level allocation 設計，per-seat reservation 需要全新 command 流。可能要保留 section-mode 與新增 seat-mode 雙模式（或廢棄 section-mode）。 | spec 階段先確認**舊模式是否保留** —— 若保留則 Venue / Event 要有 mode flag |
| R2 | **產品意圖未澄清：選位 vs. 選區可視化** | 極高 | 「想要做座位圖」可能只是「給使用者看一張漂亮的場館圖然後點某區」，不是 Ticketmaster-style 逐座位點選。若是前者，工程量 / 1 sprint；若是後者 / 1 quarter。 | spec 階段第一題就問 PM：是否**逐座位可點**？ |
| R3 | **壓測表現會被打回原形** | 高 | 1696 req/s 建立在無鎖 bitmap。改為 per-seat lock 後，hotspot section（熱門演唱會前排）會集中於少數 seat row，併發退化嚴重。 | spec 階段須包含**新搶位併發模型**（樂觀鎖？預鎖 TTL？Kafka 是否仍適用？） |
| R4 | Venue.seatMap JSON schema 從零設計 | 高 | 既有欄位是 `String`、無 schema、無前端 renderer。需設計支援不規則場館、舞台位置、走道、無障礙座位、VIP 區。 | spec 階段交付 JSON schema + 範例 + renderer 草圖 |
| R5 | Seat 目前是 `@Embeddable`，不可獨立查詢 | 高 | 要「查某座位是否可訂」必須將 Seat 抽出為獨立 `@Entity` + status 欄位 + 與 Section 的 FK。會是 schema migration。 | spec 階段確認資料模型轉換策略 + 既有 Ticket / Reservation 的 Embedded Seat 是否要遷移 |
| R6 | SSE 廣播粒度爆炸 | 中高 | `SectionStatusEvent` 50B / 區。若改成 per-seat event，**1 萬座位場館** = 廣播量 200x，前端 render 壓力 + 網路成本提升。 | spec 階段考慮：(a) 只廣播差異、(b) 用 viewport-based 訂閱、(c) 折衷只更新 section 統計、座位明細用 on-demand query |
| R7 | 多人同時選同一座位的 UX 與技術衝突 | 中高 | A 點了 5A、提交時發現 B 已搶走 —— 要顯示什麼？要不要在 click 時就 soft-lock？soft-lock 又如何釋放？ | spec 階段定義**選位 → 預鎖 → 確認**三段流程與每段 TTL |
| R8 | 既有 BDD 測試與壓測腳本需大改 | 中 | `specs/` 下既有 feature 全部以 section-level 撰寫；k6 壓測腳本（commit `b2fd4c1`）也是 section-mode。 | spec 階段列入後續 BDD 重寫的 scope |
| R9 | 開賣前後狀態切換複雜化 | 中 | 開賣前要不要預覽座位圖？開賣瞬間 100 萬人同時 render 1 萬座位的 SVG，前端會炸。 | spec 階段定義 pre-sale / on-sale / sold-out 的座位圖呈現策略 |

## 6. Open Questions（必須在 /spec 解答）

1. **產品語意**：是「逐座位可點」（Ticketmaster 模式）還是「視覺化選區」（多數 e-commerce ticket 模式）？
2. **舊模式存廢**：section-mode 自動分配是否保留？是 per-Event flag？per-Venue flag？還是直接廢棄？
3. **多選**：一筆訂單可選幾個座位？相鄰座位推薦？
4. **預鎖機制**：點選座位後是否立即 hold？hold 幾秒？hold 期間其他人看到的狀態？
5. **失敗路徑**：選到一半座位被搶走 → 提示 + 重選？自動補位？放棄整單？
6. **Venue.seatMap schema**：採用什麼格式（自訂 JSON / SVG / Ticketmaster-style POI）？是否支援不規則場館？
7. **定價**：仍是 `Section.basePrice` 統一定價，還是 per-row / per-seat / VIP 加價？
8. **無障礙 / VIP / 兒童票**：是否需要在座位圖上標記特殊座位類型？
9. **效能目標**：seat-mode 仍要維持 1000+ req/s？P95 < 3s？還是接受性能 trade-off？
10. **既有壓測腳本與 BDD**：是否需要全面重寫？預算？

## 7. Gate Verdict

**`PASS-SPEC-FIRST`**

## 8. Allowed Next Commands

- `/spec`（強制下一步）
- spec 完成後再評估 `/plan`
- **禁止**：`/build-backend`、`/build-frontend`、`/build-with-verify`

## 9. Required Follow-up Gates

- `/spec` → 解答 Open Questions、定義 Venue.seatMap schema、決定新併發模型
- `/plan` → 工程拆解、是否分階段（先做選區可視化 → 再做選位）、schema migration 策略
- `/build-backend` → 新 entity、新 endpoint、Kafka pipeline 改動
- `/build-frontend` → 座位圖 renderer、選位 UX、預鎖倒數 UI
- `/verify` → 性能回歸測試（壓測必須重跑）
- `/review`、`/ship`

## 10. Next Agent Reading List

下一個 fresh agent（`/spec` 階段）應讀：

1. **必讀** — 本檔（含 10 條 Open Questions 與 9 條 Risks）
2. **必讀** — `specs/handoffs/frontend-mvp-final.md` 與既有 4 畫面前端 MVP（理解既有 section-mode UI）
3. **必讀** — 後端 entity：`src/main/java/com/keer/ticketmaster/po/{Venue,Section,Seat,Event,Ticket}.java`
4. **必讀** — 後端 service：`SeatAvailabilityRedisService.java`、`SectionAvailabilityService.java`、`SectionStatusSseService.java`、`BookingService.java`
5. **必讀** — Kafka Streams processor 與 `SectionSeatState`（commit `e17a44d` sub-partition / bitmap state、`9de4bb6` 50B event schema）
6. **必讀** — `CLAUDE.md` + MEMORY.md（理解 Phase 1 已完成壓測前提）
7. **建議讀** — commit `b2fd4c1`（k6 壓測腳本，了解既有性能基準）、`e95daf8`（long-polling）、`eeb1af5`（async booking + TTL）
8. **建議讀** — `Venue.seatMap` 既有用法（grep 全 repo 應為 0 hit，確認從未渲染）
