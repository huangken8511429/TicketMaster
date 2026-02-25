# TicketMaster 架構改進建議 (2026-02-25)

> 基於架構評估報告的優先級改進路線圖

## 📊 系統當前狀況

### 優勢 ⭐⭐⭐⭐⭐
- **無鎖數據流設計**：通過 Kafka Streams partition-level 線性處理，優雅地避開了悲觀鎖和樂觀鎖的陷阱
- **Pre-filter 快速拒絕**：SectionStatusCache 在庫存不足時直接拒絕，避免請求進入 Kafka
- **DeferredResult 異步模式**：配合 Virtual Threads，能支撐大量併發連接
- **三角色獨立部署**：Spring Profiles + K8s 原生支持獨立擴容

### 瓶頸 ⚠️⚠️⚠️
| 瓶頸 | 嚴重度 | 影響 | 估算 |
|------|--------|------|------|
| **Kafka 單 Broker** | 🔴 Critical | SPOF，系統級故障 | 吞吐上限 50-80K msg/sec |
| **SectionSeatState 大 Map** | 🔴 Critical | 序列化開銷巨大 | ~100KB/消息 |
| **Partition/Thread 失配** | 🟡 High | 資源浪費，rebalance 複雜 | 40 threads vs 20 partitions |
| **缺少故障恢復優化** | 🟡 High | pod 重啟後數據丟失 | RocksDB 回放 1-10min |
| **預訂結果不持久化** | 🟡 High | 無審計日誌，查詢不可靠 | 只存 Kafka state store |

---

## 🚀 Phase 1: 基礎修復 (1-2週) - ROI 最高

### 改進 1.1: 修復 seat-processor.yaml 語法錯誤 ⏱️ 5min
```diff
  spec:
-   replicas: 5
    replicas: 10
```
**位置**: `k8s/app/seat-processor.yaml:7-8`
**影響**: 無，但代碼不清晰
**優先級**: Low (代碼品質)

---

### 改進 1.2: Stream Threads 對齊 Partitions ⏱️ 15min
```properties
# application.properties
num.stream.threads=2  # 從 4 改為 2
```

**為什麼**:
- 10 pods × 4 threads = 40 threads
- 但只有 20 partitions
- 結果：20 個線程空閒，CPU 調度開銷大

**改為**:
- 10 pods × 2 threads = 20 threads = 20 partitions
- 每個線程對應一個 partition task，完全飽和

**效果**:
- CPU 使用率 +50% 更有效率
- Rebalance 複雜度 -50%
- Zero 成本改進

---

### 改進 1.3: SectionSeatState 瘦身 (關鍵) ⏱️ 2-3天

**問題分析**：

```java
// 當前實現
public class SectionSeatState {
  public Map<String, String> seatStatuses;  // 5000 座位 = ~100KB!
}

// 每次座位分配都要序列化整個 map
// section-status topic 發送整個 map
// 每個 API 實例都要反序列化整個 map
```

**性能影響**：
- 1000 QPS × 100KB = **100MB/sec** 序列化開銷
- Kafka broker 磁碟 I/O 爆表
- 網路頻寬浪費

**解決方案**：

```avsc
// 保留原有 SectionSeatState for state store (座位分配邏輯需要)
// 新增 SectionStatusEvent for topic (只發 count)
{
  "type": "record",
  "name": "SectionStatusEvent",
  "fields": [
    {"name": "eventId", "type": "string"},
    {"name": "section", "type": "string"},
    {"name": "availableCount", "type": "int"}
  ]
}

// 修改 SectionStatusEmitter
// 只發送 count，不發送 seatStatuses
```

**改進步驟**：
1. 定義新 Avro schema `SectionStatusEvent`
2. 修改 `SectionStatusEmitter.processAndForward()` 只發送 count
3. SectionStatusCache 只需要讀 count（現在也是這樣）
4. 重建 section-status topic

**效果**：
- 消息大小：100KB → 50 bytes (2000x 縮小)
- 序列化延遲 -99%
- **吞吐提升 3x** (從 10-20K → 30-50K)
- Kafka broker 磁碟壓力 -99%

**🎯 最高優先級**：這個改進單獨就能提升 3 倍吞吐

---

### 改進 1.4: PodDisruptionBudget ⏱️ 1天

