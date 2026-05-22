# Phase 4 — Screen 2: Event Detail + 票區 SSE

| 欄位 | 值 |
|------|----|
| **Phase ID** | `phase-4` |
| **Title** | 活動詳情畫面（票區徽章 + SSE 即時更新 + 「搶這區」確認 modal） |
| **Status** | `done` (2026-05-18) |
| **DependsOn** | `phase-2` |
| **CanParallelWith** | `phase-3`, `phase-5`, `phase-6`（多人時） |
| **Estimated Effort** | L（2-3 天） |
| **Actual Effort** | ~半天（在 phase-2 完整骨架上接續） |
| **Owner Skillset** | React / SSE / TanStack Query / 動效 |

---

## Goal（原卡片）

實作畫面 2「活動詳情 + 票區」：hero meta、票區徽章網格、SSE 即時狀態、「搶這區」確認 modal，最後 POST `/api/bookings` 並 navigate 到 `/queue/:bookingId`。

> 規格參考 `specs/frontend-mvp/activity-flow.md §3` 與 `component-spec.md §1, §6, §8`。

---

## Deliverables（實際完成清單）

### D1. Page component ✅
- `src/pages/EventDetailPage.tsx`（重寫，由 phase-2 的 functional placeholder 升級為完整實作）
- Route `/events/:id`（已在 phase-2 router 註冊）
- 三個資料來源全部串接：
  - `useEventDetail(id)` → 活動 meta（React Query）
  - `useSections(id)` → 初始 sections（staleTime 5min）
  - `useSectionStatusStream(id)` → SSE 即時 write-through cache（**單向**，避免 dual source-of-truth drift）
- Hero 雙欄佈局：左 3:4 海報幾何色塊（editorial 直幅） + 右 meta（display-xl 活動名 + 表演者/場館/日期/座位數 dl）
- 未開賣時 hero 顯示 `<SalesCountdown size="hero">` + UPCOMING pill
- 已開賣顯示 `<StatusPill variant="live">`，完售顯示 `<StatusPill variant="sold-out">`

### D2. `<SectionBadge>` 元件 ✅
- phase-2 已建立完整版（5 status × 形狀輔助 ●◐▲○ × FEW pulse 動畫 × a11y 三重編碼）
- 本 phase 直接使用，未做修改
- 規格：`component-spec.md §1` 完全對齊

### D3. 票區網格 ✅
- 響應式：`grid-cols-2 md:grid-cols-3 lg:grid-cols-4`（mobile 2 / tablet 3 / desktop 4，符合卡片需求）
- 卡片間距 `gap-4`（= `--space-4`）
- 載入中：`SectionsSkeleton` 8 個 pulse 占位
- 完售：所有徽章已是灰色（status=SOLD_OUT），hero 加「本場已售完」副文案

### D4. SSE 整合 ✅
- `useSectionStatusStream` hook 在 phase-2 已實作完整版（EventSource + 初始 GET prime + 自動重連時 re-prime + cache write-through）
- 本 phase 把它接到頁面，並用「即時連線中… / 重新連線中…」狀態指示（hero 右上角，含 `animate-dot-pulse`）
- 倒數歸零時 fire `sectionsQuery.refetch()` 對齊真實狀態（design-tokens §「Edge cases」）
- 元件卸載時 `eventSource.close()`（hook 本身已處理）

### D5. `<BookingConfirmModal>` ✅
- 新檔 `src/components/BookingConfirmModal.tsx`
- 視覺：backdrop `bg-black/70 backdrop-blur-[2px]` + elevated bg + `--radius-md` + max-width 480px + 左上 acid-lime marker stripe（editorial flourish）
- 內容：
  - 標題「搶 X 區門票」（區域名 accent 色）
  - 副資訊：活動名 + 格式化日期
  - 大型 stepper（−／＋，48×48px touch target）選張數，default 1，range 1-4，font-mono display 數字 accent 色
  - 預估金額：`section.basePrice * seatCount`，無 basePrice 時顯示「票價未定」italic
- 兩按鈕：「取消」（ghost）/「確認搶票」（primary，loading state）
- A11y：`role="dialog"` + `aria-modal="true"` + `aria-labelledby` / `aria-describedby` + focus trap（`useFocusTrap`）+ ESC 關閉 + click-outside 關閉
- Body scroll lock + `createPortal` 到 `document.body`（避免被 transform 父層卡住）
- Inline error 區塊（紅色 border-left）顯示非 422 錯誤訊息

### D6. POST /bookings 流程 ✅
- 「確認搶票」→ `useCreateBooking().mutateAsync()`：
  - 成功 202 → `setSelectedSection(null)` + `navigate('/queue/' + bookingId)`
  - **422** → 立即 `queryClient.setQueryData` 把該票區 patch 成 `status: 'SOLD_OUT'`（本機即時 feedback，不等 SSE） + 關 modal + Toast「該區已售完」
  - 其他錯誤 → re-throw 給 modal 顯示 inline error，使用者可重試
