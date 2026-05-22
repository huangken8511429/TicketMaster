# 對應 specs/frontend-mvp/plan/done/phase-3-screen-event-list.md §D6
# Note: Cucumber 尚未整合進前端 toolchain（見 phase-2 handoff §5.1）。
# 此 .feature 為 source-of-truth 的行為規格，實際斷言由
# src/test/EventsListPage.test.tsx 覆蓋（Vitest + Testing Library + fake timers）。

Feature: 活動列表

  Background:
    Given MSW 啟用且後端 GET /api/events 可取得 EventResponse 陣列

  Scenario: 使用者看到熱賣中的活動
    Given 後端有 1 個已開賣的活動 id=1（salesStartAt 在過去）
    When 使用者打開 /events
    Then 應該看到該活動的海報卡片
    And 該卡片顯示 "LIVE" 標記

  Scenario: 倒數歸零自動切換為熱賣中
    Given 一個活動的 salesStartAt 在 3 秒後
    When 使用者打開 /events 並等待 4 秒
    Then 該卡片從 "UPCOMING" 倒數變為 "LIVE" 標記
    And 不需要重新整理頁面

  Scenario: 點擊卡片導向活動詳情
    Given 列表頁有一個活動 id=1
    When 使用者點擊該活動卡片
    Then URL 應變為 /events/1

  Scenario: 載入中顯示骨架
    Given GET /api/events 尚未回應
    When 使用者打開 /events
    Then 應該看到至少 6 個骨架卡片
    And 區塊有 aria-busy="true"

  Scenario: 空清單顯示 editorial 空狀態
    Given GET /api/events 回傳空陣列 []
    When 使用者打開 /events
    Then 應該看到 "目前沒有活動" 文案
    And 不應該看到海報卡片網格

  Scenario: API 失敗顯示重試按鈕
    Given GET /api/events 回傳 500
    When 使用者打開 /events
    Then 應該看到 "載入失敗，請稍後再試"
    And 應該看到「重試」按鈕
