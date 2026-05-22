# Venue Seat Map — JSON Schema

**Stage**: `/spec` ⭐ **核心交付物**
**對應 entity**: `com.keer.ticketmaster.po.Venue.seatMap` (currently `TEXT NULL`)
**消費者**: 前端 `<VenueMap>` SVG renderer（Phase A）；Phase B 將擴充座位粒度

---

## 1. 設計目標

| # | 目標 | 對應檢核 |
|---|------|---------|
| 1 | 描述任意場館的視覺布局（不規則、有舞台朝向） | §2 `stage` + §3 `sections[]` |
| 2 | 必須能容納既有 5 個 seed venues（台北小巨蛋、高雄巨蛋、流行音樂中心、台中洲際、Taipei Arena 系列） | §6 範例 payload |
| 3 | **Phase B 不需要 schema breaking change** —— 可平滑加上 row / seat metadata | §5 預留欄位 |
| 4 | 前端零依賴可解析 —— 純 JSON、不嵌 SVG raw markup | §3 採 polygon / rect 抽象，由前端轉 SVG |
| 5 | 場館圖可被 CDN / Redis 快取（不可變且小） | §7 大小估計：5 個 venues 各 < 4KB |
| 6 | 與既有 `Section.name` 一對一對應 —— SSE 推 section status 時可直接 lookup | §3 `sections[].name` 是 join key |

---

## 2. Top-level schema

```jsonc
{
  "schemaVersion": 1,           // 必填。Phase A = 1，Phase B 可升 2 並新增欄位
  "viewBox": "0 0 800 600",     // 必填。SVG viewBox，單位無實際意義（純比例）
  "stage": {                    // 必填。舞台 / 主視覺方向
    "position": "north",        // "north" | "south" | "east" | "west" | "center"（圓形劇場）
    "shape": "rect",            // "rect" | "polygon"
    "rect": { "x": 100, "y": 20, "width": 600, "height": 60 },  // shape=rect 時必填
    "polygon": null,            // shape=polygon 時填 number[][]
    "label": "STAGE"            // 顯示文字（i18n key 之後再做）
  },
  "sections": [ /* see §3 */ ],
  "legend": [                   // 可選。前端是否畫圖例由 renderer 決定
    { "label": "VIP", "swatch": "vip" },
    { "label": "1F", "swatch": "tier1" }
  ],
  "meta": {                     // 可選。展示用 metadata
    "rotationDeg": 0,           // 整張圖旋轉
    "background": null          // 預留：未來可給場館底圖 URL
  }
}
```

**核心欄位**：
- `schemaVersion` —— 強制存在，前端可據此決定 renderer 版本（forward compat）
- `viewBox` —— 直接餵給 `<svg viewBox="">`，避免硬綁定真實尺寸
- `stage.position` —— 給 renderer 提示「舞台在哪一側」，讓 hover / tooltip 知道方向感
- `sections[]` —— 與後端 `Section` 列表 1:1 對應，**`name` 即 join key**

---

## 3. `sections[]` schema

每個 element 描述一個票區的視覺呈現：

```jsonc
{
  "name": "A",                  // 必填。對應 Section.name（join key）
  "displayName": "A 區",        // 可選。UI 顯示名（i18n 後可移除）
  "tier": "vip",                // 可選。語意分層："vip" | "tier1" | "tier2" | "standing" | string
  "shape": "polygon",           // "polygon" | "rect" | "circle"
  "polygon": [                  // shape=polygon 時必填
    [100, 100], [300, 100], [300, 200], [100, 200]
  ],
  "rect": null,                 // shape=rect 時填 { x, y, width, height }
  "circle": null,               // shape=circle 時填 { cx, cy, r }
  "labelAnchor": { "x": 200, "y": 150 },  // 可選。section name 文字錨點，省略則 renderer 算 centroid
  "rotationDeg": 0,             // 可選。section 自身的旋轉
  "stageFacing": "north",       // 可選。該區面向舞台的方位（用於 hover tooltip「面向舞台」提示）

  /* === Phase B 預留欄位（Phase A renderer 不讀，schema 允許但不要求） === */
  "rows": null,                 // Phase B: Array<{ rowLabel: string, seats: Seat[] }>
  "seatGrid": null,             // Phase B: { rows: number, cols: number, originAnchor: [x,y], pitch: [dx,dy] }
  "accessibilityZones": null,   // Phase B: number[][]（無障礙席多邊形）
  "blockedSeats": null          // Phase B: string[] 例如 ["3-A-12"]
}
```

