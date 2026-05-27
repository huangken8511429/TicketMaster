# Seat Map — Component Spec

**Stage**: `/spec`
**Scope**: Phase A 新增 `<VenueMap>` SVG renderer 與 EventDetailPage 切換邏輯。其他既有元件（`<SectionBadge>`、`<BookingConfirmModal>`、`<QueueOverlay>` 等）完全不動。

---

## 1. 元件清單

| 元件 / Hook | 新增 / 既有 | 用於 |
|---|---|---|
| **`<VenueMap>`** | ⭐ 新增 | 畫面 2 在 `bookingMode === SECTION_VISUAL` 時取代 `<SectionList>` |
| `<VenueMap.Polygon>` | 內部子元件 | 渲染單一 section 的 polygon / rect / circle |
| `<VenueMap.Stage>` | 內部子元件 | 渲染舞台標記 |
| `<VenueMap.Legend>` | 可選子元件 | 渲染 tier / status 圖例 |
| `<SeatLevelPlaceholder>` | 新增（佔位） | 畫面 2 在 `bookingMode === SEAT_LEVEL` 時顯示「敬請期待」 |
| `parseVenueSeatMap(raw)` | 新增（lib） | 將 `venue.seatMap` JSON 字串轉成 `VenueSeatMap` 型別 |
| `<SectionList>` / `<SectionBadge>` | **既有不動** | `SECTION_TEXT` 模式 |
| `<BookingConfirmModal>` | **既有不動** | 共用 |
| `useSectionStatusStream` | **既有不動** | 共用 |

---

## 2. `<VenueMap>` 主元件

### 2.1 Props

```ts
type VenueMapProps = {
  /** Parsed seat map JSON. Null / invalid → caller should fallback to <SectionList>. */
  seatMap: VenueSeatMap;
  /**
   * Section availability data — same shape as <SectionList> consumes.
   * Source: GET /api/events/{id}/sections, merged with SSE updates via React Query.
   */
  sections: SectionAvailability[];
  /** Fired when user picks an interactive section (status ∈ ON_SALE_*). */
  onPick: (section: SectionAvailability) => void;
  /**
   * Optional class / a11y label override.
   */
  className?: string;
  ariaLabel?: string;
};
```

### 2.2 內部資料流

```
VenueMapProps
   ↓
1. 建立 Map<sectionName, SectionAvailability> (O(1) lookup)
   ↓
2. 對 seatMap.sections 逐一渲染 <Polygon>，把 status 染色
   ↓
3. 未匹配的 polygon 顯示為「未配置」灰色（不擋 render）
   ↓
4. polygon onClick → 過濾 INTERACTIVE_STATUSES → onPick(section)
```

→ 與 `EventDetailPage` 既有的 `INTERACTIVE_STATUSES` 過濾邏輯**完全一致**，可直接 import 共用。

### 2.3 SVG 結構

```jsx
<svg
  viewBox={seatMap.viewBox}
  role="img"
  aria-label={ariaLabel ?? "場館選區圖"}
  className={cn("w-full h-auto select-none", className)}
>
  {/* 背景（可選）*/}
  {seatMap.meta?.background && <image href={seatMap.meta.background} />}

  {/* 舞台 */}
  <Stage stage={seatMap.stage} />

  {/* 票區 polygons */}
  {seatMap.sections.map(s => (
    <Polygon
      key={s.name}
      shape={s}
      availability={availMap.get(s.name)}
      onPick={onPick}
    />
  ))}

  {/* 圖例（可選，desktop 右下角）*/}
  {seatMap.legend && <Legend items={seatMap.legend} />}
</svg>
```

### 2.4 響應式

| 寬度 | 行為 |
|------|------|
| < 768px (mobile) | SVG 滿寬，aspect 由 viewBox 決定；可橫向 scroll；touch 友善 |
| ≥ 768px (tablet+) | 最大寬度 `max-w-4xl` 置中；保留 hero 兩欄佈局右側 |
| ≥ 1280px (desktop) | 圖例顯示於右側；polygon name label 字級放大 |

---

## 3. `<VenueMap.Polygon>` 子元件

### 3.1 視覺規格（同 SectionBadge 配色，但作用在 polygon fill）

| status | fill | fill-opacity | stroke | stroke-width | text label | 動效 |
|--------|------|--------------|--------|--------------|-----------|------|
| `NOT_STARTED` | `--bg-surface-2` | 0.6 | `--line-subtle` | 1 | section name `--fg-tertiary` | 無 |
| `ON_SALE_PLENTY` | `--status-plenty` | 0.3 | `--status-plenty` | 1.5 | section name `--status-plenty` | hover fill-opacity → 0.5 |
| `ON_SALE_LIMITED` | `--status-limited` | 0.3 | `--status-limited` | 1.5 | section name `--status-limited` | hover fill-opacity → 0.5 |
| `ON_SALE_FEW` | `--status-few` | 0.3 | `--status-few` | 2 | section name `--status-few` | **pulse 1.6s 無限** + hover snap |
| `SOLD_OUT` | `--bg-surface-2` | 0.4 | `--line-subtle` (dashed) | 1 | section name `--fg-tertiary` line-through | 無 |
| **「未配置」**（unmatched） | `--bg-surface-2` | 0.2 | `--line-subtle` (dashed) | 1 | "—" `--fg-tertiary` | 無 |
| hover (可選) | 上行 + 0.5 alpha | — | width +0.5 | — | — | `transition: var(--motion-base)` |
| focus visible | accent ring `--accent` 2px outline | — | — | — | — | — |
| disabled (NOT_STARTED / SOLD_OUT) | `cursor: not-allowed` | — | — | — | — | 無 hover |

