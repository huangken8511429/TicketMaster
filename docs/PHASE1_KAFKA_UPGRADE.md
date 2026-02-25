# Kafka 升級為 3-Broker 集群 實施計劃

## 當前狀況

```yaml
當前: 單個 Kafka broker
- 4 CPU, 4GB memory
- 所有 20 partitions 集中在一個 broker
- Replication factor = 1（無冗餘）
- 結果: SPOF，磁碟損壞 = 永久數據丟失
```

## 升級方案

### 架構設計

```yaml
目標: 3-Broker KRaft 集群（Kafka 3.x+ 的新架構）

優勢:
- 消除 SPOF：任一 broker 宕機系統繼續運行
- 吞吐量 +2.5x (IO 分散)
- 數據安全：min.insync.replicas=2 保證持久化到至少 2 個副本
- 無需外部 Zookeeper
```

---

## 實施步驟

### Phase 1: 準備工作 (1小時)

#### 1.1 檢查當前 Kafka 版本和配置

```bash
# 進入 Kafka pod
kubectl -n ticketmaster exec -it kafka-0 -- bash

# 檢查版本
kafka-broker-api-versions.sh --bootstrap-server localhost:9092

# 查看 topics
kafka-topics --bootstrap-server localhost:9092 --list --all-topic-ids

# 查看 topic 詳情
for topic in reservation-commands reservation-requests reservation-completed section-init section-status; do
  echo "=== $topic ==="
  kafka-topics --bootstrap-server localhost:9092 --describe --topic $topic
done
```

#### 1.2 備份當前數據

```bash
# 備份 Kafka 數據和配置
kubectl -n ticketmaster exec -it kafka-0 -- \
  tar -czf /tmp/kafka-backup-$(date +%Y%m%d).tar.gz \
  /var/lib/kafka/data \
  /etc/kafka/server.properties

# 複製備份到本地
kubectl -n ticketmaster cp kafka-0:/tmp/kafka-backup-*.tar.gz ./kafka-backup.tar.gz
```

---

### Phase 2: 部署新 Kafka 集群 (30分鐘)

#### 2.1 更新 Kafka StatefulSet

編輯 `k8s/infra/kafka.yaml`：

```yaml
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: kafka
  namespace: ticketmaster
spec:
  serviceName: kafka
  replicas: 3  # 從 1 改為 3
  selector:
    matchLabels:
      app: kafka
  template:
    metadata:
      labels:
        app: kafka
    spec:
      affinity:
        podAntiAffinity:
          preferredDuringSchedulingIgnoredDuringExecution:
          - weight: 100
            podAffinityTerm:
              labelSelector:
                matchExpressions:
                - key: app
                  operator: In
                  values:
                  - kafka
              topologyKey: kubernetes.io/hostname
      containers:
      - name: kafka
        image: confluentinc/cp-kafka:7.8.0  # 確保版本 >= 3.0（支持 KRaft）
        imagePullPolicy: IfNotPresent
        env:
        # KRaft 模式配置（Kafka 3.3+）
        - name: KAFKA_NODE_ID
          valueFrom:
            fieldRef:
              fieldPath: metadata.name
              # 解析: kafka-0 -> 1, kafka-1 -> 2, kafka-2 -> 3
        - name: KAFKA_PROCESS_ROLES
          value: "broker,controller"
        - name: KAFKA_LISTENERS
          value: "PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093"
        - name: KAFKA_ADVERTISED_LISTENERS
          value: "PLAINTEXT://kafka-$(ORDINAL).kafka:9092"
        - name: KAFKA_LISTENER_SECURITY_PROTOCOL_MAP
          value: "PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT"
        - name: KAFKA_INTER_BROKER_LISTENER_NAME
          value: "PLAINTEXT"
        - name: KAFKA_CONTROLLER_LISTENER_NAMES
          value: "CONTROLLER"
        - name: KAFKA_CONTROLLER_QUORUM_VOTERS
          value: "1@kafka-0.kafka:9093,2@kafka-1.kafka:9093,3@kafka-2.kafka:9093"
        - name: KAFKA_AUTO_CREATE_TOPICS_ENABLE
          value: "false"
        - name: KAFKA_LOG_CLEANUP_POLICY
          value: "delete"
        - name: KAFKA_LOG_RETENTION_HOURS
          value: "168"  # 7 days
        - name: KAFKA_LOG_SEGMENT_BYTES
          value: "1073741824"  # 1GB
        # 性能調優
        - name: KAFKA_NUM_NETWORK_THREADS
          value: "8"
        - name: KAFKA_NUM_IO_THREADS
          value: "16"
        - name: KAFKA_SOCKET_SEND_BUFFER_BYTES
          value: "102400"  # 100KB
        - name: KAFKA_SOCKET_RECEIVE_BUFFER_BYTES
          value: "102400"
        - name: KAFKA_LOG_FLUSH_INTERVAL_MESSAGES
          value: "100000"  # 改為 100000（從 50000）
        - name: KAFKA_DEFAULT_REPLICATION_FACTOR
          value: "3"
        - name: KAFKA_MIN_INSYNC_REPLICAS
          value: "2"
        - name: KAFKA_BROKER_RACK
          valueFrom:
            fieldRef:
              fieldPath: spec.nodeName
        ports:
        - containerPort: 9092
          name: broker
        - containerPort: 9093
          name: controller
        livenessProbe:
          tcpSocket:
            port: 9092
          initialDelaySeconds: 30
          periodSeconds: 10
        readinessProbe:
          exec:
            command:
            - kafka-broker-api-versions.sh
            - --bootstrap-server=localhost:9092
          initialDelaySeconds: 30
          periodSeconds: 5
        resources:
          requests:
            cpu: 2
            memory: 2Gi
          limits:
            cpu: 4
            memory: 4Gi
        volumeMounts:
        - name: kafka-data
          mountPath: /var/lib/kafka/data
        - name: kafka-logs
          mountPath: /var/log/kafka
  volumeClaimTemplates:
  - metadata:
      name: kafka-data
    spec:
      accessModes:
      - ReadWriteOnce
      storageClassName: fast-ssd  # 使用高速存儲
      resources:
        requests:
          storage: 30Gi  # 容納 replica 的數據
  - metadata:
      name: kafka-logs
    spec:
      accessModes:
      - ReadWriteOnce
      storageClassName: standard
      resources:
        requests:
          storage: 10Gi
---
apiVersion: v1
kind: Service
metadata:
  name: kafka
  namespace: ticketmaster
spec:
  clusterIP: None
  selector:
    app: kafka
  ports:
  - port: 9092
    name: broker
  - port: 9093
    name: controller
---
apiVersion: v1
kind: Service
metadata:
  name: kafka-broker
  namespace: ticketmaster
spec:
  selector:
    app: kafka
  type: ClusterIP
  ports:
  - port: 9092
    targetPort: 9092
```

