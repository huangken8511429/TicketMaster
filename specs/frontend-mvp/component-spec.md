# Frontend MVP — Component Spec

**Stage**: `/spec`
**4 個畫面的關鍵元件規格**

---

## 1. 票區徽章 `<SectionBadge>`

驅動來源：`SectionAvailability.status`（由後端 `GET /api/events/{id}/sections` 與 SSE 推送）

### Props

```ts
type SectionBadgeProps = {
  section: string;              // "A"
  status: 'NOT_STARTED' | 'ON_SALE_PLENTY' | 'ON_SALE_LIMITED' | 'ON_SALE_FEW' | 'SOLD_OUT';
  onClick?: () => void;         // 點擊跳「搶這區」確認 modal
}
```

### 視覺規格

| State | bg | text | icon (8px) | 動效 |
|-------|----|----|------|------|
| NOT_STARTED | `--bg-surface-2` | `--fg-tertiary` | ○ | 無 |
| ON_SALE_PLENTY | `--bg-surface` + border `--status-plenty` | `--status-plenty` | ● | hover scale 1.02 |
| ON_SALE_LIMITED | `--bg-surface` + border `--status-limited` | `--status-limited` | ◐ | hover scale 1.02 |
| ON_SALE_FEW | `--bg-surface` + border `--status-few` | `--status-few` | ▲ | **pulse 1.6s 無限循環** |
| SOLD_OUT | `--bg-surface-2` | `--fg-tertiary` | ○（線稿） | 無，disabled |

- Radius: `--radius-sm` (4px)
- Padding: `--space-4` horizontal, `--space-3` vertical
- Font: `--text-heading-md` weight 700 (section name)
- 副文案：`<small>` 用 `--text-caption`，僅顯示狀態文字（「熱賣中」「即將售完」「僅剩數張」「已售完」「即將開賣」）
- **絕對不顯示精確張數**（UI/UX decision §5）

### 狀態切換動效
- 狀態改變時：`transition: border-color, color var(--motion-slower) var(--ease-standard)`
- pulse animation:
  ```css
  @keyframes badge-pulse {
    0%, 100% { box-shadow: 0 0 0 0 var(--status-few); opacity: 1; }
    50%      { box-shadow: 0 0 0 6px transparent; opacity: 0.85; }
  }
  ```

### 互動
- `onClick` 在 `ON_SALE_*` 狀態下可觸發；`NOT_STARTED` 和 `SOLD_OUT` 顯示 disabled cursor

---

## 2. 開賣倒數 `<SalesCountdown>`

用於畫面 1 卡片 + 畫面 2 hero。

### Props

```ts
type SalesCountdownProps = {
  salesStartAt: string;          // ISO datetime
  size?: 'compact' | 'hero';     // compact 用於卡片，hero 用於畫面 2
  onElapsed?: () => void;        // 倒數歸零回呼
}
```

### 視覺規格

| Size | Font token | Layout |
|------|------------|--------|
| compact | `--text-heading-md` mono | 單行 `HH:MM:SS` |
| hero | `--text-mono-display` (64px mono 700) | 三段大數字 + `天 時 分 秒` label，水平排列，每段間 `--space-5` |

- 字色：`--accent`（Acid Lime），數字使用 `font-variant-numeric: tabular-nums` 防跳動
- 「：」分隔符：`--fg-secondary` 顏色，weight 400
- 倒數每秒切換用 `--ease-snap` 200ms transition（hero size），給「儀式感」
- compact size 不做 snap

### 行為
- Mount 時用 `setInterval(1000)` 更新；卸載清除
- 倒數到 0 → fire `onElapsed`，元件變成「已開賣」chip（accent 底色 + ink 文字）
- 若 `salesStartAt` 為 null 或已過 → 直接顯示「已開賣」chip
- `useEffect` cleanup 防 memory leak

### 變體
- **「已開賣」chip**：accent 背景 + ink 文字 + 「LIVE」字樣（mono caps）+ 左側脈衝點

---

## 3. 鎖位倒數 `<HoldCountdown>` （畫面 4）

5 分鐘 UX 倒數（純前端，後端**沒有真實 TTL**）。

### Props

```ts
type HoldCountdownProps = {
  startedAt: number;           // Date.now() of booking completion
  durationMs?: number;         // default 5 * 60 * 1000
  onExpired: () => void;
}
```

### 視覺規格