```yaml
# k8s/app/pod-disruption-budgets.yaml
---
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: api-pdb
  namespace: ticketmaster
spec:
  maxUnavailable: 1
  selector:
    matchLabels:
      app: api
---
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: seat-processor-pdb
  namespace: ticketmaster
spec:
  maxUnavailable: 1
  selector:
    matchLabels:
      app: seat-processor
---
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: reservation-processor-pdb
  namespace: ticketmaster
spec:
  maxUnavailable: 1
  selector:
    matchLabels:
      app: reservation-processor
```

**為什麼**：Kafka Streams rebalance 是個重操作，如果多個 pod 同時下線，會造成長時間的數據不可用和延遲劇增。PDB 確保每次只有 1 個 pod 下線。

**效果**：
- Rolling update 時無中斷 → SLA 更好
- Rebalance 時間可控

---

## 📈 Phase 2: 韌性提升 (2-4週) - 必須做

### 改進 2.1: Kafka 升級為 3-Broker 集群 ⏱️ 1週

**現狀**：
- 單 broker (4 CPU, 4GB)
- 1 replica for all topics
- → SPOF，磁碟損壞 = 永久數據丟失

**升級方案**：

```yaml
# k8s/infra/kafka.yaml (簡化視圖)
---
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: kafka
spec:
  replicas: 3  # 從 1 改為 3
  serviceName: kafka
  template:
    spec:
      containers:
      - name: kafka
        env:
        - name: KAFKA_BROKER_RACK
          valueFrom:
            fieldRef:
              fieldPath: spec.nodeName
        - name: KAFKA_AUTO_CREATE_TOPICS_ENABLE
          value: "false"
        volumeMounts:
        - name: kafka-data
          mountPath: /var/lib/kafka/data
  volumeClaimTemplates:
  - metadata:
      name: kafka-data
    spec:
      accessModes: [ReadWriteOnce]
      resources:
        requests:
          storage: 20Gi

---
# 重建所有 topics with replicas=3
apiVersion: v1
kind: ConfigMap
metadata:
  name: kafka-topics
data:
  create-topics.sh: |
    for topic in reservation-commands reservation-requests reservation-completed section-init section-status; do
      kafka-topics --bootstrap-server kafka-0.kafka:9092 \
        --create --topic $topic \
        --partitions 20 \
        --replication-factor 3 \
        --config min.insync.replicas=2
    done
```

**配置變更**：

```properties
# kafka server.properties
log.replication.factor=3
min.insync.replicas=2
default.replication.factor=3
unclean.leader.election.enable=false
log.flush.interval.messages=100000  # 從 50000 改為 100000

# producer 配置 (application.properties)
spring.kafka.producer.acks=all
```

**效果**：
- 消除 SPOF → 任一 broker 宕機系統繼續運行
- 吞吐量 +2.5x (IO 分散到 3 個 broker)
- 數據安全：min.insync.replicas=2 保證持久化

**估算成本**：
- 存儲增加 3x (但可接受)
- 網路流量增加 2x (replica 同步)
- 值得：生產環境必須做

**🎯 必須優先級**

---

### 改進 2.2: Seat Processor 改為 StatefulSet + PVC ⏱️ 1-2天

**現狀**：Deployment with ephemeral storage
- Pod 重調度 → RocksDB 數據丟失
- 需要從 Kafka changelog topic 完整回放
- 回放時間：**1-10 分鐘**（取決於數據量）
- 期間：座位分配完全不可用

**改為 StatefulSet**：

```yaml
# k8s/app/seat-processor.yaml (替換 Deployment)
---
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: seat-processor
  namespace: ticketmaster
spec:
  serviceName: seat-processor
  replicas: 10
  selector:
    matchLabels:
      app: seat-processor
  template:
    metadata:
      labels:
        app: seat-processor
    spec:
      containers:
      - name: seat-processor
        image: ticketmaster:latest
        env:
        - name: SPRING_PROFILES_ACTIVE
          value: seat-processor
        - name: SPRING_KAFKA_STREAMS_PROPERTIES_STATE_DIR
          value: /data/kafka-streams
        volumeMounts:
        - name: rocksdb-data
          mountPath: /data
        # ... 其他配置
        resources:
          requests:
            cpu: 2
            memory: 2Gi
          limits:
            cpu: 4
            memory: 4Gi
  volumeClaimTemplates:
  - metadata:
      name: rocksdb-data
    spec:
      accessModes: [ReadWriteOnce]
      storageClassName: fast-ssd  # 使用快速存儲
      resources:
        requests:
          storage: 5Gi
```

