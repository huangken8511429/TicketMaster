# 對應 specs/frontend-mvp/plan/done/phase-4-screen-event-detail.md §D7
# Note: Cucumber 尚未整合進前端 toolchain（見 phase-2 handoff §5.1）。
# 此 .feature 為 source-of-truth 的行為規格，實際斷言由
# src/test/EventDetailPage.test.tsx 與 src/test/useSectionStatusStream.test.tsx 覆蓋。

Feature: 活動詳情與票區搶票

  Background:
    Given MSW 啟用且 seed 內含 event id=1（5 個 sections，A/B/C/D/E）

  Scenario: 顯示活動詳情與票區列表
    Given 後端有 1 個活動 id=1 含 5 個 sections
    When 使用者打開 /events/1
    Then 應該看到活動名稱
    And 應該看到 5 個票區徽章

  Scenario: SSE 即時更新票區狀態
    Given 使用者在 /events/1
    And 票區 A 初始狀態為 ON_SALE_PLENTY
    When 後端推送 SectionStatusEvent 將 A 變為 ON_SALE_FEW
    Then 票區 A 徽章應變為紅色 "僅剩數張"

  Scenario: 點擊熱賣中票區開啟確認 modal
    Given 票區 B 狀態為 ON_SALE_PLENTY
    When 使用者點擊票區 B
    Then 應該開啟 BookingConfirmModal
    And modal 應該顯示 "B 區"

  Scenario: 已售完票區不可點擊
    Given 票區 E 狀態為 SOLD_OUT
    When 使用者點擊票區 E
    Then BookingConfirmModal 不應該開啟

  Scenario: 搶票成功跳轉排隊
    Given 在 BookingConfirmModal 中選擇 2 張票
    When 使用者點擊 "確認搶票"
    And POST /api/bookings 回 202 + bookingId="abc"
    Then URL 應變為 /queue/abc

  Scenario: 搶票 422 該區已售完
    Given 在 BookingConfirmModal 中選擇 1 張票
    When POST /api/bookings 回 422
    Then 應該顯示 Toast "該區已售完"
    And modal 應該關閉
    And 該票區徽章變為 SOLD_OUT
