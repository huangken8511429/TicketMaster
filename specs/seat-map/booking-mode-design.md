# Booking Mode — Design

**Stage**: `/spec`
**對應 entity**: `com.keer.ticketmaster.po.Event`（建議新增 `bookingMode` 欄位）
**前端消費點**: `EventDetailPage.tsx`、`<VenueMap>` / `<SectionList>` switcher

---

## 1. 為什麼需要 `bookingMode`？

PM 確認**三種搶票體驗並行**：

| Mode | 階段 | UI | 後端搶票路徑 |
|------|------|-----|-------------|
| `SECTION_TEXT` | 既有 | `<SectionList>` 文字票區清單（既有 4 畫面 MVP） | Kafka section-level allocation（既有） |
| `SECTION_VISUAL` | **Phase A 新增** | `<VenueMap>` SVG 場館圖 | Kafka section-level allocation（不變） |
| `SEAT_LEVEL` | Phase B 預留 | per-seat picker（TBD） | per-seat reservation pipeline（TBD，含預鎖 TTL） |

→ 前端需要一個 flag 來決定 EventDetailPage 渲染哪個 renderer。

---

## 2. 決策：`bookingMode` 放在 `Event`（不是 `Venue`）

### 2.1 候選方案 trade-off

| 方案 | 優點 | 缺點 |
|------|------|------|
| **A. 放 Event** ✅ 採用 | 同一個場館可承辦不同類型活動：演唱會用 SECTION_VISUAL、研討會用 SECTION_TEXT、付費首映用 SEAT_LEVEL。語意清楚：「**這場活動以什麼方式賣票**」 | Event entity 多一個欄位、5 筆 seed event 需有預設值 |
| B. 放 Venue | 場館只有一張圖，邏輯更集中 | **語意錯誤**：bookingMode 是銷售策略不是場館屬性；同 venue 不能跨類型活動 |
| C. 放 Performer / 獨立 BookingPolicy entity | 最 future-proof | 過度設計、Phase A 用不到 |

### 2.2 採用 A 的關鍵理由

1. **`Venue.seatMap` = 物理事實**（場館長什麼樣，永遠不變）
2. **`Event.bookingMode` = 銷售策略**（這次怎麼賣，每次活動可不同）
3. 兩者語意正交，不應耦合
4. Phase B 加入 `SEAT_LEVEL` 時，**只影響部分 event**，不需要將整個 venue 切換到 seat-level，不會 break 同 venue 上其他既有活動

---

## 3. Enum 定義

### 3.1 Java enum

```java
// com.keer.ticketmaster.po.BookingMode (新增 enum)
public enum BookingMode {
    /**
     * Legacy / default. Renders text-based section list.
     * Booking goes via section-level Kafka allocation. Phase 1 baseline.
     */
    SECTION_TEXT,

    /**
     * Phase A. Renders venue floor plan as SVG; user clicks a section polygon.
     * Booking still goes via section-level Kafka allocation — same path as SECTION_TEXT.
     */
    SECTION_VISUAL,

    /**
     * Phase B (reserved, not implemented). User picks individual seats.
     * Will require new Seat entity, per-seat Kafka topic, hold TTL,
     * per-seat SSE. See specs/seat-map/phase-b-future-work.md.
     */
    SEAT_LEVEL;
}
```

### 3.2 Event entity 改動（最小 diff）

```java
// com.keer.ticketmaster.po.Event
@Enumerated(EnumType.STRING)
@Column(nullable = false, length = 32)
private BookingMode bookingMode = BookingMode.SECTION_TEXT;  // legacy fallback
```

**為什麼 `EnumType.STRING`** —— 避免 ordinal drift；未來新增 enum 值 / 改順序時不會破壞既有資料。

**為什麼 default `SECTION_TEXT`** —— 既有 5 筆 seed event 自動繼承既有體驗，零 regression。

### 3.3 TypeScript 型別

```ts
// frontend/src/api/types.ts (擴充既有 EventResponse)
export type BookingMode = 'SECTION_TEXT' | 'SECTION_VISUAL' | 'SEAT_LEVEL';

export type EventResponse = {
  // ... existing fields ...
  bookingMode?: BookingMode;  // optional for forward compat; default SECTION_TEXT in renderer
};
```

→ 前端 fallback 規則：`event.bookingMode ?? 'SECTION_TEXT'`。

---

## 4. 向後相容 / Migration 策略

### 4.1 既有 5 筆 seed event 怎麼辦？

| 選項 | 動作 | 推薦 |
|------|------|------|
| **A. JPA `ddl-auto=update` 自動加欄位 + DEFAULT 'SECTION_TEXT'** | DB column 加 `NOT NULL DEFAULT 'SECTION_TEXT'` + Java field default | ✅ 採用 |
| B. 寫 Liquibase migration | 多一個 migration file | 不必要，本案無 Liquibase 慣例 |
| C. 不加欄位，前端 hardcode | 沒有切換能力 | ❌ |

