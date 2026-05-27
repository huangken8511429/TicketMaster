# Frontend MVP — Plan Handoff

**Stage**: `/plan` → `/build`
**Date**: 2026-05-18
**Source artifacts**:
- `specs/handoffs/frontend-mvp-uiux-decision.md`
- `specs/handoffs/frontend-mvp-point-report.md`
- `specs/handoffs/frontend-mvp-spec.md`
- `specs/frontend-mvp/{README,api-contract,activity-flow,component-spec,design-tokens}.md`

---

## 1. Plan 產出檔案清單

| 路徑 | 內容 |
|------|------|
| `specs/frontend-mvp/plan/plan.md` | 計畫總覽 + Dependency Graph (Mermaid) + 索引表 + 執行策略 + 工時估算 + Open Questions |
| `specs/frontend-mvp/plan/todo/phase-1-backend-additions.md` | 後端最小延伸（salesStartAt + 2 endpoints + SSE bridge + CORS），BDD 流程 |
| `specs/frontend-mvp/plan/todo/phase-2-frontend-skeleton.md` | 前端骨架（Vite + Tailwind + tokens + Router + Query + MSW + 共用元件） |
| `specs/frontend-mvp/plan/todo/phase-3-screen-event-list.md` | 畫面 1 活動列表（EventCard + Grid + SalesCountdown） |
| `specs/frontend-mvp/plan/todo/phase-4-screen-event-detail.md` | 畫面 2 活動詳情（SectionBadge + SSE + ConfirmModal） |
| `specs/frontend-mvp/plan/todo/phase-5-screen-queue.md` | 畫面 3 排隊中（QueueOverlay + long-poll） |
| `specs/frontend-mvp/plan/todo/phase-6-screen-hold-confirm.md` | 畫面 4 鎖位確認（HoldCountdown + 座位顯示） |
| `specs/frontend-mvp/plan/todo/phase-7-e2e-polish.md` | E2E 整合 + a11y + 響應式 + 部署 |
| `specs/frontend-mvp/plan/doing/` | 空（執行時搬動） |
| `specs/frontend-mvp/plan/done/` | 空（執行時搬動） |

**7 張 Phase 卡片，目錄結構符合 athena-carry-on-engineering-plan 預期。**

---

## 2. 建議的第一波執行 Phase

**同時啟動兩個 Phase（並行雙線）**：

1. **`phase-1-backend-additions`**（後端 RD / Java BDD workflow）
   - 後端 RD 走 `CLAUDE.md` BDD 流程實作 3 個 endpoint + 1 個欄位 + CORS
   - 預估 1.5-2 day，悲觀 3 day
2. **`phase-2-frontend-skeleton`**（前端 RD / Vite + MSW）
   - 用 MSW 攔截所有 endpoint，**不依賴**後端進度
   - 預估 1.5-2 day，悲觀 3 day
   - phase-2 完成後 phase-3 ~ phase-6 即可並行開動（人力夠時）

**為何選這兩個並行**：plan.md §2 Dependency Graph 已將 P1 / P2 標為 parallel-safe（互不依賴；前端用 MSW mock 與後端 contract 解耦）。

---

## 3. Plan 階段才發現的風險

### 3.1 SSE 在 K8s nginx 反向代理下的 buffer 風險（**最高風險**）

spec 階段已標註中度風險，**plan 階段確認這是 phase-7 收尾的核心驗證點**。建議：
- phase-7 D5 提早給 DevOps nginx config（`proxy_buffering off`）
- 本地用 nginx docker 預先 reproduce 驗證
- 若驗證失敗 → phase-4 可暫時 fallback 為 10 秒輪詢（不擋 demo）

### 3.2 票價來源（OQ-1）會擴張 phase-1 scope

`<BookingConfirmModal>` 需顯示金額。spec 留下兩個選項：
- 用 section 第一張 ticket 的票價代表（後端**不**改）
- 新增 `Section.basePrice`（後端**要**改，多 0.5 day）

**建議**：phase-1 kickoff 時向 PO 確認；若選後者 → 加入 phase-1 scope。

### 3.3 海報圖片來源（OQ-2）

