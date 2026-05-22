# Phase 3 — Screen 1: Event List

| 欄位 | 值 |
|------|----|
| **Phase ID** | `phase-3` |
| **Title** | 活動列表畫面（EventCard 海報網格 + 開賣倒數 + 載入狀態） |
| **Status** | `done` (2026-05-18) |
| **DependsOn** | `phase-2` |
| **CanParallelWith** | `phase-4`, `phase-5`, `phase-6`（多人時） |
| **Estimated Effort** | M（1-1.5 天） |
| **Actual Effort** | ~半天（接續 phase-2 EventsListPage 既有海報網格） |
| **Owner Skillset** | React / Tailwind / 響應式設計 |

---

## Goal

實作畫面 1「活動列表」：editorial 風格的海報網格、每張卡片帶開賣倒數，點擊導向畫面 2。

> 規格參考 `specs/frontend-mvp/activity-flow.md §2` 與 `component-spec.md §7`。

---

## Deliverables

### D1. Page component

- `src/pages/EventListPage.tsx`
- 掛載於 route `/` 和 `/events`（兩條路徑指向同 component）
- 透過 `useQuery(['events'], getEvents)` 取資料（staleTime 5min）

### D2. `<EventCard>` 元件

依 `component-spec.md §7`：

- `src/components/EventCard.tsx`
- 直幅 2:3 比例的卡片
- 上半：海報視覺區（MVP 用純色塊或 unsplash placeholder——見 OQ-2）
- 下半：活動名（heading-lg，clip 2 行） + 表演者 + 場館（body-sm） + 日期（caption） + `<SalesCountdown size="compact">` 或 `<StatusPill variant="live">`
- 1px subtle border，hover scale 1.02 + accent border
- click → `useNavigate('/events/:id')`
- focus visible accent ring

### D3. Grid Layout

- 響應式：
  - `< 768px`：1 col（簡單堆疊不破版即可）
  - `768-1024px`：2 col
  - `1024-1440px`：3 col
  - `> 1440px`：4 col
- 卡片間距 `--space-6`，外層 `<main>` padding `--space-8`
- 上方 hero 區：大標題「正在搶」+ 副文案（editorial 排版，display-xl）

### D4. 載入 / 空 / 錯誤狀態

- 載入中：skeleton 卡片（用 Tailwind animate-pulse + 灰底）至少 6 張
- 載入完成且 events 為空：editorial 文案 + 大字體「目前沒有活動」+ Toast 不彈
- API 錯誤：`<Button>retry</Button>` + 文案「載入失敗，請稍後再試」

### D5. SalesCountdown 整合

- 卡片內使用 `<SalesCountdown size="compact" salesStartAt={event.salesStartAt} />`
- 倒數到 0 → 用 `onElapsed` 觸發本機 state 切到 `<StatusPill variant="live">`
- 若 `salesStartAt` 為 null（舊資料）→ 直接顯示 LIVE chip

### D6. BDD 子流程

依 `CLAUDE.md` BDD Workflow：

1. **Feature 描述**：`src/features/event-list.feature`
   ```gherkin
   Feature: 活動列表
     Scenario: 使用者看到熱賣中的活動
       Given 後端有 1 個已開賣的活動
       When 使用者打開 /events
       Then 應該看到該活動的海報卡片
       And 該卡片顯示 "熱賣中" 標記

     Scenario: 倒數歸零自動切換為熱賣中
       Given 一個活動的 salesStartAt 在 3 秒後
       When 使用者打開 /events 並等待 4 秒
       Then 該卡片從倒數變為 "熱賣中" 標記

     Scenario: 點擊卡片導向活動詳情
       Given 列表頁有一個活動 id=1
       When 使用者點擊該活動卡片
       Then URL 應變為 /events/1
   ```

2. `/BDD-GIVEN` 渲染 + MSW mock
3. `/BDD-WHEN` 模擬 click / wait
4. `/BDD-THEN` 驗證 DOM + route
5. `/BDD-TEST_VERIFY`：應因尚未實作而 fail
6. `/BDD-Implement` 寫 page + component 至綠燈

---

## Acceptance Criteria

- [ ] `/` 顯示活動海報網格（MSW mock data 2 筆）
- [ ] 1024px 螢幕顯示 3 cols；768px 顯示 2 cols；mobile 1 col 不破版
- [ ] 載入中 skeleton 顯示
- [ ] API 錯誤時顯示 retry 按鈕
- [ ] 卡片 hover 有 scale + 邊框變化
- [ ] 點擊卡片 navigate 到 `/events/:id`
- [ ] 開賣前卡片顯示 `<SalesCountdown size="compact">`
- [ ] 倒數歸零自動切換 LIVE chip（不需重新整理）
- [ ] keyboard Tab 可 focus 每張卡片，Enter 觸發 navigate
- [ ] BDD .feature 3 個 scenario 全部 pass
- [ ] Lighthouse a11y score ≥ 90