### 4.2 DB Migration 行為

由 `application.properties` 看：

```
spring.jpa.hibernate.ddl-auto=update
```

→ 啟動時 Hibernate 會自動 `ALTER TABLE event ADD COLUMN booking_mode VARCHAR(32) NOT NULL DEFAULT 'SECTION_TEXT'`。**不需手寫 migration**。

**注意**：
- 若未來改 production 環境用 `validate` 或 `none`，須補一份 Liquibase / Flyway 腳本
- Phase A 仍是 dev / staging，`update` 即可

### 4.3 既有資料如何手動切換為 SECTION_VISUAL？

```sql
-- 開發環境：把示範用的演唱會 event 切到視覺化選區
UPDATE event SET booking_mode = 'SECTION_VISUAL' WHERE id IN (1, 2);
```

或在 admin tool / seeder 程式裡設。**Phase A 不需要做後端 API 來改 mode**，PM 確認此屬「活動設定」而非「使用者操作」。

---

## 5. API 影響

### 5.1 EventResponse（變動）

```diff
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
+  bookingMode?: BookingMode;  // NEW. fallback SECTION_TEXT
 };
```

對應後端 `EventResponse` POJO 加一個 getter 即可。

### 5.2 不需要新增 PUT endpoint

「切換某活動的 bookingMode」**不是 Phase A 使用者操作**，因此**不需要** `PUT /api/events/{id}/booking-mode` 之類 endpoint。Phase A 只透過 seeder / SQL 設定。

---

## 6. 前端切換邏輯

```tsx
// EventDetailPage.tsx (僅示意關鍵分支)
const mode = event.bookingMode ?? 'SECTION_TEXT';

return (
  <section>
    {/* ...既有 hero / meta / countdown 不動... */}

    {mode === 'SECTION_TEXT' && (
      <SectionList sections={sections} onPick={openModal} />   // 既有 grid + <SectionBadge>
    )}

    {mode === 'SECTION_VISUAL' && (
      <VenueMap
        seatMap={parseVenueSeatMap(venue.seatMap)}
        sections={sections}                                    // 同一份 SectionAvailability[]
        onPick={openModal}
      />
    )}

    {mode === 'SEAT_LEVEL' && (
      <SeatLevelPlaceholder />                                 // Phase B 才實作；Phase A 顯示 "尚未開放"
    )}

    {/* modal / queue / confirm 流程完全沿用 frontend-mvp */}
  </section>
);
```

### Fallback 規則

| 條件 | 行為 |
|------|------|
| `bookingMode === undefined` | 視為 `SECTION_TEXT` |
| `bookingMode === 'SECTION_VISUAL'` 但 `venue.seatMap` 解析失敗 | fallback 為 `SECTION_TEXT` 並 log warning |
| `bookingMode === 'SECTION_VISUAL'` 但場館圖 sections 與後端 sections 不匹配 | 仍 render，未匹配的 section name 顯示為灰色「未配置」（避免整張圖崩潰） |
| `bookingMode === 'SEAT_LEVEL'` | render `<SeatLevelPlaceholder>` 顯示「此活動採逐座位選位，敬請期待」 + Toast 提示開發中 |

---

## 7. 測試影響 / BDD

| 既有測試類別 | 是否受影響 |
|---|---|
| BDD `.feature`（booking、ticket、event、venue） | **不需改寫** —— 預設 `SECTION_TEXT`，既有 scenarios 沿用 |
| k6 壓測腳本（commit `b2fd4c1`） | **不需改寫** —— 仍 POST `/api/bookings`（與 mode 無關） |
| 既有單元測試 / WebMvcTest | 後端 service / controller 不變，只 EventResponse 多一個欄位（測 ignore unknown 即可） |
| **新增**（Phase A build 階段） | `<VenueMap>` 元件測試、`parseVenueSeatMap` parser 測試、`EventDetailPage` mode switch 測試 |

---

## 8. 決策 Recap

| 議題 | 決策 |
|------|------|
| flag 放哪 | `Event.bookingMode` |
| Enum 值 | `SECTION_TEXT` / `SECTION_VISUAL` / `SEAT_LEVEL` |
| 預設值 | `SECTION_TEXT`（與既有體驗一致） |
| JPA 儲存 | `EnumType.STRING`，NOT NULL，DEFAULT 'SECTION_TEXT' |
| Migration | `ddl-auto=update` 自動處理，無需手寫 |
| API 變更 | EventResponse 加 `bookingMode` 欄位，非必填 |
| 後端 PUT endpoint | **不需要** —— 以 seeder / SQL 設定 |
| Fallback | mode 缺失或場館圖無效 → 自動降級為 `SECTION_TEXT` |