### 3.2 Label 渲染

```jsx
<text
  x={labelAnchor.x}
  y={labelAnchor.y}
  textAnchor="middle"
  dominantBaseline="central"
  className="text-heading-md font-bold pointer-events-none"
  fill={fillToken}
>
  {section.displayName ?? section.name}
</text>
```

→ `labelAnchor` 未指定時，由前端用 polygon centroid 計算。

### 3.3 互動

| 事件 | 條件 | 動作 |
|------|------|------|
| `onClick` | `INTERACTIVE_STATUSES.has(status)` | 呼叫 `onPick(section)` → 開 `<BookingConfirmModal>` |
| `onKeyDown` Enter / Space | focus + interactive | 同 click |
| `onPointerEnter` | interactive | fill-opacity transition |
| `onPointerLeave` | — | revert |

### 3.4 鍵盤 / a11y

- `<polygon tabIndex={status === 'NOT_STARTED' || status === 'SOLD_OUT' ? -1 : 0}>`
- `role="button"`、`aria-label="{section.displayName} {statusLabel}"`
- `aria-disabled={!interactive}`
- 「色 + 文字 + dashed border」三重編碼（色弱友善）

---

## 4. `<VenueMap.Stage>` 子元件

```tsx
function Stage({ stage }: { stage: VenueSeatMap['stage'] }) {
  return (
    <g aria-hidden>
      {stage.shape === 'rect' && stage.rect && (
        <rect
          x={stage.rect.x} y={stage.rect.y}
          width={stage.rect.width} height={stage.rect.height}
          fill="var(--bg-surface-2)"
          stroke="var(--line-strong)"
          strokeWidth="1"
        />
      )}
      {stage.shape === 'polygon' && stage.polygon && (
        <polygon points={stage.polygon.map(p => p.join(',')).join(' ')}
          fill="var(--bg-surface-2)" stroke="var(--line-strong)" />
      )}
      <text /* centroid */
        textAnchor="middle"
        className="text-caption uppercase tracking-[0.18em]"
        fill="var(--fg-secondary)">
        {stage.label}
      </text>
    </g>
  );
}
```

### 視覺
- 永遠灰色、無互動、與 polygon 區隔（標示「不可選」）
- 字級 `--text-caption`、tracking 寬，editorial 風格
- 若 `stage.position === 'center'`（圓形劇場），renderer 可選擇不畫舞台框（避免遮中央 polygons）

---

## 5. `<VenueMap.Legend>` 子元件（可選）

```tsx
type LegendItem = { label: string; swatch: string };

function Legend({ items }: { items: LegendItem[] }) {
  return (
    <g transform="translate(20, 540)" aria-label="圖例">
      {items.map((it, i) => (
        <g key={it.swatch} transform={`translate(${i * 120}, 0)`}>
          <rect width="14" height="14" fill={`var(--tier-${it.swatch})`} />
          <text x="20" y="11" className="text-caption">{it.label}</text>
        </g>
      ))}
    </g>
  );
}
```

→ Phase A 可選不畫；若空間有限，desktop only 顯示。

---

## 6. `EventDetailPage` 切換邏輯（與既有元件整合）

### 6.1 改動範圍

```diff
  // EventDetailPage.tsx
+ import { useParsedVenueSeatMap } from '@/api/venues';
+ import { VenueMap } from '@/components/VenueMap';
+ import { SeatLevelPlaceholder } from '@/components/SeatLevelPlaceholder';

  export function EventDetailPage() {
    // ...既有 hooks 全部保留...
+   const seatMap = useParsedVenueSeatMap(event?.venueId);
+   const mode = event?.bookingMode ?? 'SECTION_TEXT';

    // ...既有 hero / countdown / meta 區塊不動...

    {/* 票區渲染區塊 —— Phase A 改動點 */}
    <div className="flex flex-col gap-5">
      {/* 既有的「選擇票區」標題保留 */}
-     {/* 既有 grid of SectionBadge */}
-     {sections.length > 0 && (
-       <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-4 gap-4">
-         {sections.map(s => <SectionBadge ... />)}
-       </div>
-     )}

+     {mode === 'SECTION_TEXT' && (
+       <SectionList sections={sections} onPick={handleBadgeClick} />
+     )}
+     {mode === 'SECTION_VISUAL' && seatMap && (
+       <VenueMap seatMap={seatMap} sections={sections} onPick={handleBadgeClick} />
+     )}
+     {mode === 'SECTION_VISUAL' && !seatMap && (
+       /* fallback: invalid JSON */
+       <SectionList sections={sections} onPick={handleBadgeClick} />
+     )}
+     {mode === 'SEAT_LEVEL' && (
+       <SeatLevelPlaceholder />
+     )}
    </div>
```