- 主數字：`--text-mono-display`、accent 色、字級 64px mono 700
- 格式：`MM:SS`（不顯示小時）
- 下方副文案：`--text-body-md`、`--fg-secondary`、「請於倒數時間內確認您的座位」
- **最後 60 秒**：數字改 `--status-few` 紅色 + 1.2× pulse 動畫（每秒）
- 過期：數字變灰 `--fg-tertiary`、文案改「保留時間已過」

### 行為
- `requestAnimationFrame` + 截到秒 update（比 setInterval 更穩）
- 過期 fire `onExpired`，畫面 4 切換成「重新搶票」狀態

---

## 4. 排隊動畫 `<QueueOverlay>` （畫面 3）

全屏沉浸式等待。

### Props

```ts
type QueueOverlayProps = {
  bookingId: string;
  elapsedSec: number;           // 已等待秒數
  state: 'queueing' | 'long-wait' | 'failed';
  // 'long-wait' 當 elapsedSec > 30 啟用
}
```

### 視覺規格

- 背景：`--bg-ink` 全屏
- 中央：**幾何脈衝動畫**（SVG）
  - 三組同心圓環，半徑 80px / 140px / 200px
  - stroke `--accent`，stroke-width 1px
  - 透明度 0.6 / 0.4 / 0.2
  - 各自 rotate + scale 動畫，週期 `--motion-queue-cycle` (2.4s)，相位錯開 800ms
- 主文案：`--text-display-md` weight 800，「正在為您處理...」
- 副文案：`--text-body-lg`、`--fg-secondary`、「預估等待時間：約 10 秒」
- elapsedSec > 30 → 副文案改「處理時間較長，請耐心等候」
- elapsedSec > 60 → state 變 `failed`，顯示失敗 UI（見下）

### Failed 變體
- 動畫停止，圓環變灰
- 主文案：「很抱歉，這次沒搶到」
- 副文案：「您可以再試一次」
- 兩個按鈕：「回活動詳情」（primary）/「回活動列表」（secondary）

### 互動
- 攔截瀏覽器返回：`window.history.pushState` + `popstate` listener，顯示確認 toast
- ESC 鍵不允許離開（同上理由：避免使用者誤觸丟失 bookingId）

### Reduced motion
- `prefers-reduced-motion: reduce` → 圓環靜態、無 rotate；改用單純的 opacity 漸變

---

## 5. Long-poll Hook `useBookingPoll`

### Signature

```ts
function useBookingPoll(bookingId: string): {
  data: BookingResponse | null;
  state: 'polling' | 'success' | 'failed';
  elapsedSec: number;
  retryCount: number;
}
```

### 行為

- 進入畫面 3 後啟動
- `fetch(/api/bookings/{id}, { signal })` with 11s client-side timeout（後端 10s + 緩衝）
- **202 Accepted** → 立即重發（無 sleep）
- **200 OK + status=BOOKED** → state = success，停止 polling
- **200 OK + status=REJECTED** → state = failed
- **5xx / network error** → exponential backoff (1s, 2s, 4s)，最多 3 次後 state = failed
- 連續 elapsedSec > 60 → state = failed
- 元件卸載時 `AbortController.abort()`

---

## 6. SSE Hook `useSectionStatusStream`

### Signature

```ts
function useSectionStatusStream(eventId: number): {
  sections: Map<string, SectionAvailability>;
  connected: boolean;
}
```

### 行為

- mount 時建立 `new EventSource('/api/events/${eventId}/sections/stream')`
- 同時觸發一次 `GET /api/events/${eventId}/sections` 拿初始狀態
- `onmessage` (event: `section-status`) → 更新 Map
- `onerror` → 瀏覽器自動 reconnect，重連成功時 fire 一次 `GET /sections` 同步
- 元件卸載時 `eventSource.close()`

### 整合 React Query
- 初始 GET 用 React Query cache（5min stale）
- SSE 推送透過 `queryClient.setQueryData(['sections', eventId], updater)` 覆蓋

---

## 7. 海報卡片 `<EventCard>` （畫面 1）

### 視覺
- 直幅長方形（比例 2:3，模擬海報）
- 上半部：留白給活動主視覺（MVP 階段用純色塊或 unsplash placeholder）
- 下半部：
  - 活動名稱（`--text-heading-lg`，clip 2 行）
  - 表演者 + 場館（`--text-body-sm`，`--fg-secondary`）
  - 日期（`--text-caption`，`--fg-tertiary`）
  - 開賣狀態：`<SalesCountdown size="compact" />` 或 LIVE chip
- Border: 1px `--border-subtle`，hover 變 `--border-strong`
- Hover：scale 1.02 + 邊框 accent 化
- Radius: `--radius-sm`