phase-3 啟動前必須有答案，否則只能用純色塊（可接受，但 PO 若要 unsplash 需先決策）。

### 3.4 多人並行 vs 單人連續執行的策略選擇

- **單人**：建議 phase-2 → phase-3 → phase-4 → phase-5 → phase-6（順序執行）
- **多人**：phase-2 完成後可 phase-3 ~ phase-6 並行（4 人最快 2 天完成 4 畫面）

請 PO / TL 在 phase-2 收尾前決定團隊規模。

### 3.5 useBookingPoll / useSectionStatusStream 在 phase-2 是否寫完整

plan 建議 phase-2 寫**完整實作**（不只骨架），讓 phase-4 / phase-5 直接用。這比「phase-2 寫骨架 + phase-4/5 完善」省一次來回。

---

## 4. 給 `/build` 階段 fresh agent 的提示

### 4.1 進入方式

下一個 agent 用：
```
/athena-carry-on-engineering-plan
```

該 skill 會自動掃 `specs/frontend-mvp/plan/todo/`，根據 Dependency Graph 推薦下一步。

### 4.2 心智模型

- 一張卡片 = 一個 PR（建議）
- 卡片從 `todo/` → `doing/` → `done/`
- 每張卡片自帶足夠 context（不用看主對話）
- BDD workflow 是「畫面 phase 內部子流程」，不是獨立 phase

### 4.3 一定要先讀的檔案

依序：
1. `specs/frontend-mvp/plan/plan.md` —— 總覽
2. 該 phase 卡片本身
3. 卡片內 References 列的 spec 檔案
4. （後端 phase-1 才需要）`CLAUDE.md` BDD Workflow 章節

### 4.4 不要做的事

- ❌ 跳過 phase-2 直接做畫面（會在 phase-3 補一堆基建）
- ❌ phase-1 跟 phase-2 串行（會浪費 1.5-3 day）
- ❌ phase-3-6 在 phase-2 完成前開工（無基礎）
- ❌ phase-7 在某個畫面未完成時開工（會反覆改）
- ❌ 不寫 BDD .feature 直接寫實作（違反 CLAUDE.md 規範）

### 4.5 建議的進度檢查節點

- **D1 結束**：phase-1 + phase-2 都已 in_progress、各自基礎建立
- **D3**：phase-2 完成、phase-3 / phase-4 已啟動
- **D5**：4 個畫面 phase 都已 in_progress 或部分完成
- **D7**：4 個畫面完成、phase-7 啟動
- **D9-10**：phase-7 完成、可 demo

---

## 5. 一鍵延續流程

```
/athena-carry-on-engineering-plan  ← fresh agent，讀 plan/ 目錄，啟動第一波 phase
```

或若手動：
```
1. 開兩個 worktree（git worktree add）：一個跑 phase-1，一個跑 phase-2
2. 各自走 BDD：GIVEN → WHEN → THEN → TEST_VERIFY → Implement
3. 完成後把卡片從 todo/ 移到 done/，更新 plan.md 狀態
4. 在 phase-2 完成後啟動 phase-3 ~ phase-6
5. 全部完成後啟動 phase-7
```

預期總工時（中性估計）：並行 10.5 day，悲觀 16 day。

---

## 6. 卡關時可參考的回滾路徑

詳見 `plan.md §4.4`。摘要：

- phase-1 SSE 沒做完 → 前端 fallback 10 秒輪詢
- phase-1 `salesStartAt` 沒做完 → 前端 fallback「立即可賣」
- 字體 load 慢 → system-ui fallback
- Framer Motion 太大 → CSS keyframes
- nginx long-poll timeout → phase-7 給 nginx config 註記

---

## 7. Done Definition（整個 MVP）

當以下全數達成時，MVP 結束：

- [ ] 7 個 phase 卡片都在 `done/`
- [ ] Playwright happy-path 在 3 個瀏覽器 pass
- [ ] axe-core a11y 4 畫面零違規
- [ ] Production 部署可訪問
- [ ] 30 秒 demo 影片完成
- [ ] handoff `frontend-mvp-build.md` 寫完