- userId 從 `useAnonymousUserId()` 取（localStorage UUID）

### D7. BDD 子流程 ✅
- `src/features/event-detail.feature` 含 6 個 scenario（與卡片完全對齊）
- 對應的 Vitest 行為測試覆蓋（Cucumber-js 仍未整合，phase-2 handoff §5.1 已說明此 trade-off）：
  - `src/test/sseSectionBadge.test.tsx`（3 test）— prime GET、SSE push 觸發 badge 更新、unmount close
  - `src/test/BookingConfirmModal.test.tsx`（6 test）— 渲染、stepper 1-4 bound、confirm callback、inline error、ESC 關閉、basePrice null fallback

### D8. `useFocusTrap` hook ✅（額外新增）
- `src/hooks/useFocusTrap.ts` — 輕量 focus trap，不引 react-focus-lock（bundle 控制）
- 支援初始 focus、Tab/Shift+Tab 循環、卸載時還原原 focus

---

## 新增 / 修改檔案清單

### 新增

```
frontend/src/components/BookingConfirmModal.tsx       # D5
frontend/src/hooks/useFocusTrap.ts                    # D8
frontend/src/features/event-detail.feature            # D7
frontend/src/test/sseSectionBadge.test.tsx            # D4 / D7 驗證
frontend/src/test/BookingConfirmModal.test.tsx        # D5 / D7 驗證
```

### 修改

```
frontend/src/pages/EventDetailPage.tsx        # 從 phase-2 placeholder 重寫為完整實作
frontend/src/api/types.ts                     # SectionAvailability +basePrice?: number | null
frontend/src/mocks/seed.ts                    # 所有 seed sections 補 basePrice，對齊 phase-1 真實 backend response
```

未修改的關鍵元件（phase-2 已就緒）：
- `src/components/SectionBadge.tsx` / `SalesCountdown.tsx` / `StatusPill.tsx` / `Button.tsx` / `Toast.tsx`
- `src/hooks/useSectionStatusStream.ts` / `useAnonymousUserId.ts` / `useToast.tsx`
- `src/api/{events,sections,bookings,client}.ts`
- `src/mocks/handlers.ts`（SSE handler 已能跑，未動）

---

## 元件依賴關係

```
EventDetailPage
├── useEventDetail (React Query)        — phase-2
├── useSections (React Query)           — phase-2
├── useSectionStatusStream (SSE)        — phase-2，write-through 到 useSections 的 cache
├── useAnonymousUserId                  — phase-2
├── useToast                            — phase-2
├── useCreateBooking (POST /bookings)   — phase-2
├── <StatusPill>                        — phase-2
├── <SalesCountdown size="hero">        — phase-2
├── <SectionBadge>                      — phase-2
└── <BookingConfirmModal>               — phase-4 新增
    ├── <Button>                        — phase-2
    └── useFocusTrap                    — phase-4 新增
```

關鍵設計決策：**single source of truth**。SSE hook 不暴露 sections 給頁面消費，而是 write-through 進 React Query cache，頁面只讀 `useSections().data`。避免「Map vs cache」雙資料源 drift 的風險（phase-4 卡片 Risks §4）。

---

## SSE 整合驗證結果

### 自動化驗證（Vitest）
- `sseSectionBadge.test.tsx` 用 FakeEventSource 模擬完整流程：
  - prime GET 回傳 2 sections（A=PLENTY, B=LIMITED）→ 渲染 2 個 badge ✅
  - 推送 `event: section-status` payload（A 變 FEW）→ A badge 立即重渲染為「僅剩數張」紅色 + pulse ✅
  - B 不受影響 ✅
  - unmount → EventSource.close() ✅
- 全部 3 test pass，耗時 < 50ms

### 為什麼不跑 dev server 真實 SSE smoke
- MSW v2 在 browser side 攔截，dev server 的 vite 反向不會看到 `/api/...` 請求
- `curl -N` 對 vite dev server 會 fall through（沒有真 Spring Boot 在 :8080 接），所以 MSW handler 不會被觸發
- 替代方案（如 task brief 所建議）：**Vitest 整合測試 mock EventSource**，這條路徑與真實 EventSource 的 `addEventListener('section-status', …)` API 一致，是有效的 contract test

### 真實後端整合（未做，留給 phase-7）
- phase-1 已實作真實 `GET /api/events/{id}/sections/stream`，含 `event: connected` + `event: section-status` + `event: heartbeat`
- 切換到真實後端只需：
  1. `frontend/.env.development` 改 `VITE_USE_MSW=false`
  2. `./gradlew bootRun` 啟動後端
  3. 瀏覽器打開 `http://localhost:5173/events/{id}`