### 互動
- click → 導向 `/events/:id`
- focus visible：accent ring

---

## 8. 「搶這區」確認 Modal `<BookingConfirmModal>`

### Props

```ts
type BookingConfirmModalProps = {
  open: boolean;
  event: EventResponse;
  section: SectionAvailability;
  onConfirm: (seatCount: number) => Promise<void>;
  onCancel: () => void;
}
```

### 視覺
- Backdrop: `rgba(0,0,0,0.7)`
- Content: `--bg-elevated`, `--radius-md`, max-width 480px
- 標題：「搶 A 區門票」（區域名 accent 色）
- 副資訊：活動名 + 日期
- Stepper（−／＋）選張數，default 1，range 1-4
- 預估金額（張數 × 票價，從 ticket API 取或 hardcode）
- 兩按鈕：「取消」（ghost）/「確認搶票」（accent CTA，loading state）
- 失敗 inline error：紅色文字

---

## 9. CTA 按鈕 `<Button>`

### Variants

| variant | bg | text | border | hover |
|---------|----|----|-------|------|
| `primary` | `--accent` | `--fg-inverse` | none | bg `--accent-hover` + glow shadow |
| `secondary` | transparent | `--fg-primary` | 1px `--border-strong` | bg `--bg-surface-2` |
| `ghost` | transparent | `--fg-secondary` | none | bg `--bg-surface` |
| `danger` | `--error` | `--fg-primary` | none | darken 5% |

### Sizes

| size | padding | font |
|------|---------|------|
| `sm` | `--space-2` / `--space-4` | `--text-body-sm` |
| `md` | `--space-3` / `--space-5` | `--text-body-md` |
| `lg` | `--space-4` / `--space-6` | `--text-body-lg` weight 700 |

- Radius: `--radius-sm`（editorial 銳利）
- Disabled: 50% opacity + cursor not-allowed
- Loading: 替換內容為 spinner（accent 色）+ disable click

---

## 10. Status Pill `<StatusPill>` （LIVE / 已開賣標記）

### Props
```ts
type StatusPillProps = {
  variant: 'live' | 'upcoming' | 'sold-out';
}
```

| variant | bg | text | icon | 動效 |
|---------|----|----|------|------|
| live | `--accent` | `--fg-inverse` | 脈衝點 | 點脈衝 1.6s |
| upcoming | `--bg-surface-2` | `--fg-secondary` | — | 無 |
| sold-out | `--bg-surface-2` | `--fg-tertiary` | — | 無 |

- Radius: `--radius-pill`
- Font: `--text-caption` uppercase letter-spacing 0.08em

---

## 11. Toast `<Toast>` （錯誤 / 成功 系統訊息）

- 出現位置：螢幕右上 `--space-5` 邊距
- 寬度 max 400px
- Variants：`success` / `error` / `info`，左側 4px 色條
- 自動 dismiss 4 秒（error 可手動關閉）
- 進場：translateY 100% → 0 + opacity 0→1，`--motion-slow`
- 退場：opacity 1→0，`--motion-base`

---

## 12. 元件清單總覽

| 元件 | 用於畫面 | 優先級 |
|------|---------|--------|
| `<EventCard>` | 1 | P0 |
| `<SalesCountdown>` | 1, 2 | P0 |
| `<StatusPill>` | 1, 2 | P0 |
| `<SectionBadge>` | 2 | P0 |
| `<BookingConfirmModal>` | 2 | P0 |
| `<QueueOverlay>` | 3 | P0 |
| `<HoldCountdown>` | 4 | P0 |
| `<Button>` | All | P0 |
| `<Toast>` | All | P0 |

Hooks：

| Hook | 用途 |
|------|------|
| `useBookingPoll(bookingId)` | 畫面 3 long-poll |
| `useSectionStatusStream(eventId)` | 畫面 2 SSE 即時更新 |
| `useCountdown(target)` | 通用倒數（給 SalesCountdown / HoldCountdown 共用） |
| `useAnonymousUserId()` | localStorage UUID |

---

## 13. 可及性（A11y）最低要求

- 所有互動元件可鍵盤操作（Tab focus + Enter activate）
- focus visible：accent 2px outline
- 票區徽章狀態用「色 + 文字 + 形狀」三重編碼（色弱友善）
- 排隊動畫支援 `prefers-reduced-motion`
- 倒數 `aria-live="polite"`
- Modal `role="dialog"` + `aria-labelledby` + focus trap + ESC 關閉
- 顏色對比度 ≥ WCAG AA（accent on ink 14:1 ✓）
