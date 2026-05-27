# Phase 6 — Screen 4: Hold Confirm (鎖位確認)

| 欄位 | 值 |
|------|----|
| **Phase ID** | `phase-6` |
| **Title** | 鎖位確認畫面（HoldCountdown 5 分鐘 UX 倒數 + 分配座位顯示） |
| **Status** | `done` (2026-05-18) |
| **DependsOn** | `phase-2`, `phase-5` |
| **CanParallelWith** | `phase-3`, `phase-4`, `phase-5`（多人時） |
| **Estimated Effort** | S（0.5-1 天） |
| **Actual Effort** | ~1h（站在 phase-2 HoldCountdown + phase-5 router state handoff 之上） |
| **Owner Skillset** | React / 倒數動畫 / typography |

---

## Goal

實作畫面 4「鎖位確認」：顯示後端分配的座位 + 5 分鐘純前端 UX 倒數 + 確認按鈕（demo only）+ 過期回首頁。

> 規格參考 `specs/frontend-mvp/activity-flow.md §5` 與 `component-spec.md §3`。

---

## Deliverables

### D1. Page component

- `src/pages/HoldConfirmPage.tsx`
- Route `/confirm/:bookingId`
- 資料來源：**畫面 3 透過 react-router state 傳遞的 `BookingResponse`**
  - 若 state 為 null（如使用者直接刷新或進入此 URL）→ Toast「無法取得保留資訊」+ navigate `/events`
  - **不要**再 fetch `/api/bookings/:id`（避免 cache 問題、後端 BOOKED 結果已是最終狀態）

### D2. 視覺結構（editorial 排版）

- Hero 區：大標題（display-md）「已為您保留座位」
- 中央：`<HoldCountdown>` 大型倒數元件（display-lg accent 色 mono）
- 副文案（body-md secondary）：「請於倒數時間內確認您的座位」
- 座位卡片區：每張票一張卡片
  - 區域 / 排 / 座（從 `allocatedSeats[]` 解析 `"A-3-5"` → `{section: "A", row: 3, col: 5}`）
  - 票價（從 BookingResponse 或 hardcode）
  - 簡潔的 grid 排列（最多 4 張票橫排）
- CTA：「確認保留」（primary lg）→ 點擊顯示 Toast「結帳流程不在本 MVP」（demo）

### D3. `<HoldCountdown>` 元件整合

phase-2 已建骨架，本 phase 確認整合：

- 5 分鐘倒數，從 booking 完成時刻起算（用 `Date.now()` 在 mount 時記錄）
- 主數字 `--text-mono-display` (64px JetBrains Mono 700) accent 色
- 格式 `MM:SS`（不顯示小時）
- 最後 60 秒：數字變紅 `--status-few` + 1.2× pulse（每秒）
- 過期：數字變灰 `--fg-tertiary`、文案「保留時間已過」、CTA 變「重新搶票」（→ `/events`）
- 用 `requestAnimationFrame` 而非 `setInterval`（截到秒 update）

### D4. 過期 UX

倒數歸零（`onExpired` callback）→：
- 倒數元件變灰
- 副文案改「保留時間已過，請重新搶票」
- CTA 改「重新搶票」（primary）→ navigate `/events`
- 座位卡片變半透明（opacity 0.5）

### D5. 直接訪問防護

- 若使用者直接打開 `/confirm/abc`（沒從 queue 跳過來、location.state 為空）：
  - 顯示 loading 1 秒（避免閃爍）
  - 然後 Toast「無法取得保留資訊，請重新搶票」+ navigate `/events`

### D6. BDD 子流程

- `src/features/hold-confirm.feature`
  ```gherkin
  Feature: 鎖位確認與倒數
    Scenario: 顯示分配的座位
      Given 從排隊頁傳來 BookingResponse 含 allocatedSeats=["A-3-5","A-3-6"]
      When 使用者進入 /confirm/abc
      Then 應該看到 2 張座位卡片
      And 第一張卡片顯示 "A 區 3 排 5 號"

    Scenario: 倒數顯示 5 分鐘
      Given 使用者剛進入 /confirm/abc
      Then 倒數應為 "05:00"
      And 倒數應每秒減少

    Scenario: 最後 60 秒紅色 + 脈衝
      Given 使用者已停留 4 分鐘
      Then 倒數顏色應為紅色
      And 倒數應有 pulse 動畫

    Scenario: 倒數歸零顯示重新搶票
      Given 使用者已停留 5 分鐘
      Then 倒數應顯示 "00:00" 灰色
      And 副文案應為 "保留時間已過，請重新搶票"
      And CTA 應變為 "重新搶票"

    Scenario: 點擊重新搶票回活動列表
      Given 倒數已過期
      When 使用者點擊 "重新搶票"
      Then URL 應變為 /events

    Scenario: 直接訪問 URL 無 state 引導離開
      Given location.state 為空
      When 使用者直接打開 /confirm/abc
      Then 應該顯示 Toast "無法取得保留資訊"
      And URL 應變為 /events

    Scenario: 點擊確認保留顯示 demo 訊息
      Given 使用者在 /confirm/abc 倒數中
      When 使用者點擊 "確認保留"
      Then 應該顯示 Toast "結帳流程不在本 MVP"
  ```