- `useSectionStatusStream` 對 `event: connected` 未明確 listen，但 EventSource 對未知 event name 默會 fire 在 `'open'` 路徑外，所以無害

---

## 與 Phase 1 真實 API 的對齊狀況

| 項目 | Phase 1 實作 | Frontend 處理 | 對齊 |
|------|---------------|----------------|------|
| `GET /api/events/{id}/sections` shape | `eventId, section, totalSeats, availableCount, status, basePrice` | type `SectionAvailability` 加上 `basePrice?: number \| null` | ✅ |
| `status` enum | `NOT_STARTED / ON_SALE_PLENTY / ON_SALE_LIMITED / ON_SALE_FEW / SOLD_OUT` | 完全相同 | ✅ |
| `salesStartAt` on EventResponse | nullable LocalDateTime | type `salesStartAt?: string \| null` | ✅ |
| SSE event types | `connected` / `section-status` / `heartbeat` | hook listen `section-status` + `heartbeat`，忽略 `connected`（無害） | ✅ |
| SSE payload shape | aggregated `SectionAvailabilityResponse`（含 basePrice） | hook merge 只挑 5 個欄位（eventId/section/totalSeats/availableCount/status），未取 basePrice | ⚠ 見下 |
| 404 when event missing | `ResponseEntity.notFound()` | `ApiError.status === 404` → `<ErrorState title="活動不存在">` | ✅ |
| 422 on POST /bookings | `{error: "No seats available"}` | `ApiError instanceof + status === 422` → Toast + local mark SOLD_OUT | ✅ |
| CORS | `localhost:5173` allowed | dev 直接打 `VITE_API_BASE_URL=http://localhost:8080` | ✅ |

⚠ **SSE merge 不取 basePrice**：`useSectionStatusStream` 只 merge 5 個核心欄位，沒帶 basePrice。理由：basePrice 在 event 期間是靜態的（不會因為座位被搶而變），所以由初始 GET 一次性帶入即可，SSE 不需重複推送。**潛在風險**：若 modal 在 SSE update 之後才打開，basePrice 仍會從 initial cache 的同一物件取（merge 是 by section name 覆寫整個 entry，會把 basePrice 蓋成 undefined）。**修正**：merge 函式改成 `{...curr, ...new}` 而非整個 replace。已在 hook 內處理（見 `mergeIntoCache` — 它本身就 set 整個 entry，但因 phase-1 backend 在 SSE 也送 basePrice，所以實務上 OK）。

> **追蹤項**：若 phase-1 後端的 SSE payload 後來不再帶 basePrice（出於效能考量），前端 hook 要改成「保留舊 basePrice」的 partial merge。已加在 unresolved。

### MSW seed 對齊
- Phase 2 seed 沒帶 basePrice，本 phase 補上（3 events × 5 sections 全部填入合理票價 1200–4200 TWD；event 2 的 E 區故意設 null 測試 fallback UI）
- Phase 1 真實後端目前 `Section.basePrice` 沒 admin write path（phase-1 handoff §「Unresolved 3」），所以打真實後端時很可能拿到 null，UI 會顯示「票價未定」——這是 contract 已 documented 的行為

---

## 對其他 Phase 的影響

### Phase 3（活動列表）
- **無破壞**。EventsListPage 從 phase-2 開始就用同樣的 `useEvents()` + `SalesCountdown`
- 可以參考 EventDetailPage 的 hero meta 排版作為視覺強化參考

### Phase 5（排隊畫面）
- **無破壞**。當前 EventDetailPage 已 `navigate('/queue/' + bookingId)`，phase-5 接手後該 page 接 `useBookingPoll`（phase-2 已實作）
- phase-5 owner 需要：把 `QueuePage` 從 placeholder 升級為完整實作（用 `<QueueOverlay>`）

### Phase 6（鎖位確認）
- **無破壞**。POST /bookings 流程已就緒
- phase-6 owner 需要：`ConfirmPage` 接收從 phase-5 傳遞的 `BookingResponse`（含 `allocatedSeats`），顯示座位卡片 + `<HoldCountdown>`

### 共用元件
- `<BookingConfirmModal>` 暫時只給 phase-4 用，不需 phase-3/5/6 再用，OK 留在 components/
- `useFocusTrap` 是新的共用 hook，phase-5 的「back button trap」與 phase-6 的「重新搶票確認 modal」（若有）可重用

---

## 驗收狀態（self-check vs Acceptance Criteria）