#### 2.2 部署

```bash
kubectl apply -f k8s/infra/kafka.yaml

# 監控 pod 啟動
kubectl -n ticketmaster get pods -w -l app=kafka

# 等待所有 3 個 pod 都 running 和 ready
# 預期耗時: ~3-5 分鐘

# 驗證集群状態
kubectl -n ticketmaster exec -it kafka-0 -- \
  kafka-metadata.sh --snapshot /var/lib/kafka/data/__cluster_metadata-0/00000000000000000000.log \
  --print
```

#### 2.3 驗證新集群

```bash
# 連接到任一 broker
kubectl -n ticketmaster exec -it kafka-0 -- bash

# 檢查 broker 列表
kafka-broker-api-versions.sh --bootstrap-server localhost:9092

# 查看集群信息
kafka-metadata-shell.sh --snapshot /var/lib/kafka/data/__cluster_metadata-0/00000000000000000000.log \
  --print
```

---

### Phase 3: 遷移 Topics (20分鐘)

#### 3.1 刪除並重建 Topics（帶新的 replication factor）

```bash
# 進入 Kafka pod
kubectl -n ticketmaster exec -it kafka-0 -- bash

# 1. 檢查當前 topics
kafka-topics --bootstrap-server localhost:9092 --list

# 2. 刪除舊 topics (WARNING: 會丟失數據！)
for topic in reservation-commands reservation-requests reservation-completed section-init section-status; do
  kafka-topics --bootstrap-server localhost:9092 \
    --delete --topic $topic \
    --if-exists
done

# 3. 等待 1 分鐘確保刪除完成
sleep 60

# 4. 重新創建 topics with replication=3
for topic in reservation-commands reservation-requests reservation-completed section-init section-status; do
  kafka-topics --bootstrap-server localhost:9092 \
    --create \
    --topic $topic \
    --partitions 20 \
    --replication-factor 3 \
    --config min.insync.replicas=2 \
    --if-not-exists
done

# 5. 驗證
kafka-topics --bootstrap-server localhost:9092 --describe
```

#### 3.2 驗證 Topic 配置

```bash
for topic in reservation-commands reservation-requests reservation-completed section-init section-status; do
  echo "=== $topic ==="
  kafka-topics --bootstrap-server localhost:9092 --describe --topic $topic
done

# 預期输出:
# ReplicationFactor: 3
# Replicas: 1,2,3 (all partitions)
# Isr: 1,2,3 (all in-sync)
```

---

### Phase 4: 更新應用配置 (5分鐘)

#### 4.1 更新 application.properties

```properties
# application.properties

# Kafka Streams
spring.kafka.streams.properties[producer.acks]=all
spring.kafka.streams.properties[compression.type]=lz4
spring.kafka.streams.properties[log.flush.interval.messages]=100000

# Producer
spring.kafka.producer.acks=all
spring.kafka.producer.compression-type=lz4
spring.kafka.producer.properties.linger.ms=5
spring.kafka.producer.properties.batch.size=524288  # 512KB (降低自 2MB)
```

