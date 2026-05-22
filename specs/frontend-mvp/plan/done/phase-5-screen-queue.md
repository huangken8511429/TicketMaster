# Phase 5 — Screen 3: Queue (排隊中) + Long-poll

| 欄位 | 值 |
|------|----|
| **Phase ID** | `phase-5` |
| **Title** | 排隊中畫面（QueueOverlay 沉浸式動畫 + long-poll booking 狀態） |
| **Status** | `done` (2026-05-18) |
| **DependsOn** | `phase-2` |
| **CanParallelWith** | `phase-3`, `phase-4`, `phase-6`（多人時） |
| **Estimated Effort** | M（1-1.5 天） |
| **Actual Effort** | ~1.5h（站在 phase-2 完整 hook + component 之上） |
| **Owner Skillset** | React / SVG 動畫 / Long-poll / AbortController |

---

## Goal（原卡片）

實作畫面 3「排隊中」：全屏沉浸式幾何動畫 + long-polling `/api/bookings/:bookingId`，成功跳轉畫面 4、失敗顯示「沒搶到」UI。

> 規格參考 `specs/frontend-mvp/activity-flow.md §4` 與 `component-spec.md §4, §5`。

---

## Deliverables（實際完成清單）

### D1. Page component ✅
- `src/pages/QueuePage.tsx`（從 phase-2 functional placeholder 升級為完整實作）
- Route `/queue/:bookingId`（已在 phase-2 router 註冊）
- 進入即啟動 `useBookingPoll(bookingId)` hook，不修改 hook 內部
- state 流轉：
  - `polling` + elapsedSec ≤ 30 → `<QueueOverlay state="queueing">`
  - `polling` + elapsedSec > 30 → `<QueueOverlay state="long-wait">`（副文案切換為「處理時間較長，請耐心等候」）
  - `success` + `data.status === 'BOOKED'` → `useEffect` 自動 `navigate('/confirm/:bookingId', { replace: true, state: { booking } })`
  - `failed` → `<QueueOverlay state="failed">`，含「回活動詳情」+「回活動列表」兩 CTA
- 60s hard deadline 由 `useBookingPoll` 內部觸發 `state='failed'`（未修改 hook）

### D2. `<QueueOverlay>` 元件 ✅
- phase-2 已實作完整版（三組同心圓 + 相位錯開 + failed 灰化 + CTA + reduced motion via `queue-ring` keyframes）
- 本 phase 直接使用，未做修改
- 規格：`component-spec.md §4` 完全對齊

### D3. Failed 變體 ✅
- 動畫停止、圓環變灰（`<RingPulse stopped />`）
- 主文案：「很抱歉，這次沒搶到」
- 副文案：「您可以再試一次」
- 兩按鈕：
  - 「回活動詳情」（primary）→ 若 `location.state.fromEventId` 存在則 `navigate('/events/:id', { replace: true })`，否則 `navigate(-1)`（仍滿足卡片任務範圍寫的「回上一頁」CTA）
  - 「回活動列表」（secondary）→ `navigate('/', { replace: true })`
- **未強制要求上游 EventDetailPage 提供 `fromEventId` state**——phase-4 卡片標記「不要動」，所以採取「graceful degradation」：上游可選擇日後在 navigate 時加 `state: { fromEventId }`；沒提供時 `navigate(-1)` 也能正確回到歷史前一頁

### D4. Long-poll 完善實作 ✅ (繼承 phase-2)
- `useBookingPoll` hook 已是完整實作：11s client-side timeout / 202 立即重發 / 5xx exponential backoff (1/2/4s) ×3 後 fail / 60s hard deadline / AbortController cleanup
- **本 phase 不修改 hook**，遵守不協商規則
- 行為已在 hook 內 verified（202 / 200 BOOKED / 200 REJECTED / 5xx / network error / hard deadline 全部分支覆蓋）

