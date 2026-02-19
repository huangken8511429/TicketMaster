# language: zh-TW
功能: 分散式預訂查詢（Interactive Query）
  作為系統管理員
  我想要多個服務實例都能查詢到同一筆預訂
  以便確保 Kafka Streams Interactive Query 的跨節點查詢正確運作

  場景: 兩個實例都能查到同一筆 reservation
    假如 系統啟動了兩個服務實例，分別在 port 8080 和 port 8081
    假如 一筆預訂「res-multi-001」已寫入 reservation-completed topic，使用者為「user001」，狀態為「CONFIRMED」，座位為「A-001,A-002」
    當 分別向兩個實例查詢預訂「res-multi-001」
    那麼 兩個實例都應成功回傳預訂資料
    並且 兩個實例回傳的預訂資料應完全一致
    並且 查詢結果的預訂狀態應為「CONFIRMED」
    並且 Instance 2 應透過 HTTP 遠端查詢 Instance 1