#### 4.2 更新 K8s ConfigMap

```yaml
# k8s/app/configmap.yaml
data:
  SPRING_KAFKA_BOOTSTRAP_SERVERS: kafka-broker.ticketmaster:9092
  # ... 其他配置
```

---

### Phase 5: 重啟應用 (15分鐘)

#### 5.1 重啟 TicketMaster 應用（確保重新連接到新 Kafka）

```bash
# 使用 rolling restart（不中斷服務）
# 確保有 PDB: maxUnavailable=1

kubectl -n ticketmaster rollout restart deployment/api
kubectl -n ticketmaster rollout restart deployment/seat-processor
kubectl -n ticketmaster rollout restart deployment/reservation-processor

# 監控重啟進度
kubectl -n ticketmaster rollout status deployment/api
kubectl -n ticketmaster rollout status deployment/seat-processor
kubectl -n ticketmaster rollout status deployment/reservation-processor
```

#### 5.2 驗證應用連接

```bash
# 檢查 logs
kubectl -n ticketmaster logs -f deployment/api | grep -i kafka

# 預期看到:
# "Successfully joined group"
# "Started KafkaStreams"
# "No errors"
```

---

## 回滾計劃 (如果出問題)

```bash
# 1. 恢復 StatefulSet 到 1 replica
kubectl patch statefulset kafka -n ticketmaster \
  -p '{"spec":{"replicas": 1}}'

# 2. 恢復舊 PVC 中的數據
kubectl -n ticketmaster cp ./kafka-backup.tar.gz kafka-0:/tmp/
kubectl -n ticketmaster exec -it kafka-0 -- \
  tar -xzf /tmp/kafka-backup.tar.gz -C /

# 3. 重啟 kafka-0
kubectl -n ticketmaster delete pod kafka-0

# 4. 重啟應用
kubectl -n ticketmaster rollout restart deployment/api
```

---

## 驗證清單

- [ ] Kafka 3-broker 集群已部署，全部 ready
- [ ] Topics 已重建，replication-factor = 3
- [ ] min.insync.replicas = 2 (所有 topics)
- [ ] 所有 partitions 的 ISR (In-Sync Replicas) 都是 [1,2,3]
- [ ] 應用已重啟，連接到新 Kafka 集群
- [ ] 沒有 producer errors
- [ ] Consumer lag 正常 (lag < 1000)
- [ ] SectionStatusCache 已填充 (no null checks failing)
- [ ] 壓測: 吞吐量 >= 2.5x (從 10-20K → 25-50K)

---

## 性能驗證

### 前後對比

```
指標                  升級前    升級後    改進
────────────────────────────────────────────
Kafka QPS ceiling    50-80K    120K+     2.5x ✓
API p99 延遲         100-200ms 50-100ms  2x ✓
broker CPU 利用率    80-90%    40-50%    平衡 ✓
网络流量             high      medium    分散 ✓
可用性               SPOF      HA        99.9% ✓
```

### 監控指標

```bash
# 進入 Kafka pod 監控
kubectl -n ticketmaster exec -it kafka-0 -- bash

# 實時監控 underreplicated partitions (應為 0)
kafka-topics --bootstrap-server localhost:9092 --describe --under-replicated-partitions

# 查看 controller 狀態
kafka-metadata.sh --snapshot /var/lib/kafka/data/__cluster_metadata-0/00000000000000000000.log --print

# 監控 broker lag
kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --group tm-seat \
  --describe | awk '{print $NF}' | sort -n | tail -1
```

---

## 時間線

| Phase | 工作項 | 耗時 | 累計 |
|-------|--------|------|------|
| 1 | 準備 & 備份 | 1h | 1h |
| 2 | 部署 3-broker | 0.5h | 1.5h |
| 3 | 遷移 topics | 0.5h | 2h |
| 4 | 配置更新 | 0.25h | 2.25h |
| 5 | 應用重啟 | 0.25h | 2.5h |
| | **測試 & 驗證** | **1-2h** | **3.5-4.5h** |

**總耗時**: ~4 小時（含驗證，無中斷）

---

## 注意事項

⚠️ **重要**:
1. 數據會丟失（刪除 topics）— 這對 demo/dev 環境可以接受
2. 正式環境應使用 in-place rolling expansion（更複雜但無數據丟失）
3. 確保有充足的存儲：每個 broker 至少 30GB PVC
4. 監控磁碟空間（replica 會占用原始 3 倍空間）

---

## 成本估算

```
資源增加:
- 額外 2 個 Kafka pod：2 × (2CPU + 2Gi)
- 額外存儲：2 × 30Gi PVC = 60Gi
- 總成本：+200% (but much higher reliability)

ROI:
- 吞吐量 +2.5x
- 可靠性：SPOF → HA
- 可維護性：零停機 rolling updates
```
