# Kafka Streams 搶票系統 — 卡片筆記

> 專案：TicketMaster
> 日期：2026-02-18
> 來源：基於 [Scaling to 1 Million Ticket Reservations](https://itnext.io/scaling-to-1-million-ticket-reservations-part-2-data-driven-optimizations-228c6a52e00a) 的架構教學問答

---

## 資料流全局圖

```
用戶 HTTP POST /api/reservations
        │
        ▼
  ReservationService.createReservation()
        │  產生 ReservationCommand (Avro)
        ▼
  ┌─────────────────────────────┐
  │  Topic: reservation-commands │  ← key = eventId
  └─────────────┬───────────────┘
                ▼
  ① ReservationCommandProcessor        State Store: reservation-store
     (建立 PENDING 狀態，轉發請求)
                │  產生 ReservationRequestedEvent
                ▼
  ┌─────────────────────────────┐
  │  Topic: reservation-requests │  ← key = eventId
  └─────────────┬───────────────┘
                ▼
  ② SeatAllocationProcessor            State Store: seat-inventory-store
     (查可用座位、分配連續座位)
                │  產生 ReservationResultEvent
                ▼
  ┌─────────────────────────────┐
  │  Topic: reservation-results  │  ← key = eventId
  └─────────────┬───────────────┘
                ▼
  ③ ReservationResultProcessor         State Store: reservation-store
     (更新狀態為 CONFIRMED/REJECTED)
                │  產生 ReservationCompletedEvent
                ▼
  ┌──────────────────────────────┐
  │  Topic: reservation-completed │
  └─────────────┬────────────────┘
                ▼
  ④ selectKey(reservationId) → KTable (reservation-query-store)
     │                                     │
     ▼                                     ▼
  .peek() → pendingRequests.resolve()   InteractiveQueryService
  (喚醒等待中的 DeferredResult)         (查詢已完成的訂單)
```

---

## Card 01 — 為什麼搶票系統不直接用 DB

**問題：** 傳統「直接操作 DB」的搶票方式在高併發下有什麼問題？

**核心概念：**

多個請求同時讀到「有票」，然後都嘗試 UPDATE，產生 race condition。解法只有兩種鎖，但都有瓶頸：

| 方式 | 做法 | 高併發代價 |
|------|------|-----------|
| 悲觀鎖 | `SELECT ... FOR UPDATE` | 999 人排隊等鎖，DB connection pool 耗盡 |
| 樂觀鎖 | version 欄位 + retry | 999 人 UPDATE 失敗後重試，DB 壓力更大 |

**根本問題：** 所有併發請求都在 DB 層競爭同一把鎖。

**解法方向：** 把「併發競爭」轉化為「順序處理」，排隊發生在 Kafka broker 層（極輕量），而不是 DB 層（極昂貴）。

> **連結** → [Card 02](#card-02--partition-即單寫者保證)（Kafka 如何實現順序處理）

---

## Card 02 — Partition 即單寫者保證

**問題：** Kafka 怎麼做到「不用鎖也不會重複訂票」？

**核心概念：**

Kafka 的 partition 分配策略是 `hash(key) % partitionCount`。只要用 `eventId` 當 key，同一活動的所有搶票請求**一定落在同一個 partition**。而一個 partition 在同一時間**只會被一個 Kafka Streams task 處理**，所以天然串行，不需要任何鎖。

**程式碼對照：** `ReservationService.java:41`

```java
String eventKey = request.getEventId().toString();
kafkaTemplate.send(TOPIC_RESERVATION_COMMANDS, eventKey, command);
//                                             ^^^^^^^^
//                       key = eventId，同一活動的請求全進同一個 partition
```

**效果：** 1000 人搶活動 A → 全進 partition 7 → 逐筆處理 → 第一個人拿到座位 → 後面的人看到庫存已扣 → 自然 REJECTED。

> **連結** → [Card 01](#card-01--為什麼搶票系統不直接用-db)（為什麼不用 DB）、[Card 06](#card-06--co-partitioning共同分區)（co-partitioning）

---

## Card 03 — Kafka vs Kafka Streams

**問題：** 基本 Kafka 和 Kafka Streams 有什麼差別？

**核心概念：**

```
基本 Kafka（訊息傳遞系統）：
  Producer → Topic (多個 Partition) → Consumer
  像「郵局」——寄信、存信、取信

Kafka Streams（串流處理框架）：
  Topic → Processor → Topic → Processor → Topic
  像「工廠流水線」——每一站做一件事，傳給下一站
```

Processor 就是「讀取 → 處理 → 寫出」的單元。Kafka Streams 把多個 Processor 串成 pipeline（topology），自動管理 partition 分配、容錯、State Store。

**比喻：**

- 基本 Kafka = 輸送帶（搬東西）
- Kafka Streams = 輸送帶 + 沿線的加工站（搬東西 + 加工）

> **連結** → [Card 04](#card-04--為什麼拆成多個-processor)（為什麼拆多個 Processor）

---

## Card 04 — 為什麼拆成多個 Processor

**問題：** 為什麼不用一個 Processor 做完所有事？

**核心概念：**

**State Store 是跟著 Partition 走的。** 每個 Partition 的 Processor 只能存取自己那份 State Store。

```
Partition 0 的辦公室              Partition 1 的辦公室
┌────────────────────────┐      ┌────────────────────────┐
│ 工人 (Processor)        │      │ 工人 (Processor)        │
│ 📒 我的帳本 (State Store)│      │ 📒 我的帳本 (State Store)│
│    只看得到自己的        │      │    看不到隔壁的          │
└────────────────────────┘      └────────────────────────┘
```

專案裡兩個核心 Processor 操作不同的 State Store：

| Processor | State Store | 記錄內容 |
|-----------|-------------|---------|
| ReservationCommandProcessor | `reservation-store` | 訂單資料（key=reservationId）|
| SeatAllocationProcessor | `seat-inventory-store` | 座位庫存（key=eventId-seatNumber）|

兩本帳本的 key 結構完全不同，歸檔邏輯也不同，所以必須拆成不同的 Processor 各自管理。

**程式碼對照：** `KafkaStreamsConfig.java:100-117`

```java
// ① seat-events → SeatEventMaterializeProcessor → seat-inventory-store
// ② reservation-commands → ReservationCommandProcessor → reservation-requests
// ③ reservation-requests → SeatAllocationProcessor → reservation-results
// ④ reservation-results → ReservationResultProcessor → reservation-completed
```

> **連結** → [Card 03](#card-03--kafka-vs-kafka-streams)（Processor 是什麼）、[Card 05](#card-05--state-store-與容錯機制)（State Store 容錯）

---

## Card 05 — State Store 與容錯機制

**問題：** State Store 存在哪裡？機器掛了資料會不會消失？

**核心概念：**

- **底層儲存：** RocksDB（磁碟，LSM Tree 結構，順序寫入，I/O 效能好）
- **容錯機制：** 不是靠 RocksDB 的 WAL，而是靠 **Kafka changelog topic**

```
每次 seatStore.put(key, value)
        │
        ▼ 自動同步
Kafka changelog topic（持久化在 Kafka broker）
ticketmaster-streams-seat-inventory-store-changelog
```

**機器掛掉時的恢復流程：**

```
新機器啟動
    → 從 changelog topic 讀取所有歷史記錄
    → 重建 RocksDB State Store
    → 恢復完成，繼續處理
```

**重點：** 真正保證不丟資料的是 Kafka 本身的持久化能力。RocksDB 只是本地的快取加速層。

> **連結** → [Card 04](#card-04--為什麼拆成多個-processor)（State Store 與 Partition 綁定）、[Card 11](#card-11--水平擴展)（水平擴展時 State Store 遷移）

---

## Card 06 — Co-partitioning（共同分區）

**問題：** 一個搶票請求經過多個 Topic，是不是每次都在不同的 partition？

**核心概念：**

**不是！** 是同一個 partition 編號貫穿整條流水線。

因為每一站轉發時的 key 始終是 `eventId`，而所有 Topic 都有相同的 partition 數量（20），所以 `hash(eventId) % 20` 永遠相同：

```
活動 #1 的搶票請求：
reservation-commands  partition 7  → Processor ①
reservation-requests  partition 7  → Processor ②  ← 全在 partition 7
reservation-results   partition 7  → Processor ③
```

**程式碼對照：** 每個 Processor 都用 eventId 當 key 轉發

```java
// ReservationCommandProcessor.java:54
context.forward(new Record<>(eventKey, event, ...));    // eventKey = eventId

// SeatAllocationProcessor.java:89
context.forward(new Record<>(eventKey, result, ...));   // eventKey = eventId

// ReservationResultProcessor.java:66
context.forward(new Record<>(eventKey, completedEvent, ...)); // eventKey = eventId
```

**co-partitioning 的保證：** 同一 eventId 的資料在每個 Topic 裡都落在相同 partition，確保每一站的 Processor 都能從自己的 State Store 找到需要的資料。

> **連結** → [Card 02](#card-02--partition-即單寫者保證)（Partition 與 key 的關係）、[Card 07](#card-07--selectkey-與-repartition)（打破 co-partitioning 的 selectKey）

---

## Card 07 — selectKey 與 Repartition

**問題：** 如果某一站把 key 從 `eventId` 換成 `reservationId`，會怎樣？

**核心概念：**

換 key 會打破 co-partitioning，所以 Kafka Streams 會自動做 **repartition（重新分區）**——建立一個內部 topic，用新 key 重新分配資料到不同 partition。

**程式碼對照：** `KafkaStreamsConfig.java:124-132`

```java
builder.stream(TOPIC_RESERVATION_COMPLETED, ...)       // key = eventId
    .selectKey((eventKey, event) -> event.getReservationId())  // 換 key！
    .toTable(Materialized.as(RESERVATION_QUERY_STORE))         // 建立查詢用 KTable
```

**為什麼要換 key？**

用戶查訂單時帶的是 `reservationId`（`GET /api/reservations/abc`），不是 `eventId`。如果 State Store 的 key 還是 `eventId`，收到 `reservationId = "abc"` 時根本不知道它在哪個 partition。換成 `reservationId` 當 key 後，就能算 `hash("abc") % 20` 精準定位。

```
換 key 前：同一活動的訂單全在 partition 7
換 key 後：每筆訂單按 reservationId 分散到不同 partition
```

**注意：** 這裡用的是 **KTable**（每個 instance 只存部分 partition），不是 GlobalKTable（每台存全部資料）。百萬筆訂單用 GlobalKTable 記憶體會爆。

| | KTable | GlobalKTable |
|--|--------|-------------|
| 資料分佈 | 每個 instance 只有**部分** partition 的資料 | 每個 instance 有**全部**資料 |
| 適用場景 | 大資料量（如訂單） | 小資料量的參考表（如匯率、設定檔） |

> **連結** → [Card 06](#card-06--co-partitioning共同分區)（co-partitioning）、[Card 08](#card-08--interactive-query互動式查詢)（Interactive Query 如何利用 repartition 後的 key）

---

## Card 08 — Interactive Query（互動式查詢）

**問題：** 查訂單結果時，資料可能在別台機器的 State Store 裡，怎麼辦？

**核心概念：**

Kafka Streams 的 Interactive Query 機制可以不依賴外部資料庫，直接查 State Store。透過 `queryMetadataForKey` 算出資料在哪台機器：

```
查詢 GET /api/reservations/abc
    → hash("abc") % 20 = partition 12
    → partition 12 在哪台機器？
    ├─ 在本機 → 直接讀本地 State Store
    └─ 在別台 → HTTP 轉發到 /internal/reservations/abc
```

**程式碼對照：** `InteractiveQueryService.java:39-54`

```java
KeyQueryMetadata metadata = kafkaStreams.queryMetadataForKey(
    RESERVATION_QUERY_STORE, reservationId, Serdes.String().serializer());

HostInfo activeHost = metadata.activeHost();
if (isLocalHost(activeHost)) {
    return queryLocalStore(kafkaStreams, reservationId);    // 本機直讀
} else {
    return queryRemoteStore(activeHost, reservationId);     // HTTP 轉發
}
```

**前提條件：** 每台機器必須設定不同的 `application.server`：

```properties
# application.properties
spring.kafka.streams.properties[application.server]=localhost:${server.port:8080}
```

多台部署時：

```
Instance A: application.server=host-a:8080
Instance B: application.server=host-b:8080
Instance C: application.server=host-c:8080
```

> **連結** → [Card 07](#card-07--selectkey-與-repartition)（selectKey 讓 key 對齊查詢需求）、[Card 11](#card-11--水平擴展)（水平擴展）

---

## Card 09 — DeferredResult 非阻塞長輪詢

**問題：** 搶票請求丟進 Kafka 後，HTTP 要怎麼拿到結果？

**核心概念：**

搶票分成兩次 HTTP 請求：

```
POST /api/reservations     → 202 + reservationId（立刻回應，不等待）
GET  /api/reservations/abc → DeferredResult 長輪詢（等結果，但不佔線程）
```

**DeferredResult vs 傳統 Blocking：**

| 傳統 Blocking | DeferredResult |
|--------------|----------------|
| 線程一直占著等結果 | 線程立刻釋放去服務別人 |
| 1000 個等待 = 1000 個線程卡住 | 1000 個等待 = 只佔 HashMap 記憶體 |

**觸發機制：** 當 Kafka Streams 處理完畢，透過 `KafkaStreamsConfig.java:132` 的 `.peek()` 回呼：

```java
.peek((reservationId, event) -> pendingRequests.resolve(event))
```

從 `ConcurrentHashMap` 取出對應的 DeferredResult，塞入結果，Spring 自動回應 HTTP。

**程式碼對照：** `ReservationPendingRequests.java:28-34`

```java
public void resolve(ReservationCompletedEvent event) {
    DeferredResult<...> deferred = pending.remove(event.getReservationId());
    if (deferred == null) return;  // 沒有人在等（可能已 timeout）
    deferred.setResult(ResponseEntity.ok(response));
}
```

> **連結** → [Card 10](#card-10--double-check-模式)（Double-Check 防止 race condition）

---

## Card 10 — Double-Check 模式

**問題：** `getReservationAsync` 裡為什麼要查兩次 State Store？

**核心概念：**

在「第一次查詢」和「註冊 DeferredResult」之間存在時間窗口。如果 Kafka Streams 剛好在這個窗口處理完畢，`.peek()` 觸發 `resolve()` 時 DeferredResult 還沒被註冊進 HashMap → resolve 找不到人 → **請求永遠等不到結果**（30 秒 timeout）。

```
拿掉 double-check 的 bug：

用戶線程:    ①查(沒結果)                    ②註冊DeferredResult
                                            ↑ 已經沒人會來 resolve
                       ↑
Kafka線程:        處理完畢，resolve("abc")
                  → pending.remove("abc") = null  ← 還沒註冊！
                  → if (deferred == null) return   ← 跳過了
```

**修復：註冊後再查一次**

```
用戶線程:    ①查(沒結果)     ②註冊     ③再查一次(有了！) → 直接回應 ✅
                       ↑
Kafka線程:        處理完畢，resolve 沒找到人（沒關係）
```

**程式碼對照：** `ReservationService.java:55-73`

```java
// ① 第一次查
ReservationResponse response = getReservation(reservationId);
if (response != null && !"PENDING".equals(response.getStatus())) {
    deferred.setResult(ResponseEntity.ok(response));
    return deferred;
}

// ② 註冊到 pending map
pendingRequests.register(reservationId, deferred);

// ③ Double-check：再查一次
response = getReservation(reservationId);
if (response != null && !"PENDING".equals(response.getStatus())) {
    deferred.setResult(ResponseEntity.ok(response));
}
```

這是 **check-then-act with double-check** 模式，與 Double-Checked Locking 精神類似。

> **連結** → [Card 09](#card-09--deferredresult-非阻塞長輪詢)（DeferredResult 機制）

---

## Card 11 — 水平擴展

**問題：** 怎麼讓系統撐住更大的流量？

**核心概念：**

**Partition 數量 = 並行度上限。** 目前設定 `partitions(20)`：

| 機器數量 | 每台分到 | 效果 |
|---------|---------|------|
| 1 台 | 20 個 partition | 全部自己扛 |
| 4 台 | 各 5 個 | 流量分散 |
| 20 台 | 各 1 個 | 最大並行度 |
| 21 台 | 1 台閒置 | **多開沒用** |

**Kafka Streams 自動處理的事：**

- 新機器加入 → 自動 rebalance，重新分配 partition
- State Store 遷移 → 從 changelog topic 重建（見 [Card 05](#card-05--state-store-與容錯機制)）
- 不需要改任何程式碼，只要 `application-id` 相同

```
原本 2 台：                      加入第 3 台後（自動 rebalance）：

Instance A: partition 0~9        Instance A: partition 0~6
Instance B: partition 10~19      Instance B: partition 7~13
                                 Instance C: partition 14~19 ← 新加入
```

**唯一需要注意：** 多台部署時 `application.server` 每台要設不同值，Interactive Query 才能正確轉發（見 [Card 08](#card-08--interactive-query互動式查詢)）。

> **連結** → [Card 05](#card-05--state-store-與容錯機制)（changelog 重建 State Store）、[Card 08](#card-08--interactive-query互動式查詢)（Interactive Query 跨機器查詢）

---

## Card 12 — 效能對比：本專案 vs 參考專案

**問題：** 同樣用 Kafka Streams，為什麼參考專案能達到 ~86K QPS，本專案只有 ~16K QPS？

**核心概念：**

差距約 5 倍，原因是多個因素疊加，按影響程度排列：

### 12-1. 座位資料結構 — 影響最大（~40%）

| | 參考專案 | 本專案 |
|--|---------|---------|
| 結構 | 一個區域 = 一筆記錄（2D 陣列） | 每個座位 = 一筆獨立記錄 |
| 查可用座位 | `store.get(key)` → **1 次 I/O** | `prefixScan` → **N 次 I/O** |
| 更新座位 | 改 array 後 `store.put()` → **1 次寫入** | 每個座位各寫一次 → **N 次寫入** |
| 500 座活動 | 2 次 I/O | 500+ 次 I/O |

參考專案的做法：

```java
// 一次 get 拿到整個區域的所有座位
AreaStatus areaStatus = areaStatusStore.get(eventAreaId);

// 直接陣列存取 O(1)
SeatStatus status = areaStatus.getSeats().get(row).get(col);
```

本專案的做法：

```java
// 掃描整個 prefix，遍歷所有座位
try (KeyValueIterator<String, SeatState> iterator =
        seatStore.prefixScan(keyPrefix, ...)) {    // N 筆記錄
    while (iterator.hasNext()) { ... }
}
```

### 12-2. Topic 跳轉次數（~25%）

每次 Topic 跳轉 = 序列化 → 寫入 Kafka → 持久化 → 讀取 → 反序列化。

| | 參考專案 | 本專案 |
|--|---------|---------|
| 跳轉次數 | ~2-3 次 | **5 次**（4 個 topic + 1 個 repartition） |

本專案的路徑：

```
reservation-commands → reservation-requests → reservation-results
    → reservation-completed → (repartition topic) → query-store
```

### 12-3. 框架開銷（~15%）

| | 參考專案 | 本專案 |
|--|---------|---------|
| HTTP 框架 | 原生 Jetty 12 + Jersey（極輕量） | Spring Boot 4 + Spring MVC |
| 每次請求開銷 | 直接 handler | Filter chain、Interceptor、AOP、DI |
| 線程模型 | 手動調優 acceptors/selectors | Spring 預設配置 |

### 12-4. JVM 調優（~10%）

參考專案使用：

```
-XX:+UseZGC -XX:+ZGenerational -Xmx2G -Xms2G -XX:+AlwaysPreTouch
```

本專案：JVM 預設設定（G1GC，未調優）。

### 12-5. Producer 批次設定（~7%）

| | 參考專案 | 本專案 |
|--|---------|---------|
| `linger.ms` | 30（累積 30ms 後批次送出） | 0（預設，每條立刻送出） |
| 效果 | 大幅減少網路往返 | 每條訊息一次網路 I/O |

### 12-6. Repartition 開銷（~3%）

本專案在最後做 `selectKey` 換 key，產生額外的內部 repartition topic。

**最划算的改動（零程式碼修改）：**

1. 加 JVM 參數：`-XX:+UseZGC -XX:+ZGenerational -Xmx2G -Xms2G -XX:+AlwaysPreTouch`
2. 加 `spring.kafka.producer.properties.linger.ms=30`

> **連結** → [Card 02](#card-02--partition-即單寫者保證)（Partition 與 key）、[Card 04](#card-04--為什麼拆成多個-processor)（Processor 拆分）、[Card 13](#card-13--微服務-vs-單體架構)（微服務 vs 單體）

---

## Card 13 — 微服務 vs 單體架構

**問題：** 參考專案的 3 個微服務如果用 Spring 做，專案結構會長什麼樣？

**核心概念：**

參考專案是 **mono-repo（單一代碼庫）+ 3 個獨立部署單元**：

```xml
<!-- 單一 pom.xml，用 Maven Shade Plugin 打 3 個 fat JAR -->
lab.tall15421542.app.ticket.Service       → ticket-service.jar
lab.tall15421542.app.reservation.Service  → reservation-service.jar
lab.tall15421542.app.event.Service        → event-service.jar
```

如果用 Spring Boot 做，典型的 multi-module 結構：

```
ticket-master/
├── pom.xml                          ← 父 pom（管理共用依賴版本）
├── common/
│   └── pom.xml                      ← 共用模組（Avro schema、domain）
├── ticket-service/
│   └── pom.xml                      ← 子 pom → 獨立 Spring Boot JAR
├── reservation-service/
│   └── pom.xml                      ← 子 pom → 獨立 Spring Boot JAR
└── event-service/
    └── pom.xml                      ← 子 pom → 獨立 Spring Boot JAR
```

**本專案是單體應用（monolith）**——所有 Processor 跑在同一個 Spring Boot process。

**單體 vs 微服務的取捨：**

| | 單體（本專案） | 微服務（參考專案） |
|--|--------------|----------------|
| 開發複雜度 | 低（一個專案搞定） | 高（多個服務要協調） |
| 部署 | 簡單（一個 JAR） | 複雜（K8s 編排） |
| 獨立擴展 | 不行（全部一起擴） | 可以（只擴瓶頸服務） |
| 效能調優 | 所有 Processor 共享 JVM | 各服務獨立調優 |

> **連結** → [Card 12](#card-12--效能對比本專案-vs-參考專案)（效能差距分析）、[Card 11](#card-11--水平擴展)（水平擴展）

---

## Card 14 — Page Fault 與 AlwaysPreTouch

**問題：** `-XX:+AlwaysPreTouch` 解決什麼問題？Page Fault 是什麼？

**核心概念：**

### OS 的「懶惰」記憶體分配

當 JVM 說「我要 2GB」時，OS **不會立刻分配實體記憶體**。只是帳面記一筆虛擬地址空間，背後沒有對應的實體記憶體頁。

```
JVM 啟動：「我要 2GB」

OS 的帳面：
┌──────┬──────┬──────┬──────┐
│ 頁 0  │ 頁 1  │ 頁 2  │ ...  │
│ 未映射 │ 未映射 │ 未映射 │      │  ← 實體記憶體還沒分配
└──────┴──────┴──────┴──────┘
```

### 第一次存取某頁時 → Page Fault

```
JVM: seatStore.put("1-A-1", state)
  → 存取虛擬頁 42
  → 頁 42 沒有對應實體記憶體！
  → 觸發 Page Fault（CPU 中斷）
  → OS 介入：分配實體記憶體頁、建立映射
  → 回到 JVM 繼續執行
```

### 兩種 Page Fault

| 類型 | 原因 | 延遲 | 情境 |
|------|------|------|------|
| **Minor**（軟缺頁） | 實體記憶體還沒分配，需要映射 | ~幾微秒 | 第一次存取新頁 |
| **Major**（硬缺頁） | 實體記憶體不夠，要從 swap 磁碟讀回 | ~幾毫秒 | 記憶體不足時 |

Major Page Fault 比 Minor 慢約 **1000 倍**。

### AlwaysPreTouch 的效果

```
沒有 AlwaysPreTouch（預設）：
  啟動快 → 執行期陸續觸發上千次 Minor Page Fault
  → 高併發下微秒級中斷累積成明顯延遲抖動

有 AlwaysPreTouch：
  啟動時把 2GB 全部「摸一遍」→ Page Fault 全在啟動期發生
  → 執行期間零 Page Fault → 延遲穩定、可預測
```

**核心思想：把痛苦集中在啟動階段，讓執行期間完全零 Page Fault。**

搭配 `-Xmx2G -Xms2G`（堆大小固定）效果更好——避免 JVM 動態擴縮堆時觸發額外的 Page Fault 和 GC。

> **連結** → [Card 12](#card-12--效能對比本專案-vs-參考專案)（JVM 調優對效能的影響）

---

## 卡片關係總覽

```
Card 01 為什麼不用DB
    └→ Card 02 Partition 單寫者保證
         └→ Card 06 Co-partitioning
              └→ Card 07 selectKey & Repartition
                   └→ Card 08 Interactive Query

Card 03 Kafka vs Kafka Streams
    └→ Card 04 為什麼拆多個 Processor
         └→ Card 05 State Store 容錯

Card 09 DeferredResult 長輪詢
    └→ Card 10 Double-Check 模式

Card 11 水平擴展（整合 Card 05 + 08）

Card 12 效能對比分析
    ├→ Card 13 微服務 vs 單體架構
    └→ Card 14 Page Fault 與 AlwaysPreTouch
```

---

## Avro Schema 速查

本專案使用 8 個 Avro schema，定義在 `src/main/avro/`：

| Schema | 用途 | 關鍵欄位 |
|--------|------|---------|
| `ReservationCommand` | 搶票指令（輸入） | reservationId, eventId, section, seatCount, userId |
| `ReservationRequestedEvent` | 已受理的搶票請求 | reservationId, eventId, section, seatCount, userId |
| `ReservationResultEvent` | 座位分配結果 | reservationId, success, allocatedSeats, failureReason |
| `ReservationCompletedEvent` | 最終訂單結果 | reservationId, eventId, userId, status, allocatedSeats |
| `ReservationState` | 訂單狀態（State Store） | reservationId, status, allocatedSeats, createdAt |
| `SeatEvent` | 座位事件（輸入） | eventId, seatNumber, section, status |
| `SeatState` | 座位狀態（State Store） | seatNumber, eventId, section, status, reservationId |
| `SeatStateStatus` | 座位狀態列舉 | AVAILABLE, RESERVED, SOLD |