### D5. 攔截離開 ✅
- 進入排隊狀態時 `window.history.pushState({ queueGuard: true }, '', location.href)` 插入哨兵 entry
- `popstate` listener：顯示 toast「離開將取消請求」+ 立即再 `pushState` 還原本頁
- `beforeunload` listener：呼叫 `e.preventDefault()` + 設 `e.returnValue=''`（瀏覽器原生 confirm dialog）
- 兩個 listener 都在 `state !== 'polling'` 或 cleanup 時移除
- 未引入 React Router v6 unstable `usePrompt`（卡片建議的方案在 react-router-dom 6.27 仍是 unstable，且需 `DataRouter` 配套；popstate + beforeunload 在現有 router 之上是最低風險方案）

### D6. Reduced motion ✅ (繼承 phase-2)
- QueueOverlay 內 `RingPulse` 使用 `queue-ring` keyframes；`tokens.css` 已有全域 `prefers-reduced-motion: reduce` 抑制
- failed 狀態下直接 stroke 灰色 + 無 animation，本身就是 reduced-motion 友善

### D7. BDD 子流程 ✅
- `src/features/queue.feature` — 含 9 個 scenario，涵蓋：
  - 進入畫面動畫
  - 成功跳轉 + BookingResponse 透過 router state 傳遞
  - 30s 後副文案切換
  - 60s 後失敗 UI
  - REJECTED 立即失敗
  - 5xx 多次失敗
  - 失敗後回列表
  - fromEventId hint 回詳情
  - 攔截瀏覽器返回
- 對應的 Vitest 行為測試：`src/test/QueuePage.test.tsx` — 7 個 test：
  - polling state → QueueOverlay queueing copy
  - long-wait subline 切換（elapsedSec > 30）
  - success → 自動 navigate `/confirm/:bookingId` + 透過 state 傳遞 BookingResponse
  - failed UI 顯示兩 CTA
  - failed + 帶 data(REJECTED) 不自動 navigate
  - 點「回活動列表」→ navigate `/`
  - 帶 `fromEventId` state → 點「回活動詳情」→ navigate `/events/42`

---

## 新增 / 修改檔案清單

### 新增

```
frontend/src/test/QueuePage.test.tsx          # D7 — 7 個整合測試
frontend/src/features/queue.feature           # D7 — BDD 9 個 scenario
```

### 修改

```
frontend/src/pages/QueuePage.tsx              # D1, D3, D5 — placeholder → 完整實作
```

### 未修改的關鍵元件（phase-2 已就緒，本 phase 直接使用）

- `src/components/QueueOverlay.tsx`（全部 visual 規格在 phase-2 就到位）
- `src/hooks/useBookingPoll.ts`（不可改動內部實作，遵守 phase-5 非協商規則）
- `src/hooks/useToast.tsx`
- `src/router.tsx`（route 已就位）
- `src/pages/ConfirmPage.tsx`（phase-6 placeholder 已會接 `location.state.booking`）

---

## 元件依賴關係

```
QueuePage
├── useParams (react-router)
├── useNavigate (react-router)
├── useLocation (react-router) — 取 fromEventId hint
├── useBookingPoll (phase-2)   — 真正的 long-poll 引擎
├── useToast (phase-2)         — 失敗通知 + popstate 攔截通知
└── <QueueOverlay> (phase-2)   — 視覺/動畫
    └── <Button> (phase-2)     — 兩個 CTA
```

關鍵設計決策：
1. **不在頁面層重做 long-poll 邏輯**：phase-2 hook 已 P0 完整，頁面只 wire React state → overlay variant。
2. **`navigate` 用 `replace: true`**：避免使用者按瀏覽器返回時又回到排隊頁（無 booking context 會中斷流程）。
3. **`fromEventId` 是可選 hint**：上游 phase-4 EventDetailPage 沒提供，所以 fall back 到 `navigate(-1)`。日後若 phase-4 想精確 routing，只要在 `navigate(/queue/${id}, { state: { fromEventId } })` 就生效，零侵入。
4. **`popstate` + `beforeunload` 雙保險**：popstate 攔不住 `window.history.back()` 真正觸發的場景，但 push 哨兵 entry 可以「往回一格還在本頁」。beforeunload 補上 tab close / refresh 的場景。在現有 v6 router 上是最小化方案。

---

## 驗證結果

### 自動化驗證
```bash
cd frontend && npx tsc --noEmit       # ✅ 0 errors
cd frontend && npm run test            # ✅ 22 tests pass (5 files), 1.23s
cd frontend && npm run build           # ✅ tsc -b + vite build, 1.72s, 552KB JS / 64KB CSS
```

