# Frontend MVP — UI/UX Decision Handoff

**Stage**: Pre-Flow（UI/UX discovery）
**Date**: 2026-05-18
**Next Stage**: `/point`
**Purpose**: 將 UI/UX 討論結果固化為 artifact，供下一個 fresh agent（/point）讀取，不依賴對話脈絡。

---

## 1. 任務一句話描述

為現有的 TicketMaster 後端（Java 25 + Spring Boot 4 + Kafka Streams，已通過壓測）建立**前端搶票介面 MVP**。

## 2. 需求邊界

### In Scope（MVP）
- 4 個畫面：活動列表 → 活動詳情 → 排隊中 → 鎖位確認
- **票區式搶票**：使用者選票區 + 張數，後端自動分配座位
- 桌面優先（Desktop-first）
- 對接既有 API（events、sections、reservations、long-polling）

### Out of Scope
- 個別座位選擇（不畫場館 SVG 圖）
- 金流 / 結帳流程
- 會員系統、登入註冊
- 手機 RWD 細節（之後再做）
- 後台管理介面

## 3. 使用者輪廓

- **A 類：散客 / 一般觀眾**
- 一年搶 1-2 次熱門演唱會
- 對搶票流程不熟悉，UX 要極度直觀、容錯高、低資訊密度
- 不需要鍵盤快捷鍵、進階模式

## 4. 4 個 MVP 畫面與後端對應

| # | 畫面 | 核心元件 | 後端對應 |
|---|------|---------|---------|
| 1 | **活動列表** | 海報卡片網格、開賣倒數、搜尋 | `event` module |
| 2 | **活動詳情** | 場次選擇 + 票區卡片（含狀態徽章）+ 張數選擇 + 「搶這區」CTA | `event` + `SectionStatusEvent` 廣播 |
| 3 | **排隊中** | 沉浸式等待動畫 + 預估等待時間文案（不顯示精確位置） | long-polling endpoint（async booking） |
| 4 | **鎖位確認** | 系統分配的座位（排/座）+ TTL 倒數 + 「確認」按鈕 | `reservation` 完成事件 |

## 5. 票區狀態徽章規格

由 `SectionStatusEvent`（50B 高頻廣播）驅動。**僅顯示狀態文字 + 顏色，不顯示精確張數**（避免黃牛掃描 + 減少使用者焦慮）。

| 狀態 | 條件 | 顏色 | 文案 |
|------|------|------|------|
| 熱賣中 | 庫存 > 30% | 綠 | 熱賣中 |
| 即將售完 | 庫存 5%–30% | 黃 | 即將售完 |
| 僅剩數張 | 庫存 < 5% | 紅 | 僅剩數張 |
| 已售完 | 庫存 = 0 | 灰 | 已售完（disabled） |

## 6. 視覺方向

使用者授權創作空間（「漂亮就好」）。原則：

- **避開**：拓元的表格感、一般 SaaS 的 pastel + soft shadow 制式 AI 美感
- **追求**：editorial（編輯式排版）、高對比、distinctive
- **建議基底**：深色背景 + 強烈 accent color + 有個性的 Sans-Serif（如 Inter Tight / Söhne 等）
- **倒數元件**：極粗等寬字體，搶票場域的儀式感
- **動態狀態**：脈衝動畫、大膽幾何，避免通用 spinner
- **資訊密度**：低密度、留白多（散客導向）

關鍵字：`impeccable` + `bolder`（使用者原話）

## 7. 高併發 UX 重點

這是搶票系統獨有的 UX 挑戰：

1. **排隊體驗**：對接後端 long-polling（`feat e95daf8` 已實作），畫面要顯示「正在處理請求」+ 預估等待時間，但不顯示精確排隊位置避免焦慮
2. **票區即時狀態**：`SectionStatusEvent` 廣播驅動票區徽章即時更新
3. **鎖位 TTL 倒數**：搶到後給使用者有限時間確認（具體秒數待 spec 階段釐定）

## 8. 技術棧建議（待 /spec 確認）

尚未綁定，但考量點：
- 桌面優先 + 即時更新需求 → React + WebSocket/SSE 或長輪詢
- 後端是 Spring Boot，前端建議獨立 SPA（部署解耦）
- 設計品質要求高 → 建議 Tailwind + 自訂 design system（避免 MUI/AntD 制式感）

## 9. Open Questions（給 /point + /spec）

- 鎖位 TTL 精確秒數？（後端應已有設定）
- 是否需要活動搜尋？或 MVP 階段純列表瀏覽？
- 票區資料結構：後端 API 是否已有 `GET /events/{id}/sections` 含庫存狀態？
- 開賣前的「時間到點」機制：前端輪詢 vs 倒數結束後自動啟用按鈕？

## 10. Next Stage

```
/point  ← fresh agent，評估這個前端建置任務的複雜度與分流（DIRECT-BUILD vs SPEC-FIRST）
```

預期 /point 會建議 `PASS-SPEC-FIRST`（全新前端、4 畫面、即時狀態整合，非單純小修改）。