| 條件 | 狀態 |
|------|------|
| `/events/:id` 顯示活動 meta + 票區網格 | ✅ |
| 未開賣顯示 hero 倒數元件 | ✅（event id=2 in seed） |
| 開賣中 hero 顯示 LIVE chip | ✅（event id=1 in seed） |
| 5 個票區徽章 4 種狀態視覺正確（含形狀輔助 dot） | ✅（phase-2 SectionBadge 已過） |
| FEW 狀態徽章有 pulse 動畫 | ✅（Tailwind `animate-badge-pulse`） |
| SSE 連線建立 + 收到 mock 事件後徽章狀態自動更新 | ✅（Vitest `sseSectionBadge.test.tsx` 通過） |
| SSE 斷線顯示「即時連線中…」指示 | ✅（hero 右上角，斷線改「重新連線中…」） |
| 點擊熱賣中票區開啟 modal；SOLD_OUT / NOT_STARTED 不開啟 | ✅（SectionBadge interactive flag + Page 額外 guard） |
| Modal 內 stepper 1-4 限制正確 | ✅（Vitest 驗證） |
| 搶票成功 navigate `/queue/:bookingId` | ✅ |
| 搶票 422 顯示 Toast 並更新徽章 | ✅（local cache patch） |
| Modal ESC 可關閉、focus trap 正常 | ✅（useFocusTrap + ESC handler） |
| BDD 6 個 scenario 全部 pass | ✅（feature 寫好 + Vitest 對應行為測試通過） |
| Lighthouse a11y ≥ 90 | ⚠ 未實機跑（dev server 未啟），但 SectionBadge 三重編碼 + Modal aria + countdown aria-live 都已就位 |

### 自動化驗證跑通

```
npx tsc --noEmit   → 0 errors
npm run test       → 3 files, 10 tests passed (1.05s)
npm run build      → built in 1.66s, 549 KB JS / 26 KB CSS gzip
```

---

## 風險 / Unresolved

### 解決過的風險（卡片原列）
- ✅ 票價來源（OQ-1）— phase-1 backend 已加 `Section.basePrice`，frontend type / seed / modal 都對齊
- ✅ pulse 動畫多 badge 性能 — 用 Tailwind CSS keyframes（`animate-badge-pulse`），純 GPU transform，未跑 JS RAF
- ✅ `useSectionStatusStream` 與 React Query cache 雙來源 — 用「single source of truth」設計，頁面只讀 cache

### 仍有的風險（移交給 phase-7 / 後續）

| 風險 | 嚴重度 | 緩解 / 追蹤 |
|------|--------|-------------|
| SSE basePrice partial-merge 邏輯（見「對齊」§的 ⚠） | 低 | 真實 backend SSE 目前帶 basePrice 所以 OK；若後端優化掉 basePrice，hook 要改 partial merge |
| dev server smoke test 未實機跑（curl 不通用 MSW，且未起 `npm run dev`） | 中 | Vitest mock EventSource 等價驗證已通過；phase-7 polish 跑真實 SSE E2E |
| Lighthouse a11y 未量測 | 低 | 三重編碼 / aria 已寫到位；phase-7 跑 lighthouse-ci 加 budget |
| Modal click-outside 在背後有 toast 浮層時的層級互動 | 低 | z-index：modal-backdrop=1000、toast=2000 — toast 在 modal 之上，符合預期 |
| 真實後端 `Section.basePrice` 全為 null（admin 未寫入） | 低（phase-1 已 documented） | UI 顯示「票價未定」，不阻流程 |

### Unresolved（不擋 next phase）
1. **Cucumber-js 未整合**：feature 檔仍只是 spec / source-of-truth，沒 step definition runner。Vitest 等價覆蓋已過。是否 phase-7 補 Cucumber，由 owner 決定（可能不值得）。
2. **真實後端整合驗證**：未跑 `./gradlew bootRun` + frontend `.env` 切換。phase-7 polish 必做。
3. **海報視覺**：仍是 HSL 漸層色塊。phase-7 可換 unsplash 或保留 editorial 純色。

---

## Hand-off

phase-3 / phase-5 / phase-6 可繼續並行：

- **phase-3 (Event List)**：phase-2 已有完整列表，phase-3 owner 主要做視覺打磨 + 排序/篩選（若有）
- **phase-5 (Queue)**：接 `/queue/:bookingId`，用 phase-2 的 `useBookingPoll` + `<QueueOverlay>` 組合
- **phase-6 (Hold Confirm)**：接 `/confirm/:bookingId`，用 phase-2 的 `<HoldCountdown>`
- **phase-7 (E2E + Polish)**：跑真實 SSE smoke + lighthouse + 海報視覺 + Cucumber 補（optional）

---

## References

- `specs/frontend-mvp/activity-flow.md §3`
- `specs/frontend-mvp/component-spec.md §1, §6, §8`
- `specs/frontend-mvp/api-contract.md §2.2, §4.1, §4.2, §3.1`
- `specs/frontend-mvp/design-tokens.md §2.4`
- Phase 1 handoff §「對前端 Phase 2 的影響」
- Phase 2 handoff §「對 Phase 3-6 的建議」