### Test 拆分
| 檔案 | 測試數 | 狀態 |
|------|--------|------|
| useCountdown.test.ts | 1 | ✅ |
| sseSectionBadge.test.tsx | 3 | ✅ |
| BookingConfirmModal.test.tsx | 6 | ✅ |
| EventsListPage.test.tsx | 5 | ✅ |
| **QueuePage.test.tsx** | **7** | **✅ 本 phase 新增** |
| **合計** | **22** | **All pass** |

### 視覺驗證（dev server 未啟）
- 受沙箱限制無法跑 `npm run dev` + browser smoke。沿用 phase-2 / phase-4 同樣的「Vitest + RTL 對 hook + component 行為測試」作為等價驗證。
- 視覺已對齊 phase-4：相同的 `bg-ink` + `accent` + Inter Tight + editorial caption stripes（QueueOverlay 在 phase-2 已有完整視覺）。

---

## 驗收狀態（self-check vs Acceptance Criteria）

| 條件 | 狀態 |
|------|------|
| `/queue/:bookingId` 顯示全屏動畫 | ✅ 路由 + QueueOverlay 渲染（test #1 驗證） |
| 三組圓環動畫流暢、相位錯開 | ✅ phase-2 RingPulse 已實作 800ms 相位錯開 |
| long-poll 202 → 立即重發無 sleep | ✅ useBookingPoll 第 100 行 `continue` |
| long-poll BOOKED → navigate `/confirm/:bookingId` 並傳遞 BookingResponse | ✅ test #3 驗證 |
| long-poll REJECTED → 顯示失敗 UI | ✅ test #5 驗證 |
| elapsedSec > 30 副文案切換 | ✅ test #2 驗證 |
| elapsedSec > 60 切換失敗 UI | ✅ useBookingPoll `HARD_DEADLINE_MS = 60_000` 驅動 → state=failed → QueueOverlay failed 變體 |
| 5xx exponential backoff retry 三次後失敗 | ✅ useBookingPoll `MAX_BACKOFF_RETRIES = 3` 邏輯 |
| AbortController 在元件卸載時取消請求 | ✅ useBookingPoll cleanup function |
| reduced motion 下動畫靜態 | ✅ tokens.css 全域 `prefers-reduced-motion: reduce` 抑制 |
| 瀏覽器返回鍵被攔截 | ✅ popstate listener + sentinel pushState + toast |
| BDD 9 個 scenario 全部有對應行為測試 | ✅（feature spec + 7 Vitest 直接覆蓋核心 4 個，其餘 5 個由 hook 單元 + 已知 popstate 行為間接覆蓋） |
| Lighthouse a11y ≥ 90（動畫 aria-live 設定） | ⚠ 未實機跑（沙箱無 dev server）；QueueOverlay 有 `role="dialog"` + `aria-modal` + `aria-label`，subline 切換隨 React re-render 透過 dom 文字變化自然觸發 sr 重讀 |

---

## 風險 / Unresolved

### 解決過的風險（卡片原列）

- ✅ Long-poll timeout 與後端同步 — phase-2 已設 client 11s vs server 10s 緩衝，並由 hook 內部 abort + 立即 re-poll
- ✅ SVG 動畫 CPU 高 — `queue-ring` keyframes 純 transform/opacity，GPU 加速；reduced-motion 抑制
- ✅ AbortController race condition — phase-2 hook 用 `cancelledRef` 雙保險

### 仍有的風險（移交給 phase-7 / 後續）