**效果**：
- Pod 重調度 → PVC 掛載到同一 pod
- RocksDB 數據完整，無需回放
- 故障恢復時間：**<1 分鐘** (vs 1-10min)
- 可用性提升 10x

**存儲考慮**：
- 每個 pod 5GB PVC
- 10 pods = 50GB total (可接受)
- 使用高速存儲 class (SSD/NVMe)

**🎯 高優先級**：生產環境故障恢復 critical

---

### 改進 2.3: Reservation 結果持久化到 PostgreSQL ⏱️ 1-2天

**現狀**：預訂流程全走 Kafka，從不寫 PostgreSQL
```java
@Entity
public class Reservation {
  // 這些字段從未被填充！
  private Status status;        // PENDING/CONFIRMED/CANCELLED
  private Instant completedAt;
}
```

**影響**：
- 沒有審計日誌 → 無法追蹤誰在何時預訂了什麼
- 無法用 SQL 查詢歷史 → 必須從 Kafka 讀
- 故障風險：Kafka state store 損壞 → 無 fallback

**改進方案**：

```java
// 新增 consumer: reservation-completed → PostgreSQL
@Service
@Profile("api")
public class ReservationPersistenceService {

  @KafkaListener(topics = "reservation-completed")
  public void persistCompletion(ReservationCompletedEvent event) {
    Reservation reservation = reservationRepository.findById(event.getReservationId());
    reservation.setStatus(Status.CONFIRMED);
    reservation.setCompletedAt(Instant.now());
    reservation.setEventId(event.getEventId());
    reservation.setSection(event.getSection());
    reservation.setSeats(event.getSeats());
    reservationRepository.save(reservation);
  }
}
```

**DB 配置**：
```sql
-- 新增索引以支持審計查詢
CREATE INDEX idx_reservation_user_time ON reservation(user_id, completed_at DESC);
CREATE INDEX idx_reservation_event ON reservation(event_id, completed_at DESC);

-- 存儲過程：每日歸檔舊數據
CREATE PROCEDURE archive_old_reservations() AS $$
  INSERT INTO reservation_archive
  SELECT * FROM reservation WHERE completed_at < NOW() - INTERVAL '90 days';
  DELETE FROM reservation WHERE completed_at < NOW() - INTERVAL '90 days';
$$;
```

**效果**：
- 完整審計日誌
- SQL 可查詢
- Kafka state store 損壞時有安全網

---

### 改進 2.4: 用戶級去重 ⏱️ 1天

**問題**：客戶端短時間內重試相同預訂請求
```
用戶 click 預訂按鈕
→ POST /reservations (timeout 因為網路慢)
→ 用戶再次 click (自動重試)
→ 生成 2 個不同 reservationId 的預訂
```

**解決方案**：Redis dedup

```java
@Service
public class ReservationService {

  public ReservationResponse createReservation(CreateReservationRequest req) {
    // 去重 key: userId:eventId:section
    String dedupKey = req.getUserId() + ":" + req.getEventId() + ":" + req.getSection();

    // 檢查：最近 1 小時是否預訂過同一 section
    String existingReservationId = redisTemplate.opsForValue().get(dedupKey);
    if (existingReservationId != null) {
      // 直接返回之前的預訂，不重複提交
      return getExistingReservation(existingReservationId);
    }

    // 真正提交預訂
    ReservationCompletedEvent result = submitReservation(req);
    String reservationId = result.getReservationId();

    // 記錄：1 小時內不重複提交
    redisTemplate.opsForValue().set(
      dedupKey,
      reservationId,
      Duration.ofHours(1)
    );

    return new ReservationResponse(reservationId, result);
  }
}
```

**效果**：
- 防止重複預訂
- 重試安全
- 改善 UX：快速返回結果 vs Kafka 排隊

---

## 🎯 Phase 3: 性能與可觀測性 (4-8週)

### 改進 3.1: HPA (Horizontal Pod Autoscaler) ⏱️ 1day