### 6.2 `<SectionList>` 抽取

把既有的 `sections.map(section => <SectionBadge ...>)` grid 抽成獨立元件 `<SectionList>`，方便兩個 mode 共用「點某區→開 modal」邏輯。**這是 refactor，不改行為。**

### 6.3 共用 `handleBadgeClick` / `handleConfirmBooking`

- `<SectionList>` 與 `<VenueMap>` 都吃同一個 `onPick: (section) => void`
- modal 開啟、確認、422 fallback 邏輯 100% 不變
- 唯一差異：`<VenueMap>` 在 422 時要把 polygon 立即染灰；既有 React Query `setQueryData` 已寫法相容（polygon fill 由 query data 驅動，**自動就會染灰**，不需額外處理）

---

## 7. `<SeatLevelPlaceholder>` 元件（Phase A 佔位）

```tsx
export function SeatLevelPlaceholder() {
  return (
    <div className="border border-line-subtle bg-surface p-10 rounded-md text-center flex flex-col gap-3">
      <span className="text-caption uppercase tracking-[0.18em] text-fg-tertiary">
        / Seat-Level Booking
      </span>
      <h3 className="text-display-md font-extrabold tracking-tight">逐座位選位</h3>
      <p className="text-body-md text-fg-secondary">此活動採逐座位選位，敬請期待。</p>
    </div>
  );
}
```

→ 純占位，Phase B 才實作真正互動。Phase A 必要 export 以避免 mode switch crash。

---

## 8. Design Token 沿用 / 新增

Phase A **不新增 design tokens**，全沿用 frontend-mvp 既有：

| Token | 用途 |
|-------|------|
| `--status-plenty` / `--status-limited` / `--status-few` | polygon stroke + fill 色 |
| `--bg-surface` / `--bg-surface-2` | 舞台 / NOT_STARTED / SOLD_OUT 背景 |
| `--line-subtle` / `--line-strong` | 邊框 |
| `--fg-primary` / `--fg-secondary` / `--fg-tertiary` | label 文字色 |
| `--accent` | focus ring |
| `--motion-base` / `--ease-standard` | hover transition |
| `@keyframes badge-pulse` | ON_SALE_FEW polygon pulse（與 SectionBadge 共用） |

可選擴充（Phase B 才會需要）：
- `--tier-vip` / `--tier-tier1` / `--tier-tier2` / `--tier-standing` —— legend 配色，Phase A 若加圖例需要

---

## 9. 測試清單（給 build 階段參考）

| Test | Level | 重點 |
|------|-------|------|
| `parseVenueSeatMap` parser | unit | valid / invalid / schemaVersion mismatch / 空字串 |
| `<VenueMap>` 渲染 | component | sections × polygon × shape 三種型態都 render |
| `<VenueMap>` 配對 | component | sections name 與 SectionAvailability join 正確；未配置 polygon 灰色 |
| `<VenueMap>` 互動 | component | 點 ON_SALE polygon → onPick fire；點 SOLD_OUT → 不 fire |
| `<VenueMap>` a11y | component | keyboard tab + Enter；aria-disabled 正確 |
| `EventDetailPage` mode switch | integration | mode 三值各自 render 正確 renderer |
| `EventDetailPage` fallback | integration | bookingMode=SECTION_VISUAL + invalid seatMap → 降級 SECTION_TEXT |
| SSE 整合 | integration | section status 改變時 polygon fill 跟著變（透過 React Query cache） |

---

## 10. 元件總覽

| 元件 | 用於畫面 | 狀態 | 優先級 |
|------|---------|------|--------|
| `<VenueMap>` | 2 | 新增 | P0 |
| `<VenueMap.Polygon>` | 2 | 新增 | P0 |
| `<VenueMap.Stage>` | 2 | 新增 | P0 |
| `<VenueMap.Legend>` | 2 | 新增（可選） | P2 |
| `<SectionList>` | 2 | refactor（從既有 grid 抽出） | P0 |
| `<SeatLevelPlaceholder>` | 2 | 新增（占位） | P1 |
| 其他既有元件 | 1 / 2 / 3 / 4 | 不動 | — |

Hooks / Libs：

| 名稱 | 狀態 | 用途 |
|------|------|------|
| `parseVenueSeatMap` | 新增（lib） | venue.seatMap → VenueSeatMap |
| `useParsedVenueSeatMap` | 新增（hook） | wrapper over useVenue + memo |
| `useSectionStatusStream` | **既有不動** | 共用 |
| `useBookingPoll` | **既有不動** | 共用 |