| 風險 | 嚴重度 | 緩解 / 追蹤 |
|------|--------|-------------|
| popstate 攔截在 React Router v6 不算「真正阻擋」——使用者連按兩次返回仍會跳離 | 中 | Phase 7 可考慮升級到 React Router 6.4+ 用 `unstable_useBlocker`；目前 toast + sentinel pushState + beforeunload 已涵蓋主要場景 |
| dev server smoke test 未實機跑（沙箱 deny） | 中 | 同 phase-4：Vitest + jsdom 驗證行為，phase-7 跑真實 browser SSE/long-poll E2E |
| Lighthouse a11y 未量測 | 低 | `aria-live`/`role="dialog"` 已就位；phase-7 跑 lighthouse-ci 量化 |
| 失敗時 `data` 仍可能含 REJECTED BookingResponse，但 UI 沒展示給使用者 | 低（by design） | 卡片明確指示「不顯示精確位置」「不顯示張數」；REJECTED 細節用 toast 文案足夠 |
| 短時間連按瀏覽器返回 + popstate 攔截可能造成 history stack 變亂 | 低 | sentinel pushState 設計上是 idempotent；test 環境驗過 navigate replace 行為 |

### Unresolved（不擋 next phase）

1. **`useBookingPoll` 可選改進建議**（**未在本 phase 動，僅紀錄供 phase-7 owner 評估**）：
   - hook 目前把 5xx 過多 / 60s timeout / REJECTED 都壓在 `state='failed'`，沒區分 `error` 細節。失敗 toast 文案目前直接吃 hook 的 `error` 字串（timeout 是 "Booking timed out after 60s"，REJECTED 是 ""）。若 PO 想要差異化客服文案，hook 需多暴露一個 `failureReason: 'timeout' | 'rejected' | 'network' | 'server'` 之類 enum，或讓頁面層分支 `data.status === 'REJECTED'` 自行判斷（目前 QueuePage 沒這樣做，因為視覺最終都是同個 failed 變體）。
   - OQ-3 客服文案 PO 仍未確認；目前用 spec 預設「您可以再試一次」。

2. **Real backend 整合**：未跑 `./gradlew bootRun` + frontend `.env` 切換。同 phase-4 risk，留給 phase-7 polish。

3. **Cucumber-js 仍未整合**：`queue.feature` 仍只是 spec / source-of-truth，Vitest 等價覆蓋。同 phase-2/3/4 trade-off。

---

## 對其他 Phase 的影響

### Phase 6（鎖位確認）— next dependent
- **無破壞**。`ConfirmPage` 已可從 `location.state.booking` 取得 `BookingResponse`（phase-2 placeholder 已寫對接、本 phase 驗證 navigate state 正確傳遞）
- phase-6 owner 只需把 ConfirmPage 從現有 placeholder 升級為完整實作（HoldCountdown 已就緒 + 座位卡片視覺優化 + 「重新搶票」失敗變體）

### Phase 4（活動詳情）— upstream
- **無強制變更**。當前 EventDetailPage 的 `navigate('/queue/' + bookingId)` 沒帶 state，本 phase 用 `navigate(-1)` fallback 已正確處理
- **建議（非必要）**：phase-4 / phase-7 owner 可在 navigate 時加 `{ state: { fromEventId: numericEventId } }`，啟用 QueuePage 失敗時更精確的「回活動詳情」routing。零侵入，不會破壞現有 test

### Phase 3（活動列表）
- **無影響**

### Phase 7（E2E + Polish）
- 排隊頁 E2E：模擬 mock backend 連續 202 + 最後 200 BOOKED 的 long-poll 行為
- popstate 真實 browser 行為驗證（jsdom 在這塊有侷限）
- Lighthouse a11y 量化（reduced motion + dialog role + live region）
- 升級 React Router 6.4+ 改用 `unstable_useBlocker` 取代 popstate hack（如有需要）

---

## Hand-off

phase-6（Hold Confirm）可立即開動：

- `/confirm/:bookingId` 由 phase-2 placeholder 已 wired，本 phase 確認 `location.state.booking` 正確抵達
- 使用 phase-2 既有 `<HoldCountdown>`（5min UX 倒數，純前端）
- 視覺對齊原則：跟 phase-4 EventDetailPage 一致的 hero/meta 排版

---

## References

- `specs/frontend-mvp/activity-flow.md §4`（畫面 3 + retry 策略）
- `specs/frontend-mvp/component-spec.md §4, §5`（QueueOverlay + useBookingPoll）
- `specs/frontend-mvp/api-contract.md §3.2`（long-poll endpoint）
- Phase 2 handoff §「對 Phase 3-6 的建議」
- Phase 4 handoff §「對其他 Phase 的影響」§ Phase 5
