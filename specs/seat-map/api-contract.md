# Seat Map — API Contract

**Stage**: `/spec`
**Scope**: Phase A 對既有 API 的最小修改。Phase B 在 `phase-b-future-work.md` 列出。

---

## 1. 對既有 API 的分類

| 類別 | Endpoint | Phase A 變動 |
|------|----------|-------------|
| **新增** | （無） | 無 —— Phase A 不需要任何新 endpoint |
| **變動** | `GET /api/events` / `GET /api/events/{id}` | EventResponse 多 `bookingMode` 欄位 |
| **變動** | `GET /api/venues/{id}` | `seatMap` 字串內容從「空 / 無效 JSON」變成「合法 VenueSeatMap JSON」 |
| **不動** | `GET /api/events/{id}/sections` | shape / status 完全不變 |
| **不動** | `GET /api/events/{id}/sections/stream`（SSE） | shape / 訂閱機制完全不變 |
| **不動** | `POST /api/bookings` | request / response 完全不變（仍 section-level） |
| **不動** | `GET /api/bookings/{id}` | long-poll shape 完全不變 |
| **不動** | `GET /api/tickets*` | 不變（MVP 也沒用） |

→ **核心承諾**：搶票路徑（POST + long-poll + SSE）一個字都沒改。Phase 1 壓測完全沿用。

---

## 2. 變動 #1：`EventResponse` 加 `bookingMode`

### Before

```ts
type EventResponse = {
  id: number;
  name: string;
  description: string;
  eventStartTime: string;
  eventEndTime: string | null;
  venueId: number;
  venueName: string;
  performerName: string;
  totalSeats: number | null;
  sectionCount: number | null;
  salesStartAt?: string | null;
};
```

### After

```ts
type EventResponse = {
  id: number;
  name: string;
  description: string;
  eventStartTime: string;
  eventEndTime: string | null;
  venueId: number;
  venueName: string;
  performerName: string;
  totalSeats: number | null;
  sectionCount: number | null;
  salesStartAt?: string | null;
  bookingMode?: 'SECTION_TEXT' | 'SECTION_VISUAL' | 'SEAT_LEVEL';   // ⬅ NEW
};
```

### 後端工作量

- `Event.java` 加 `@Enumerated(EnumType.STRING) BookingMode bookingMode`（default `SECTION_TEXT`）
- `EventResponse.java` 加對應 field 與 getter
- `EventService.toResponse()` 把 entity 欄位帶上來
- JPA `ddl-auto=update` 自動 `ALTER TABLE`，無需 migration script
- **估時**：0.3 day（含單元測試）

---

## 3. 變動 #2：`Venue.seatMap` 從「unused 空字串」變成「合法 VenueSeatMap JSON」

### Before (`GET /api/venues/{id}` response)

```jsonc
{
  "id": 11,
  "name": "Taipei Arena",
  "location": "Taipei",
  "seatMap": ""                       // empty string or null
}
```

### After

```jsonc
{
  "id": 11,
  "name": "Taipei Arena",
  "location": "Taipei",
  "seatMap": "{\"schemaVersion\":1,\"viewBox\":\"0 0 800 600\",\"stage\":{...},\"sections\":[...]}"
}
```

### 重要約定

- 後端**不解析、不驗證、不查詢** `seatMap`。仍是 opaque `TEXT` blob，純 pass-through
- Schema 定義在 `venue-seatmap-schema.md`，**前端負責 parse**
- 既有空字串 / null 仍可接受 —— 前端 `parseVenueSeatMap()` 偵測 invalid 時 fallback 到 `SECTION_TEXT` 渲染

### 後端工作量

- **不需要 controller / service / model 改動**（欄位早已存在）
- 需要為示範 venues 填入合法 JSON：透過 seeder script、SQL UPDATE、或 admin UI
- **估時**：0.5 day（製作 3-5 個範例 venue seat map JSON）

---

## 4. 不動的 API（明確列出以驗證 zero regression）

### 4.1 `GET /api/events/{id}/sections`