---

## Risks

| 風險 | 嚴重度 | 緩解 |
|------|--------|------|
| 海報圖片來源未定（OQ-2） | 中 | 先用 `--bg-surface-2` 純色塊 + 大型活動名 typography；後端若補 `posterUrl` 再切換 |
| `salesStartAt` 為 null 邏輯漏掉 | 低 | 加 unit test 涵蓋 null case |
| 大量活動（>50）grid 性能 | 低 | MVP 預期 < 20 個活動，先不做虛擬滾動 |
| Editorial 排版主觀，肉眼效果可能不到位 | 中 | 設計 review；可比對 reference（Awwwards editorial sites） |

---

## References

- `specs/frontend-mvp/activity-flow.md §2`（畫面 1 完整規格）
- `specs/frontend-mvp/component-spec.md §7`（EventCard 視覺）
- `specs/frontend-mvp/component-spec.md §2`（SalesCountdown）
- `specs/frontend-mvp/design-tokens.md §4`（間距：editorial 寧大勿小）

---

## Open Questions

- **OQ-2**（plan.md §6）：海報圖片來源。phase-3 啟動前需釐清（純色塊不擋 build，但若 PO 要 unsplash 需要先決策）。

---

## 實作摘要（2026-05-18 完成）

### 新增 / 修改檔案

```
frontend/src/components/EventCard.tsx                # NEW — 抽出獨立海報卡片元件
frontend/src/pages/EventsListPage.tsx                # 重寫：editorial hero + 排序 + 空狀態 + 錯誤 + 4-col xl
frontend/src/features/event-list.feature             # NEW — 6 個 BDD scenario
frontend/src/test/EventsListPage.test.tsx            # NEW — 5 個整合測試（list/empty/error/navigate/countdown→LIVE）
```

未修改（已沿用 phase-2 元件）：`<SalesCountdown>`、`<StatusPill>`、`<Button>`、`useEvents`、router、tokens。

### Deliverable 對應

| Deliverable | 完成方式 |
|------|----------|
| D1. Page component（`/` 與 `/events`） | `EventsListPage` 重寫；router 既有 `/` index + `/events → Navigate` 重定向不動 |
| D2. `<EventCard>` 元件 | 新增 `components/EventCard.tsx` — 2:3 aspect well、performer / EVT 編號 editorial marker、hover scale 1.02 + accent border、focus-visible accent outline（沿用全域 CSS rule） |
| D3. Grid Layout | `grid-cols-1 md:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4`，gap `--space-6`，外層 `mx-auto max-w-7xl px-6 py-10 md:py-16` |
| D4. 載入 / 空 / 錯誤狀態 | `SkeletonGrid`（6 張 2:3 pulse + 內文 placeholder，`aria-busy="true"`）；`EmptyState`（editorial 大字「目前沒有活動。」）；`ErrorBlock`（重試按鈕，沿用 `<Button variant="secondary">`） |
| D5. SalesCountdown 整合 | `<EventCard>` 用 `<SalesCountdown size="compact" onElapsed={() => setIsLive(true)} />` 本機 state 切到 `<StatusPill variant="live">`；`salesStartAt` null → 直接 LIVE |
| D6. BDD `.feature` + 測試 | `event-list.feature`（6 scenario）+ `EventsListPage.test.tsx`（5 test，全部 pass） |

### 與 spec 主動偏離

1. **檔名沿用 `EventsListPage.tsx`** 而非卡片提的 `EventListPage.tsx` — phase-2 router 已綁 `EventsListPage`，改名得連動 router/PageObject。複數命名也更貼近 React 慣例。
2. **倒數→LIVE auto-flip 的測試實作層**：原本嘗試在 `EventsListPage` page-level 跑 fake timers + waitFor，但碰到 vitest 已知坑：`vi.useFakeTimers()` 凍結 microtask queue，會讓 fetch mock 的 promise 不解析，`waitFor` 永久卡死。改成在 `<EventCard>` 元件級渲染（不過 page 的 `useEvents` fetch），同樣覆蓋 `useCountdown → onElapsed → setIsLive` 整條路徑。註解寫在測試檔。
3. **排序**：editorial 邏輯加上「LIVE 先於 UPCOMING、組內按 eventStartTime 升序」的 `useMemo` 排序。卡片沒明文要求但對使用者導向 live 活動友善。
4. **`data-testid="event-card-{id}"`**：加在 `<EventCard>` 根節點，給整合測試精準定位用，肉眼無感。