### Shape 選擇指南

| 場館類型 | 建議 shape | 範例 |
|----------|-----------|------|
| 矩形大廳 / 平面演講廳 | `rect` | 台中洲際 |
| 不規則甜甜圈 / 馬蹄形 | `polygon` | 台北小巨蛋（環形 = 多段 polygon） |
| 圓形 / 接近圓形劇場 | `circle` | 高雄巨蛋（也可用 polygon 近似） |
| 流行音樂中心戶外 | `polygon` | 不規則扇形 |

→ Phase A 只要支援這三種就可覆蓋既有 5 個 venues。

---

## 4. TypeScript 型別（前端真實 source of truth）

```ts
// frontend/src/types/venueSeatMap.ts  (新增於 Phase A)

export type SeatMapVersion = 1;  // Phase B 可加 2

export type StagePosition = 'north' | 'south' | 'east' | 'west' | 'center';
export type SectionShape = 'polygon' | 'rect' | 'circle';

export type Rect = { x: number; y: number; width: number; height: number };
export type Circle = { cx: number; cy: number; r: number };
export type Polygon = Array<[number, number]>;

export type VenueSeatMapSection = {
  name: string;                    // join key → Section.name
  displayName?: string;
  tier?: 'vip' | 'tier1' | 'tier2' | 'standing' | string;
  shape: SectionShape;
  polygon?: Polygon;
  rect?: Rect;
  circle?: Circle;
  labelAnchor?: { x: number; y: number };
  rotationDeg?: number;
  stageFacing?: StagePosition;

  // Phase B placeholders — present in schema, NOT consumed by Phase A renderer
  rows?: null | Array<unknown>;
  seatGrid?: null | unknown;
  accessibilityZones?: null | Array<Polygon>;
  blockedSeats?: null | string[];
};

export type VenueSeatMap = {
  schemaVersion: SeatMapVersion;
  viewBox: string;                 // "minX minY width height"
  stage: {
    position: StagePosition;
    shape: 'rect' | 'polygon';
    rect?: Rect;
    polygon?: Polygon;
    label: string;
  };
  sections: VenueSeatMapSection[];
  legend?: Array<{ label: string; swatch: string }>;
  meta?: {
    rotationDeg?: number;
    background?: string | null;
  };
};
```

### Parse 策略

```ts
// frontend/src/lib/venueSeatMap.ts
export function parseVenueSeatMap(raw: string | null | undefined): VenueSeatMap | null {
  if (!raw) return null;
  try {
    const obj = JSON.parse(raw) as VenueSeatMap;
    if (obj.schemaVersion !== 1) return null;  // 未來版本暫不支援
    return obj;
  } catch {
    return null;  // 不擋 render，呼叫端 fallback 到 SECTION_TEXT renderer
  }
}
```

→ **無效 JSON 必須 fallback 而非崩潰**（既有 5 筆舊資料可能仍是空字串）。

---

## 5. Phase B 預留欄位的相容性策略