```jsonc
// shape 完全不變
[
  {
    "eventId": 1,
    "section": "A",
    "totalSeats": 2400,
    "availableCount": 1800,
    "status": "ON_SALE_PLENTY",
    "basePrice": 3800
  }
]
```

→ `<VenueMap>` 與 `<SectionList>` **共享同一個** API。SSE 推送格式也不變。

### 4.2 `GET /api/events/{id}/sections/stream`（SSE）

```
event: section-status
id: <timestamp-ms>
data: {"eventId":1,"section":"A","availableCount":1234,"status":"ON_SALE_PLENTY", ...}
```

→ `<VenueMap>` 透過既有 `useSectionStatusStream` hook 拿同樣的 stream，內部 lookup `sections.find(s => s.name === event.section)` 把 polygon fill 染色。

### 4.3 `POST /api/bookings`

```jsonc
// Request shape 完全不變
{ "eventId": 1, "section": "A", "seatCount": 2, "userId": "uuid" }

// Response 202 完全不變
{ "bookingId": "uuid" }
```

→ `<VenueMap>` 點到 polygon → 開既有 `<BookingConfirmModal>` → 送既有 `POST /api/bookings`。**搶票後段流程零改動**。

### 4.4 `GET /api/bookings/{bookingId}`（long-poll）

完全不變，畫面 3、4 沿用。

---

## 5. CORS / 部署

無新 endpoint → **CORS config 完全不動**。既有的 `CorsConfig.java`（frontend-mvp Phase 1 已加）已涵蓋。

---

## 6. 後端工作量總結

| 項目 | 估時 | 必要性 |
|------|------|--------|
| `Event.bookingMode` 欄位 + EventResponse | 0.3 day | 必須 |
| 為 3-5 個 seed venues 補合法 seatMap JSON | 0.5 day | 必須（否則 Phase A 視覺化選區無圖可畫） |
| 既有 BDD `.feature` / k6 壓測腳本 | **0** | 不需改 |
| 新增 controller / service | **0** | 不需要 |
| **合計** | **~0.8 day** | — |

→ 對比 frontend-mvp Phase 1 的 ~2 day，本次後端工作量小很多（因為搶票路徑零動）。

---

## 7. Frontend API client 改動

```ts
// frontend/src/api/types.ts
+ export type BookingMode = 'SECTION_TEXT' | 'SECTION_VISUAL' | 'SEAT_LEVEL';

  export type EventResponse = {
    // ...
+   bookingMode?: BookingMode;
  };

// frontend/src/lib/venueSeatMap.ts (新增)
+ export function parseVenueSeatMap(raw?: string | null): VenueSeatMap | null { ... }

// frontend/src/api/venues.ts (擴充既有 hook)
  export function useVenue(id: number) {
    return useQuery({
      queryKey: ['venue', id],
      queryFn: () => fetchVenue(id),
      staleTime: 5 * 60_000,
    });
  }
+ export function useParsedVenueSeatMap(id: number) {
+   const v = useVenue(id);
+   return useMemo(() => parseVenueSeatMap(v.data?.seatMap), [v.data?.seatMap]);
+ }
```

---

## 8. Risks / Edge Cases

| # | Risk | 嚴重度 | 緩解 |
|---|------|--------|------|
| 1 | seatMap JSON 與後端 sections 不匹配（缺名 / 多名 / 改名） | 中 | 前端做 set-diff，未配置 polygon 顯示灰色「未配置」；超出 sections 的 polygon 一樣灰色 |
| 2 | seatMap 為空字串 / null 但 `bookingMode === SECTION_VISUAL` | 中 | parser 偵測 invalid → 自動降級 SECTION_TEXT + log warning |
| 3 | `bookingMode` 欄位不存在（舊前端打舊後端） | 低 | fallback `SECTION_TEXT` |
| 4 | `bookingMode === SEAT_LEVEL` 但 Phase B 未實作 | 低 | render `<SeatLevelPlaceholder>` 顯示「敬請期待」 |
| 5 | seatMap 大小爆炸 | 低 | < 5 KB 已是上限；React Query 5min cache |