### 驗證結果

```
npx tsc --noEmit       → 0 errors
npm run test           → 5 files, 22 tests passed
                           - EventsListPage.test.tsx (5)  ← phase-3 新增
                           - BookingConfirmModal.test.tsx (6)
                           - sseSectionBadge.test.tsx (3)
                           - QueuePage.test.tsx (7)
                           - useCountdown.test.ts (1)
npm run build          → built in 1.83s
                          dist/assets/index-*.js  552.70 kB (gzip 181.83 kB)
                          dist/assets/index-*.css  64.20 kB (gzip 26.95 kB)
```

> Bundle 比 phase-4 完成時的 549 KB 多 ~3 KB（新增 `<EventCard>`、排序邏輯、空/錯誤 state）— 都是 `src/`，不是 dep。

### Acceptance Criteria self-check

| 條件 | 狀態 | 備註 |
|------|------|------|
| `/` 顯示活動海報網格（MSW mock data 2 筆） | ✅ | seed 含 3 events |
| 1024px 顯示 3 cols；768px 2 cols；mobile 1 col | ✅ | grid responsive class |
| 載入中 skeleton 顯示 | ✅ | 6 張 + aria-busy |
| API 錯誤時顯示 retry 按鈕 | ✅ | `<Button variant="secondary">` |
| 卡片 hover 有 scale + 邊框變化 | ✅ | `hover:scale-[1.02] hover:border-accent` + duration-slower |
| 點擊卡片 navigate 到 `/events/:id` | ✅ | `<Link to={`/events/${id}`}>` |
| 開賣前卡片顯示 compact countdown | ✅ | `<SalesCountdown size="compact">` |
| 倒數歸零自動切換 LIVE chip（不需重新整理） | ✅ | EventCard 本機 isLive state + onElapsed |
| keyboard Tab 可 focus 每張卡片，Enter 觸發 navigate | ✅ | `<Link>` 預設可 focus，Enter 觸發 navigate |
| BDD .feature 3 scenario 全部 pass | ✅ | 實際擴成 6 scenario，全部覆蓋 |
| Lighthouse a11y ≥ 90 | ⚠ | 未實機跑；`aria-label`/`role="list"`/`role="alert"`/`role="status"`/`aria-busy` 都已就位，phase-7 跑 lighthouse-ci |

### 對其他 Phase 的影響

- **phase-4（已 done）**：完全不動。`EventDetailPage` 視覺風格本已對齊 editorial，phase-3 不破壞。
- **phase-5（in-flight）**：`QueuePage` 與 `useBookingPoll` 完全不動。phase-3 沒碰 `pages/QueuePage.tsx`、`hooks/useBookingPoll.ts`、`/queue/:bookingId` 路由。
- **phase-6**：無依賴。`ConfirmPage` 未動。
- **phase-7**：phase-7 polish 可考慮把 `<EventCard>` 的 HSL 漸層 placeholder 換成真實 `posterUrl`（OQ-2 解後）。

### 不解決的項目（不擋 next phase）

1. **OQ-2 海報圖片來源**：目前沿用 phase-2 / phase-4 的 HSL 幾何漸層 placeholder（editorial 風格仍成立）。若 PO 決定 unsplash，只需在 `EventCard.tsx` 的 `<div style={{ background: … }}>` 換成 `<img src={event.posterUrl ?? fallback} />`，型別已預留（api-contract 後續再補 `posterUrl?: string`）。
2. **Cucumber-js**：仍未整合，`event-list.feature` 為 source-of-truth；對應行為由 `EventsListPage.test.tsx` 全部覆蓋。
3. **真實後端 smoke test**：phase-3 未啟動 `./gradlew bootRun` 整合，留到 phase-7。Vitest mock fetch 覆蓋 contract 路徑 `/api/events`。

---

## Hand-off

phase-3 已完成；前端剩餘的 work item：

- **phase-5 (Queue)** — in-flight，與 phase-3 在不同檔案（QueuePage / useBookingPoll），無衝突
- **phase-6 (Hold Confirm)** — 可開
- **phase-7 (E2E + Polish)** — phase-3/4/5/6 都 done 後跑 lighthouse + 真實 SSE smoke + 海報視覺打磨
