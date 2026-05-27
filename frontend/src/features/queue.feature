Feature: 排隊中與 long-poll
  As a 搶票使用者
  When 我送出搶票請求後
  I want 看到沉浸式排隊動畫並在拿到座位時自動跳轉確認頁
  So that 我不必擔心請求是否還在處理

  Background:
    Given 使用者剛 POST /api/bookings 成功取得 bookingId="abc-123"

  Scenario: 進入排隊頁面顯示動畫
    When 使用者進入 /queue/abc-123
    Then 應該看到 "正在為您處理..." 文案
    And 應該看到副文案 "預估等待時間：約 10 秒"
    And 應該看到沉浸式幾何動畫

  Scenario: long-poll 成功跳轉確認頁
    Given 使用者在 /queue/abc-123
    When useBookingPoll 回 state=success + BookingResponse(status=BOOKED, allocatedSeats=["A-3-5","A-3-6"])
    Then URL 應變為 /confirm/abc-123
    And 應該將 BookingResponse 透過 router state 傳遞到下一頁

  Scenario: 30 秒後切換為長等待文案
    Given 使用者在 /queue/abc-123 已等待 35 秒
    Then 副文案應為 "處理時間較長，請耐心等候"

  Scenario: 60 秒後觸發失敗 UI
    Given 使用者在 /queue/abc-123 已等待 61 秒
    When useBookingPoll 觸發 state=failed
    Then 應該看到 "很抱歉，這次沒搶到"
    And 應該看到 "回活動詳情" 與 "回活動列表" 兩按鈕

  Scenario: REJECTED 立即失敗
    Given 使用者在 /queue/abc-123
    When useBookingPoll 回 state=failed + BookingResponse(status=REJECTED)
    Then 應該立即看到失敗 UI
    And 不應該 navigate 到 /confirm

  Scenario: 5xx 多次失敗後顯示失敗 UI
    Given 使用者在 /queue/abc-123
    When useBookingPoll 5xx 三次後 state=failed
    Then 應該看到失敗 UI

  Scenario: 失敗後可從失敗 UI 回到活動列表
    Given 使用者在失敗 UI
    When 使用者點擊 "回活動列表"
    Then URL 應變為 /

  Scenario: 失敗後可使用 fromEventId 回到活動詳情
    Given 使用者帶 router state fromEventId=42 進入 /queue/abc-123
    And 使用者在失敗 UI
    When 使用者點擊 "回活動詳情"
    Then URL 應變為 /events/42

  Scenario: 攔截瀏覽器返回
    Given 使用者在 /queue/abc-123 排隊中
    When 使用者按瀏覽器返回鍵
    Then 應該顯示確認 toast "離開將取消請求"
    And 應該保持在排隊頁面