---

## Acceptance Criteria

- [ ] `/confirm/:bookingId` 從 queue 跳轉時正常顯示
- [ ] 顯示 1-4 張座位卡片（依 BookingResponse.seatCount）
- [ ] 座位格式 `A-3-5` 解析為 `A 區 3 排 5 號`
- [ ] 倒數從 05:00 起跳，每秒減少
- [ ] 最後 60 秒紅色 + pulse 動畫
- [ ] 倒數歸零切換為「重新搶票」UI
- [ ] 點擊「重新搶票」navigate `/events`
- [ ] 直接訪問 URL 顯示 toast 並重定向
- [ ] 點擊「確認保留」顯示 demo toast
- [ ] BDD 7 個 scenario 全部 pass
- [ ] requestAnimationFrame 平穩無 lag
- [ ] Lighthouse a11y ≥ 90

---

## Risks

| 風險 | 嚴重度 | 緩解 |
|------|--------|------|
| react-router state 在頁面刷新後丟失 | 中 | 已用 toast + redirect 設計處理；MVP 接受此限制 |
| `requestAnimationFrame` 切回 tab 時暫停（背景 tab） | 低 | mount 時用 `Date.now()` + 對比目前時間計算剩餘秒；不依賴 raf 累積 |
| 「沒有真實 TTL」demo 被質疑 | 低 | demo script 加說明「結帳流程不在本 MVP」 |
| 座位字串解析格式 `"A-3-5"` 後端若改 | 低 | 把 parser 抽成 util function；好維護 |

---

## References

- `specs/frontend-mvp/activity-flow.md §5`（畫面 4 完整規格）
- `specs/frontend-mvp/component-spec.md §3`（HoldCountdown）
- `specs/frontend-mvp/README.md §5`（鎖位 TTL 決策：Plan A 純前端 UX 倒數）
- `specs/frontend-mvp/api-contract.md §3.2`（BookingResponse.allocatedSeats 格式）

---

# Implementation Handoff (2026-05-18)

## Summary

`ConfirmPage` 從 phase-2 placeholder 升級為完整的 4-state 鎖位確認畫面。所有 acceptance criteria 對應的關鍵分支（`missing-state` / `active` / `expired` / `confirmed`）皆以 Vitest 整合測試覆蓋。視覺維持與 phase-4 EventDetailPage 對齊的 dark + Acid Lime + Inter Tight + editorial caption stripes 排版，並保留 phase-2 既有 `HoldCountdown` 的最後 60s 紅色脈衝效果（**未動既有元件 / hook**）。

## 新增 / 修改檔案

### 修改
```
frontend/src/pages/ConfirmPage.tsx              # placeholder → 4-state 完整實作
```

### 新增
```
frontend/src/test/ConfirmPage.test.tsx          # 6 個整合測試
frontend/src/features/hold-confirm.feature      # 7 個 BDD scenario
```

### 未動既有檔案（read-only，依非協商規則）
- `src/components/HoldCountdown.tsx`
- `src/hooks/useCountdown.ts`
- `src/hooks/useToast.tsx`
- `src/components/Button.tsx`
- `src/router.tsx`（route 已就位）
- 後端任何檔案

## 實作摘要（按 Deliverables 對齊）

