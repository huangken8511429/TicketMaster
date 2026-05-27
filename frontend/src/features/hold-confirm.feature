Feature: 鎖位確認與倒數
  As a 搶票使用者
  When 我搶到票被導向確認頁
  I want 看到後端分配的座位 + 5 分鐘倒數
  So that 我有清楚的視覺確認與「動作期限」儀式感

  Background:
    Given Phase 5 將 BookingResponse 透過 router state 傳給 /confirm/:bookingId

  Scenario: 顯示分配的座位
    Given 從排隊頁傳來 BookingResponse 含 allocatedSeats=["A-3-5","A-3-6"]
    When 使用者進入 /confirm/abc-123
    Then 應該看到主標題 "已為您保留座位"
    And 應該看到 2 張座位卡片
    And 第一張卡片顯示 "A 區 · 3 排 · 5 號"

  Scenario: 倒數從 5 分鐘起跳
    Given 使用者剛進入 /confirm/abc-123
    Then 倒數應為 "05:00"
    And 倒數應每秒減少

  Scenario: 倒數歸零顯示重新搶票
    Given 使用者已停留 5 分鐘
    Then 主標題應變為 "保留時間已過"
    And 應該看到提示 "您的座位保留已過期，請重新搶票。"
    And CTA 應變為 "重新搶票"
    And 座位卡片應半透明

  Scenario: 點擊重新搶票回活動列表
    Given 倒數已過期
    When 使用者點擊 "重新搶票"
    Then URL 應變為 /

  Scenario: 直接訪問 URL 無 state 引導離開
    Given location.state.booking 為空
    When 使用者直接打開 /confirm/abc-123
    Then 應該短暫顯示 "正在確認保留資訊…" loading
    And 1 秒後應該顯示 Toast "無法取得保留資訊，請重新搶票"
    And URL 應變為 /

  Scenario: 點擊確認保留顯示 demo 訊息
    Given 使用者在 /confirm/abc-123 倒數中
    When 使用者點擊 "確認保留"
    Then 應該顯示 Toast "結帳流程不在本 MVP — Demo 完成"
    And 確認按鈕應變為 "已確認" 且 disabled

  Scenario: 點擊取消回活動列表
    Given 使用者在 /confirm/abc-123 倒數中
    When 使用者點擊 "取消並回活動列表"
    Then URL 應變為 /
