# SectionSeatState 瘦身實施計劃

## 問題分析

### 當前架構
```
SectionInitProcessor 和 SeatAllocationProcessor
  ↓
  發送完整 SectionSeatState (包含 seatStatuses map) 到 section-status topic
  ↓
  ~5000 座位 × 20 bytes/entry = ~100KB 每條消息
  ↓
  每個 API instance 的 SectionStatusCache broadcast consumer 接收
  ↓
  反序列化整個 map，但只使用 availableCount
```

### 性能影響
- **序列化開銷**: 1000 QPS × 100KB = **100MB/sec** 序列化/反序列化
- **網路流量**: Kafka broker 磁碟 I/O 爆表
- **消費端開銷**: 每個 API instance 都要反序列化完整 map

---

## 瘦身方案

### Step 1: 定義新 Avro Schema（5 min）

創建 `SectionStatusEvent.avsc`：只包含必要字段

```avsc
{
  "type": "record",
  "name": "SectionStatusEvent",
  "namespace": "com.keer.ticketmaster.avro",
  "fields": [
    {"name": "eventId", "type": "long"},
    {"name": "section", "type": "string"},
    {"name": "availableCount", "type": "int"},
    {"name": "timestamp", "type": "long", "logicalType": "timestamp-millis"}
  ]
}
```

**位置**: `src/main/avro/SectionStatusEvent.avsc`

---

### Step 2: 修改 Producers（座位初始化和分配）(30 min)

#### 2a. SectionInitProcessor 改進

當前：
```java
context.forward(new Record<>(storeKey, state, record.timestamp()));
```

改為：
```java
SectionStatusEvent statusEvent = SectionStatusEvent.newBuilder()
  .setEventId(state.getEventId())
  .setSection(state.getSection())
  .setAvailableCount(state.getAvailableCount())
  .setTimestamp(record.timestamp())
  .build();

context.forward(new Record<>(storeKey, statusEvent, record.timestamp()));
```

#### 2b. SectionStatusEmitter 改進

當前：
```java
@Override
public void process(Record<String, ReservationCompletedEvent> record) {
  ReservationCompletedEvent event = record.value();
  String storeKey = event.getEventId() + "-" + event.getSection();

  SectionSeatState state = seatStore.get(storeKey);
  if (state != null) {
    context.forward(new Record<>(storeKey, state, record.timestamp()));
  }
}
```

改為：
```java
@Override
public void process(Record<String, ReservationCompletedEvent> record) {
  ReservationCompletedEvent event = record.value();
  String storeKey = event.getEventId() + "-" + event.getSection();

  SectionSeatState state = seatStore.get(storeKey);
  if (state != null) {
    // 只發送 count，不發送完整 map
    SectionStatusEvent statusEvent = SectionStatusEvent.newBuilder()
      .setEventId(state.getEventId())
      .setSection(state.getSection())
      .setAvailableCount(state.getAvailableCount())
      .setTimestamp(record.timestamp())
      .build();

    context.forward(new Record<>(storeKey, statusEvent, record.timestamp()));
  }
}
```

---

### Step 3: 修改 Consumer（SectionStatusCache）(30 min)

當前：
```java
@KafkaListener(
  topics = "section-status",
  groupId = "${app.section-status.group-id}",
  containerFactory = "kafkaListenerContainerFactory"
)
public void onSectionStatus(ConsumerRecord<String, SectionSeatState> record) {
  SectionSeatState state = record.value();
  if (state != null) {
    String key = state.getEventId() + "-" + state.getSection();
    availableCounts.put(key, state.getAvailableCount());
  }
}
```

改為：
```java
@KafkaListener(
  topics = "section-status",
  groupId = "${app.section-status.group-id}",
  containerFactory = "kafkaListenerContainerFactory"
)
public void onSectionStatus(ConsumerRecord<String, SectionStatusEvent> record) {
  SectionStatusEvent statusEvent = record.value();
  if (statusEvent != null) {
    String key = statusEvent.getEventId() + "-" + statusEvent.getSection();
    availableCounts.put(key, statusEvent.getAvailableCount());
  }
}
```

---

### Step 4: 更新 Kafka Streams Config（15 min）

在 `SeatProcessorStreamsConfig.seatPipeline()` 中：

當前：
```java
SpecificAvroSerde<SectionSeatState> seatStateSerde = newAvroSerde(serdeConfig);
// ...
.to(KafkaConstants.TOPIC_SECTION_STATUS, Produced.with(Serdes.String(), seatStateSerde));
```