| Phase B 需求 | Phase A schema 預留欄位 | 不擋 Phase A 嗎？ |
|---|---|---|
| 個別座位顯示與點擊 | `sections[].rows[]` / `sections[].seatGrid` | ✅ Phase A renderer 完全不讀 |
| 無障礙席標記 | `sections[].accessibilityZones` | ✅ |
| 不可售座位 | `sections[].blockedSeats` | ✅ |
| 不同票價的視覺分區（per-row pricing） | `sections[].tier` + Phase B 加 `sections[].pricingZones` | ✅（`tier` Phase A 已用，pricingZones 為新增欄位） |
| Schema 大改 | bump `schemaVersion` 到 2 | ✅ 前端可 detect 並 fallback |

→ Phase A 的所有欄位都是 **additive**，Phase B 只需新增欄位，不需 break。

---

## 6. 5 個 seed venues 的 schema 實例

> 座標僅為示意，最終由前端工程師與設計微調。重點是 **schema 能裝下** 既有 sections。

### 6.1 Taipei Arena（台北小巨蛋，事件 id=1）—— 環形 + 5 區

```jsonc
{
  "schemaVersion": 1,
  "viewBox": "0 0 800 600",
  "stage": {
    "position": "north",
    "shape": "rect",
    "rect": { "x": 280, "y": 40, "width": 240, "height": 50 },
    "label": "STAGE"
  },
  "sections": [
    {
      "name": "A", "displayName": "A 區", "tier": "vip",
      "shape": "polygon",
      "polygon": [[280, 130], [520, 130], [560, 230], [240, 230]],
      "stageFacing": "north"
    },
    {
      "name": "B", "displayName": "B 區", "tier": "tier1",
      "shape": "polygon",
      "polygon": [[120, 200], [240, 230], [240, 380], [120, 410]],
      "stageFacing": "east"
    },
    {
      "name": "C", "displayName": "C 區", "tier": "tier1",
      "shape": "polygon",
      "polygon": [[560, 230], [680, 200], [680, 410], [560, 380]],
      "stageFacing": "west"
    },
    {
      "name": "D", "displayName": "D 區", "tier": "tier2",
      "shape": "polygon",
      "polygon": [[240, 380], [560, 380], [520, 480], [280, 480]],
      "stageFacing": "north"
    },
    {
      "name": "E", "displayName": "E 區 站位", "tier": "standing",
      "shape": "rect",
      "rect": { "x": 280, "y": 250, "width": 240, "height": 100 },
      "stageFacing": "north"
    }
  ],
  "legend": [
    { "label": "VIP", "swatch": "vip" },
    { "label": "1F", "swatch": "tier1" },
    { "label": "2F", "swatch": "tier2" },
    { "label": "STANDING", "swatch": "standing" }
  ]
}
```

### 6.2 Kaohsiung Music Center（高雄流行音樂中心，事件 id=2）—— 半圓 + 5 區

```jsonc
{
  "schemaVersion": 1,
  "viewBox": "0 0 800 600",
  "stage": {
    "position": "north",
    "shape": "polygon",
    "polygon": [[300, 30], [500, 30], [560, 90], [240, 90]],
    "label": "STAGE"
  },
  "sections": [
    { "name": "A", "tier": "vip", "shape": "polygon",
      "polygon": [[280, 120], [520, 120], [550, 200], [250, 200]], "stageFacing": "north" },
    { "name": "B", "tier": "tier1", "shape": "polygon",
      "polygon": [[180, 200], [250, 200], [260, 360], [170, 380]], "stageFacing": "east" },
    { "name": "C", "tier": "tier1", "shape": "polygon",
      "polygon": [[550, 200], [620, 200], [630, 380], [540, 360]], "stageFacing": "west" },
    { "name": "D", "tier": "tier2", "shape": "polygon",
      "polygon": [[250, 200], [550, 200], [540, 360], [260, 360]], "stageFacing": "north" },
    { "name": "E", "tier": "tier2", "shape": "polygon",
      "polygon": [[170, 380], [630, 380], [580, 480], [220, 480]], "stageFacing": "north" }
  ]
}
```

### 6.3 Taichung Intercontinental Hall（台中洲際，事件 id=3）—— 矩形演講廳 + 5 區