```yaml
# k8s/app/api-hpa.yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: api-hpa
  namespace: ticketmaster
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: api
  minReplicas: 3
  maxReplicas: 20
  metrics:
  - type: Resource
    resource:
      name: cpu
      target:
        type: Utilization
        averageUtilization: 70
  behavior:
    scaleUp:
      stabilizationWindowSeconds: 30
      policies:
      - type: Percent
        value: 100
        periodSeconds: 30
    scaleDown:
      stabilizationWindowSeconds: 300
      policies:
      - type: Pods
        value: 1
        periodSeconds: 60
```

**為什麼只 HPA API**：
- Kafka Streams rebalance 成本高 → 固定 seat/reservation 副本數
- API 層無狀態 → 快速擴縮容無影響

**效果**：
- 低峰時 3 pods → 節省成本 70%
- 高峰時自動擴到 20 pods
- SLA 更好

---

### 改進 3.2: Redis Cache for Tickets ⏱️ 1-2天

```java
@Service
public class TicketService {

  public List<Ticket> getAvailableTicketsByEvent(String eventId) {
    // 1. Check Redis cache
    String cacheKey = "tickets:event:" + eventId;
    List<Ticket> cached = redisTemplate.opsForValue().get(cacheKey);
    if (cached != null) {
      return cached;
    }

    // 2. Query from DB if cache miss
    List<Ticket> tickets = ticketRepository.findAvailableByEvent(eventId);

    // 3. Cache with 10 second TTL
    redisTemplate.opsForValue().set(
      cacheKey,
      tickets,
      Duration.ofSeconds(10)
    );

    return tickets;
  }
}

// 當 section-status 更新時，使 ticket cache 失效
@KafkaListener(topics = "section-status")
public void invalidateTicketCache(SectionStatusEvent event) {
  String cacheKey = "tickets:event:" + event.getEventId();
  redisTemplate.delete(cacheKey);
}
```

**效果**：
- P99 延遲 -50%
- PostgreSQL 查詢減少 90%
- 搶票高峰期查詢快速返回

---

### 改進 3.3: OTel 正式啟用 + 監控 ⏱️ 1day

```yaml
# k8s/app/configmap.yaml
data:
  # 啟用 tracing
  MANAGEMENT_TRACING_ENABLED: "true"
  MANAGEMENT_OTLP_METRICS_EXPORT_ENABLED: "true"
  MANAGEMENT_OTLP_TRACING_ENDPOINT: "http://otel-collector.observability:4318"

  # Sampling: 避免 trace 數據爆炸
  OTEL_TRACES_SAMPLER: "traceidratio"
  OTEL_TRACES_SAMPLER_ARG: "0.1"  # 10% sampling
```

**Grafana Dashboard**：

```
行 1 (吞吐):
  - kafka.producer.record-send-total (QPS)
  - kafka.consumer.records-consumed-total

行 2 (延遲):
  - kafka.streams.stream-thread.process-latency-avg
  - http.server.request.duration (P50/P95/P99)

行 3 (健康):
  - kafka.consumer.records-lag-max ⚠️ 最關鍵
  - kafka.topic.size (磁碟使用)
  - rocksdb.write-latency-avg

行 4 (應用):
  - pending_requests (DeferredResult)
  - pre_filter_rejection_rate
  - reservation_timeout_rate
```

**關鍵告警**：

```yaml
# Prometheus alert rules
groups:
- name: ticketmaster
  rules:
  - alert: HighConsumerLag
    expr: kafka_consumer_records_lag_max > 10000
    for: 1m
    severity: critical

  - alert: HighTimeoutRate
    expr: rate(reservation_timeout_total[1m]) > 0.05
    for: 30s
    severity: critical

  - alert: SeatProcessorSlowdown
    expr: kafka_streams_stream_thread_process_latency_avg > 50
    for: 1m
    severity: warning

  - alert: KafkaDiskFull
    expr: kafka_topic_size_bytes > 100 * 1024 * 1024 * 1024
    for: 5m
    severity: warning
```

**效果**：
- 完整可觀測性 ✓
- 故障秒級發現
- 根因分析有數據支撐

---

### 改進 3.4: SectionStatusCache 預熱 ⏱️ 1day

**問題**：系統啟動時 cache 為空，早期請求都進入 Kafka

**解決方案**：