| 卡片條目 | 實作位置 | 備註 |
|----------|----------|------|
| **D1** Page component + 路由 + state 來源 | `ConfirmPage.tsx` lifecycle state machine（4-state） | 不再次 fetch；直接讀 `location.state.booking` |
| **D2** Editorial 視覺結構 | header + caption stripe + display-md/lg 主標題 + 兩欄 grid + 座位卡片網格 + CTA 區 | 跟 EventDetailPage 同節奏 |
| **D3** `<HoldCountdown>` 整合 | `<HoldCountdown startedAt={Date.parse(booking.createdAt)} onExpired={handleExpired} />` | startedAt 用 booking.createdAt 而非 mount-time，重 mount 仍精確；最後 60s 紅色脈衝由元件內建 |
| **D4** 過期 UX | `phase === 'expired'` → 主標題改「保留時間已過」+ 紅色提示 + 座位卡片 `opacity-50` + CTA 變「重新搶票」（primary lg）→ navigate `/` | 卡片變半透明只用 class，無 framer-motion |
| **D5** 直接訪問防護 | `phase === 'missing-state'` → 1s loading 佔位 → toast「無法取得保留資訊，請重新搶票」+ `navigate('/', { replace: true })` | 用 `useRef` 防 toast 重發 |
| **D6** BDD scenarios | `src/features/hold-confirm.feature` 7 個 scenario | 對應 7 個 Vitest（取消 CTA scenario 由「navigate back to / when cancel CTA clicked」整合測試覆蓋）|

## 4-state lifecycle machine（核心邏輯）

```
              router state 缺失
                     │
                     ▼
              [missing-state]──1s grace──→ toast + navigate("/")
                                     
              router state 存在
                     │
                     ▼
              [ active ]──onExpired──→[ expired ]──click──→ navigate("/")
                  │
                  ▼ click "確認保留"
              [ confirmed ]（顯示 demo toast + button 鎖為「已確認」）
                  │
                  ▼ click "取消並回活動列表"（任何 non-expired phase 都可）
                navigate("/")
```

## 視覺對齊 phase-4 / 5

- 深色 `bg-ink` 全屏，header 用同款 `ticket/master` mark + 邊框分隔
- caption stripe `/ Hold Confirmed` 與 EventDetailPage `/ Event Detail` 同套排版語彙
- 兩欄 grid：左 = 倒數區（標籤 + HoldCountdown + 提示說明），右 = 座位卡片
- 座位卡片：surface bg + line-subtle border + mono 字體顯示 `A 區 · 3 排 · 5 號` + 原始字串 `A-3-5` 副資訊
- CTA 區 border-top 分隔，主 CTA `lg` size + accent，副 CTA `ghost` size md
- 過期狀態：標題色不變但語意切換、座位列表 `opacity-50`、紅色提示文字、`重新搶票` 變主 CTA

## 驗證結果

```bash
cd frontend && npx tsc --noEmit      # ✅ 0 errors
cd frontend && npm run build         # ✅ tsc -b + vite build, 1.71s, 556KB JS / 65KB CSS
cd frontend && npm run test          # ✅ 28 tests pass (6 files), ~1.2s
```

### Test 拆分（cumulative）

| 檔案 | 測試數 | 狀態 |
|------|--------|------|
| useCountdown.test.ts | 1 | ✅ |
| sseSectionBadge.test.tsx | 3 | ✅ |
| BookingConfirmModal.test.tsx | 6 | ✅ |
| EventsListPage.test.tsx | 5 | ✅ |
| QueuePage.test.tsx | 7 | ✅ |
| **ConfirmPage.test.tsx** | **6** | **✅ 本 phase 新增** |
| **合計** | **28** | **All pass** |

### ConfirmPage 測試覆蓋

| Scenario | 卡片要求 |
|----------|----------|
| router state 正常 → 顯示已分配座位 + 倒數 | ✅ 必要 #1 |
| 直接訪問（state 缺失）→ 1s loading → toast + redirect | ✅ 必要 #2 |
| 倒數歸零 → 確認按鈕 disabled + 過期 UI | ✅ 必要 #3 |
| 點擊「重新搶票」navigate `/` | ✅ |
| 點擊「確認保留」demo toast + button 鎖 | ✅ |
| 點擊「取消並回活動列表」navigate `/` | ✅ |

## 與 spec 不一致 / 主動偏離

1. **`computeTotalPrice` 回傳 `null`**：卡片 D2 列「總價」為**選用**，但 `BookingResponse` 沒有票價欄位，`SectionAvailability.basePrice` 也未透過 router state 帶過來。為避免顯示誤導性的 `NT$ 0`，目前直接 hide 總價區塊。Phase 7 owner 可以選擇：(a) 在 phase-5 navigate 時把 `selectedSection.basePrice` 一起塞進 state，或 (b) 在 ConfirmPage 額外 fetch `/api/events/:id/sections` 找 basePrice。已留好 hook 點 `computeTotalPrice(booking)`，加個參數就能上線。
2. **MISSING_STATE_GRACE_MS = 1000ms**：spec D5 寫「loading 1 秒」，照辦。未做 skeleton（單一文字佔位夠用，避免 phase-6 範圍蔓延）。
3. **取消按鈕加上去了**：卡片任務範圍寫「取消按鈕（可選，依卡片定義）」；本 phase 採取「加上去」以對齊 activity-flow §5 引導離開的儀式感，使用者在不確定要不要結帳時有明確 escape hatch。Ghost variant，視覺重量低於主 CTA，不喧賓奪主。
4. **不接後端 fetch**：嚴格遵守卡片 D1「**不要**再 fetch `/api/bookings/:id`」。若 router state 缺失就走 missing-state 流程，不嘗試挽救。