```jsonc
{
  "schemaVersion": 1,
  "viewBox": "0 0 800 600",
  "stage": {
    "position": "south",
    "shape": "rect",
    "rect": { "x": 200, "y": 500, "width": 400, "height": 60 },
    "label": "STAGE"
  },
  "sections": [
    { "name": "A", "tier": "vip", "shape": "rect",
      "rect": { "x": 200, "y": 380, "width": 400, "height": 100 }, "stageFacing": "south" },
    { "name": "B", "tier": "tier1", "shape": "rect",
      "rect": { "x": 200, "y": 280, "width": 400, "height": 90 }, "stageFacing": "south" },
    { "name": "C", "tier": "tier1", "shape": "rect",
      "rect": { "x": 200, "y": 190, "width": 400, "height": 80 }, "stageFacing": "south" },
    { "name": "D", "tier": "tier2", "shape": "rect",
      "rect": { "x": 100, "y": 280, "width": 90, "height": 200 }, "stageFacing": "east" },
    { "name": "E", "tier": "tier2", "shape": "rect",
      "rect": { "x": 610, "y": 280, "width": 90, "height": 200 }, "stageFacing": "west" }
  ]
}
```

> 其餘 2 個 venues（若存在於 backend，未來新增的）可比照上述風格產生。本 spec **不要求**事前產生全部 venues 的合法 JSON —— Phase A 工程階段由前端工程師 / 設計師補齊（見 `specs/handoffs/seat-map-spec.md` §4 Phase 拆分）。

---

## 7. 大小估計 & 儲存策略

| 場館 | sections | 預估 JSON 大小（minified） |
|------|----------|---------------------------|
| 矩形大廳（5 sections） | 5 rect | ~ 1 KB |
| 環形大型場館（5 polygon, 各 6-8 點） | 5 polygon | ~ 3 KB |
| 預估上限（含 Phase B 預留欄位但不填） | — | < 5 KB |

→ 不需單獨 table、不需 CDN，**繼續用 `Venue.seatMap` 的 `TEXT` 欄位**。讀取頻率不高（每場活動進入 detail 頁時一次），可由前端 React Query 5 min stale cache 處理。

**注意**：欄位目前是 `String` 形式存 JSON。後端**不需要**改成 `jsonb` —— 後端不解析、不查詢、不索引，純 pass-through。前端負責 parse。

---

## 8. 驗證規則（後端 / 前端共用）

> Phase A 不強制後端做 schema validation —— 後端視為 opaque blob。但建議**前端 parser** 做以下檢查：

1. `schemaVersion === 1`，否則 fallback `SECTION_TEXT`
2. `viewBox` 必填且符合 `/^-?\d+\s+-?\d+\s+\d+\s+\d+$/`
3. 每個 `sections[].name` 必須能在後端 `GET /api/events/{id}/sections` 的 response 中找到對應 entry（**unmatched names 顯示為「未配置」灰色 polygon，不擋 render**）
4. `shape` 與對應的 `polygon` / `rect` / `circle` 必須其中之一存在

驗證失敗 → 視為 invalid，整張圖 fallback 到 `<SectionList>`（不部分 render，避免畫一半）。

---

## 9. Open Issues / 後續可優化

| # | 議題 | 是否擋 Phase A |
|---|------|-----------------|
| 1 | 是否需要 schema linter / JSON Schema 文件（draft-07）？ | 否，Phase B 再做 |
| 2 | 場館底圖（照片背景）是否要支援？`meta.background` 已預留 | 否 |
| 3 | i18n —— `displayName` / `legend.label` 是否要支援多語言 key？ | 否，Phase A 先 hardcode zh-TW |
| 4 | 場館圖共享 —— 同一個 venue 不同活動是否可覆寫 sections？ | 否，Phase A schema 在 Venue 即可（活動不複寫） |
| 5 | Performance —— 1 萬+ sections 場館？ | 不在 scope，現實 venues < 30 sections |