```java
@Component
public class SectionStatusCacheWarmer {

  @Autowired
  private SectionStatusCache cache;

  @Autowired
  private KafkaTemplate<String, SectionStatusEvent> kafkaTemplate;

  @Bean
  public ApplicationRunner warmupCache() {
    return args -> {
      // 1. 創建 consumer，從 earliest 讀取
      Map<String, Object> consumerProps = new HashMap<>();
      consumerProps.put("bootstrap.servers", kafkaProperties.getBootstrapServers());
      consumerProps.put("group.id", "cache-warmer");
      consumerProps.put("auto.offset.reset", "earliest");

      KafkaConsumer<String, SectionStatusEvent> consumer = new KafkaConsumer<>(consumerProps);
      consumer.subscribe(Arrays.asList("section-status"));

      // 2. 消費所有消息到 cache
      int emptyPollCount = 0;
      while (emptyPollCount < 10) {  // 10 次空 poll 則認為完成
        ConsumerRecords<String, SectionStatusEvent> records = consumer.poll(Duration.ofSeconds(1));
        if (records.isEmpty()) {
          emptyPollCount++;
        } else {
          emptyPollCount = 0;
          for (ConsumerRecord<String, SectionStatusEvent> record : records) {
            cache.put(record.value());
          }
        }
      }

      consumer.close();
      logger.info("Cache warmed up with {} entries", cache.size());
    };
  }

  // Readiness probe: 只在 cache 預熱完後才返回 ready
  @GetMapping("/health/ready")
  public ResponseEntity<?> readiness() {
    if (cache.isEmpty()) {
      return ResponseEntity.status(503).build();
    }
    return ResponseEntity.ok("Ready");
  }
}
```

**配置**：

```yaml
# k8s/app/api.yaml
readinessProbe:
  httpGet:
    path: /health/ready  # 而非 /actuator/health
    port: 8080
  initialDelaySeconds: 30  # 給予足夠時間預熱
  periodSeconds: 5
```

**效果**：
- 啟動時 cache 100% 填滿
- 無啟動風暴
- readiness probe 確保服務真正準備好

---

## 📋 最終改進檢查清單

### Phase 1 (立即執行)
- [ ] 修復 seat-processor.yaml 重複 replicas
- [ ] 修改 num.stream.threads=2
- [ ] SectionSeatState 瘦身 (定義新 schema + 修改 emitter)
- [ ] 添加所有 deployment 的 PDB

**預期耗時**: 1-2 週
**預期收益**: 吞吐 3x + 資源效率 +50%

### Phase 2 (1 個月內)
- [ ] Kafka 升級 3-broker + topic replication=3
- [ ] seat-processor 改為 StatefulSet
- [ ] ReservationPersistenceService 實現
- [ ] Redis dedup 邏輯

**預期耗時**: 2-4 週
**預期收益**: 吞吐 2.5x + 故障恢復 10x + 審計完整

### Phase 3 (2 個月內)
- [ ] HPA 配置
- [ ] Ticket Redis cache
- [ ] OTel 啟用 + Grafana dashboard
- [ ] Cache warmer
- [ ] 壓測驗證

**預期耗時**: 4-8 週
**預期收益**: SLA 提升 + 成本優化 + 完整可觀測性

---

## 📊 效果量化

| 指標 | 現狀 | Phase 1 | Phase 1+2 | Phase 1+2+3 |
|------|------|---------|-----------|------------|
| **吞吐量** (QPS) | 10-20K | 30-50K | 50-100K | 100K+ |
| **P99 延遲** | 100-200ms | 50-100ms | 20-50ms | 10-20ms |
| **故障恢復** | 1-10min | 1-10min | <1min | <1min |
| **API 副本** | 10 固定 | 10 固定 | 10 固定 | 3-20 auto |
| **Kafka SPOF** | 是 | 是 | 否 | 否 |
| **審計日誌** | 否 | 否 | 是 | 是 |
| **可觀測性** | 基礎 | 基礎 | 基礎 | 完整 |

---

## 🎯 核心建議優先順序

1. **立即 (週內)**: 修復語法 + num.stream.threads + SectionSeatState 瘦身 → **3x 吞吐立竿見影**
2. **1 個月內**: Kafka HA + StatefulSet → **生產可靠性基礎**
3. **2 個月內**: HPA + OTel → **運維成熟度**

祝你改進順利！🚀