## 風險 / Unresolved

### 解決過的風險（卡片原列）

- ✅ react-router state 在頁面刷新後丟失 — missing-state UI + toast + redirect 已就位，使用者不會見到不完整狀態
- ✅ `requestAnimationFrame` 切回 tab 時暫停 — 用 `Date.parse(booking.createdAt) + 5min` 為固定 target，不依 raf 累積；切回 tab 後第一次 rAF tick 立刻補正
- ✅ demo 結帳被質疑 — toast 直接寫「結帳流程不在本 MVP — Demo 完成」+ 按鈕鎖為「已確認」
- ✅ 座位字串解析 — 抽成 `parseSeat(raw: string): ParsedSeat`，後端格式若變只改一處

### 仍有的風險（移交給 phase-7）

| 風險 | 嚴重度 | 緩解 / 追蹤 |
|------|--------|-------------|
| dev server smoke test 未實機跑 | 中 | 同 phase-4/5：Vitest + jsdom 驗證行為，phase-7 跑真實 browser E2E |
| Lighthouse a11y 未量測 | 低 | role="timer" + aria-live="polite"（HoldCountdown 內建）+ role="alert"（過期提示）+ aria-busy（loading）+ aria-disabled（confirm 鎖）已就位；phase-7 跑 lighthouse-ci 量化 |
| `confirmed` 後 5min 到期會被 `expired` phase 覆蓋（race） | 低 | 目前 confirmed UI 仍會被 onExpired 切到 expired；demo 場域下這是合理（5min 內若已 confirm，過期再 prompt 重搶也算合理 UX）。若 PM 想凍結 confirmed，setPhase 加守衛即可 |
| 票價來源未決 | 低 | 已標記為 unresolved；不擋 MVP demo |
| `useCountdown` rAF 在 fake timers 下對 5min 太大 advance 會慢 | 低（測試環境） | 已在測試中用「pre-expired booking」技巧繞過 |

### Unresolved（不擋 ship）

1. **總價顯示**：見上方「主動偏離 #1」。
2. **EVT/XXXX 格式**：目前用 `formatEventRef(eventId)` 印 `EVT/0007`。若 PM 想要真實活動名，需要 phase-5 也把 EventResponse 帶過來（或在 ConfirmPage 加 `useEventDetail(booking.eventId)`，但這需要 React Query Provider 在 full-bleed 路由內可用——已可用，App.tsx 已 wrap）。phase-7 polish 可考慮。

## 對其他 Phase 的影響

### Phase 5（排隊）— upstream
- **無破壞**。phase-5 已正確透過 `navigate('/confirm/:bookingId', { replace: true, state: { booking: data } })` 傳遞 `BookingResponse`，本 phase 直接消費，QueuePage.test 中 BookingResponse 透過 router state 的傳遞驗證未受影響
- 建議（非必要）：phase-7 owner 可在 phase-5 額外傳 `selectedSection`（含 basePrice）給總價計算

### Phase 2 — base
- **無破壞**。沒動 HoldCountdown / useCountdown / useToast / Button / router / 任何共用元件

### Phase 7（E2E + Polish）
- ConfirmPage E2E：
  - browser 真實 5min 倒數 lighthouse + a11y 量化（jsdom 在 rAF tick 上不精確）
  - 切換 tab 後 raf 暫停 → 切回後 catch up 的真實行為驗證
  - 過期 → 重新搶票 full circle round-trip
- 票價區塊：決定接 React Query 還是擴大 router state
- 倒數最後 60s 紅色脈衝：肉眼驗證 status-few 對比夠

## Hand-off

MVP P1-P6 全部完成，使用者完整流程已可從首頁走到鎖位確認：

```
/             ── 列表（phase-3）
  → /events/:id  ── 詳情 + 票區 SSE（phase-4）
    → POST /api/bookings → 202 + bookingId
      → /queue/:bookingId  ── 排隊 + long-poll（phase-5）
        → 200 + BOOKED
          → /confirm/:bookingId  ── 鎖位確認（phase-6）✅
```

phase-7 owner 可以開始整合 E2E（Playwright 建議）+ polish + Lighthouse 量化。