改為：
```java
SpecificAvroSerde<SectionStatusEvent> statusEventSerde = newAvroSerde(serdeConfig);
// ...
// SectionInitProcessor 部分
.to(KafkaConstants.TOPIC_SECTION_STATUS, Produced.with(Serdes.String(), statusEventSerde));

// SectionStatusEmitter 部分
.process(SectionStatusEmitter::new, KafkaConstants.SEAT_INVENTORY_STORE)
.to(KafkaConstants.TOPIC_SECTION_STATUS, Produced.with(Serdes.String(), statusEventSerde));
```

---

### Step 5: 重建 Kafka Topic（必須, 5 min）

舊 schema 的消息無法被新 consumer 正確反序列化，需要重建 topic。

```bash
# 1. 刪除舊 topic
kafka-topics --bootstrap-server localhost:29092 --delete --topic section-status

# 2. 重新創建
kafka-topics --bootstrap-server localhost:29092 \
  --create \
  --topic section-status \
  --partitions 20 \
  --replication-factor 1 \
  --config min.insync.replicas=1

# 3. 檢查
kafka-topics --bootstrap-server localhost:29092 --describe --topic section-status
```

---

## 實施步驟時間線

| 步驟 | 工作項 | 耗時 | 累計 |
|------|--------|------|------|
| 1 | 定義 SectionStatusEvent.avsc | 5min | 5min |
| 2 | 修改 SectionInitProcessor | 15min | 20min |
| 3 | 修改 SectionStatusEmitter | 15min | 35min |
| 4 | 修改 SectionStatusCache | 15min | 50min |
| 5 | 更新 SeatProcessorStreamsConfig | 15min | 65min |
| 6 | 編譯測試 | 10min | 75min |
| 7 | 刪除並重建 topic | 5min | 80min |
| 8 | 啟動應用驗證 | 10min | 90min |

**總耗時**: ~1.5 小時（開發 + 測試）

---

## 效果驗證

### 消息大小變化
```
前: 100KB per message (5000 seats × 20 bytes)
後: 50 bytes per message
縮小: 2000x ✓
```

### 吞吐量提升
```
當前: 10-20K QPS (受 Kafka 序列化瓶頸)
改進後: 30-50K QPS (序列化開銷 -99%)
提升: 3x ✓
```

### 磁碟 I/O 減少
```
當前: 1000 QPS × 100KB = 100MB/sec
改進後: 1000 QPS × 50 bytes = 50KB/sec
減少: 99% ✓
```

---

## 代碼生成清單

### 新增文件
- `src/main/avro/SectionStatusEvent.avsc`

### 修改文件
- `src/main/java/com/keer/ticketmaster/ticket/stream/SectionInitProcessor.java`
- `src/main/java/com/keer/ticketmaster/ticket/stream/SectionStatusEmitter.java`
- `src/main/java/com/keer/ticketmaster/reservation/service/SectionStatusCache.java`
- `src/main/java/com/keer/ticketmaster/config/SeatProcessorStreamsConfig.java`

### 可保持不變
- `SectionSeatState.avsc` (state store 仍需要完整 map)
- 座位分配邏輯 (SeatAllocationProcessor)
- State store schema (不變)

---

## 風險與緩解

| 風險 | 嚴重度 | 緩解措施 |
|------|--------|---------|
| Schema 演化不兼容 | 🟡 | 新建 topic，無需向後兼容 |
| 消費者滯後 | 🟡 | 重建前確保 lag = 0 |
| 正式環境應用時斷連 | 🟡 | rolling restart，一次只停 1 pod |

---

## 測試計劃

### 單元測試
- SectionStatusEvent 序列化/反序列化
- SectionStatusCache 正確接收新消息格式

### 集成測試
- SeatAllocationProcessor → SectionStatusEmitter → section-status
- SectionStatusCache 消費驗證

### 性能測試
- 1000 QPS 下的序列化延遲（應降低 99%）
- 吞吐量測試（應提升 3x）

---

## 推出計劃

### 本地測試環境
1. 提交代碼
2. `./gradlew build`
3. 運行集成測試
4. `docker-compose restart`
5. 壓測驗證

### 開發環境推出
1. 備份數據
2. 刪除舊 topic
3. 部署新代碼
4. 重建 topic
5. 監控 lag 和延遲

### 正式環境推出
1. 計劃 rolling restart
2. PDB: maxUnavailable=1
3. 一個 pod 一個 pod 重啟
4. 監控 SectionStatusCache miss rate
5. 監控 consumer lag
