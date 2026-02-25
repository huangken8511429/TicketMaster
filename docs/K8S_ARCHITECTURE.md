# TicketMaster Kubernetes 架構指南

## 📋 目錄
1. [整體架構](#整體架構)
2. [應用層詳解](#應用層詳解)
3. [基礎設施層詳解](#基礎設施層詳解)
4. [服務通訊架構](#服務通訊架構)
5. [數據流程](#數據流程)
6. [Kubernetes 配置詳解](#kubernetes-配置詳解)
7. [高併發優化設計](#高併發優化設計)
8. [部署命令](#部署命令)

---

## 整體架構

```
┌─────────────────────────────────────────────────────────────┐
│                    Kubernetes Cluster                        │
│           Namespace: ticketmaster                            │
├─────────────────────────────────────────────────────────────┤
│  ┌──────────────────────────────────────────────────────┐   │
│  │          應用層 (Application Services)                │   │
│  ├──────────────────────────────────────────────────────┤   │
│  │ • API (10 pods)              → REST API 入口            │   │
│  │ • Reservation Processor (10) → 預定業務邏輯            │   │
│  │ • Seat Processor (10)        → 座位狀態管理            │   │
│  └──────────────────────────────────────────────────────┘   │
│  ┌──────────────────────────────────────────────────────┐   │
│  │          基礎設施層 (Infrastructure)                  │   │
│  ├──────────────────────────────────────────────────────┤   │
│  │ Kafka (1) ←→ Schema Registry (1)                      │   │
│  │    ↓                                                  │   │
│  │ PostgreSQL (1)  +  Redis (1)                         │   │
│  └──────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

---

## 應用層詳解

### 1. API Service (10 replicas)
**檔案**: `k8s/app/api.yaml`

**用途**:
- REST API 入口，供客戶端調用
- 接收所有來自外部的 HTTP 請求
- 使用 DeferredResult 管理非同步響應

**配置**:
```yaml
Replicas: 10
資源配置:
  requests: CPU 1, Memory 1Gi
  limits:   CPU 4, Memory 2Gi

JVM 參數:
  -XX:+UseZGC -XX:+ZGenerational  # ZGC 低延遲垃圾回收
  -Xmx1g -Xms1g                   # 1GB 堆大小
  -XX:+AlwaysPreTouch             # 預熱物理記憶體

服務:
  type: LoadBalancer
  port: 8080

健康檢查:
  readinessProbe: /actuator/health (初始延遲 15s)
  livenessProbe:  /actuator/health (初始延遲 30s)
```

**高併發特性**:
- ZGC GC：延遲低於 10ms，適合實時應用
- 大堆 + 預熱：減少 GC 暫停
- 負載均衡器（LoadBalancer）：分散外部流量到 10 個 pod

---

### 2. Reservation Processor (10 replicas)
**檔案**: `k8s/app/reservation-processor.yaml`

**用途**:
- 核心業務邏輯：處理高併發訂票
- Kafka Streams 拓撲：訂票流程編排
- 與 API 通訊：暴露 HTTP 端點供 API 查詢預定狀態

**配置**:
```yaml
Replicas: 10
資源配置:
  requests: CPU 1, Memory 1Gi
  limits:   CPU 2, Memory 2Gi

Kafka Streams 設定:
  POD_IP: 動態注入（支持交互式查詢）
  KAFKA_STREAMS_APP_SERVER: $(POD_IP):8180

JVM 參數:
  -XX:+UseZGC -XX:+ZGenerational
  -Xmx1g -Xms1g -XX:+AlwaysPreTouch

服務:
  port: 8180

健康檢查:
  readinessProbe: /actuator/health (初始延遲 30s)
  livenessProbe:  /actuator/health (初始延遲 60s)
```

**高併發特性**:
- Kafka Streams：無鎖資料流設計
- 分區併行：多個 pod 並行處理不同分區
- Pod IP 路由：支持交互式狀態查詢（IQ）
- WAL 事件重放：故障恢復無數據丟失

---

### 3. Seat Processor (10 replicas)
**檔案**: `k8s/app/seat-processor.yaml`

**用途**:
- 座位狀態流處理
- 維護 RocksDB 本地狀態存儲
- 生成座位初始化和狀態變更事件

**配置**:
```yaml
Replicas: 10
資源配置:
  requests: CPU 2, Memory 2Gi  # 更高的資源
  limits:   CPU 4, Memory 4Gi

JVM 參數:
  -XX:+UseZGC -XX:+ZGenerational
  -Xmx2g -Xms2g -XX:+AlwaysPreTouch  # 2GB 堆
  原因: RocksDB 本地狀態存儲佔用大量記憶體

服務:
  無外部暴露（內部通訊）
```

**高併發特性**:
- RocksDB 本地狀態：O(1) 狀態查詢，無遠端調用延遲
- 大堆分配：RocksDB 快取大量座位狀態
- 分布式狀態：每個 pod 管理一部分分區的狀態

---

## 基礎設施層詳解

### 1. Kafka (1 replica)
**檔案**: `k8s/infra/kafka.yaml`

**用途**:
- 事件流代理（message broker）
- 所有微服務通過 Kafka 非同步通訊
- 確保消息持久化和事件重放

**配置**:
```yaml
Replicas: 1
映像: apache/kafka:3.8.1

KRaft 模式 (無需 Zookeeper):
  KAFKA_NODE_ID: 1
  KAFKA_PROCESS_ROLES: broker,controller
  KAFKA_LISTENERS: PLAINTEXT://:9092, CONTROLLER://:9093
  KAFKA_ADVERTISED_LISTENERS: kafka.ticketmaster.svc.cluster.local:9092

性能調優:
  KAFKA_NUM_NETWORK_THREADS: 8
    → 允許 8 個並發網路連接

  KAFKA_NUM_IO_THREADS: 16
    → 16 個線程處理磁碟 I/O

  KAFKA_SOCKET_SEND_BUFFER_BYTES: 1048576 (1MB)
    → TCP 發送緩衝大小

  KAFKA_SOCKET_RECEIVE_BUFFER_BYTES: 1048576 (1MB)
    → TCP 接收緩衝大小

  KAFKA_LOG_FLUSH_INTERVAL_MESSAGES: 50000
    → 每 50000 條消息落盤一次

  KAFKA_LOG_SEGMENT_BYTES: 1073741824 (1GB)
    → 單個日誌段大小

資源配置:
  requests: CPU 2, Memory 2Gi
  limits:   CPU 4, Memory 4Gi

服務:
  port: 9092 (PLAINTEXT 端口)
```

**工作原理**:
- 所有 producers/consumers 連接到這個單一 broker
- 消息持久化到磁碟（WAL）
- 支持消息重放（replay）

---

### 2. PostgreSQL (1 replica + 10Gi PVC)
**檔案**: `k8s/infra/postgres.yaml`

**用途**:
- 持久化存儲：訂單、用戶、票券信息
- 事務一致性：ACID 保證
- 狀態恢復：pod 重啟時恢復數據

**配置**:
```yaml
Replicas: 1
映像: postgres:latest

PersistentVolumeClaim (PVC):
  名稱: postgres-pvc
  存儲容量: 10Gi
  訪問模式: ReadWriteOnce (同時只能一個 pod 讀寫)

環境變數:
  POSTGRES_DB: mydatabase
  POSTGRES_USER: myuser
  POSTGRES_PASSWORD: secret
  PGDATA: /var/lib/postgresql/data/pgdata

掛載:
  /var/lib/postgresql/data (PVC 掛載點)

資源配置:
  requests: CPU 500m, Memory 512Mi
  limits:   CPU 2, Memory 2Gi

服務:
  port: 5432 (PostgreSQL 標準端口)
```

**持久化策略**:
- PersistentVolumeClaim：即使 pod 刪除，數據仍保留
- WAL (Write-Ahead Log)：確保故障時無數據丟失

---

### 3. Redis (1 replica)
**檔案**: `k8s/infra/redis.yaml`

**用途**:
- 高性能快取層
- 票券庫存快取：提升讀取速度
- 臨時狀態存儲：session、DeferredResult ID

**配置**:
```yaml
Replicas: 1
映像: redis:latest

端口: 6379 (Redis 標準端口)

資源配置:
  requests: CPU 250m, Memory 256Mi
  limits:   CPU 1, Memory 1Gi

服務:
  port: 6379
```

**快取策略**:
- 票券可用性快取：API 讀取無需查 PostgreSQL
- 超時自動清理：設定 TTL 防止記憶體爆炸
- 無持久化：故障時重新從 PostgreSQL 熱載

---

### 4. Schema Registry (1 replica)
**檔案**: `k8s/infra/schema-registry.yaml`

**用途**:
- Avro Schema 版本管理
- Kafka 消息序列化/反序列化
- Schema 演化規則管理

**配置**:
```yaml
Replicas: 1
映像: confluentinc/cp-schema-registry:7.8.0

配置文件 (ConfigMap):
  listeners: http://0.0.0.0:8081
  host.name: schema-registry
  kafkastore.bootstrap.servers: kafka.ticketmaster.svc.cluster.local:9092
  kafkastore.topic: _schemas (Kafka 內部主題)
  debug: false

資源配置:
  requests: CPU 250m, Memory 512Mi
  limits:   CPU 1, Memory 1Gi

服務:
  port: 8081
```

**功能**:
- Producer 序列化時：查詢 schema 版本
- Consumer 反序列化時：驗證 schema 兼容性
- 強制 schema 演化規則：向後/向前兼容

---

## 服務通訊架構

### ConfigMap - 環境變數注入
**檔案**: `k8s/app/configmap.yaml`

所有應用 pod 通過 ConfigMap 注入配置，實現 12-Factor App：

```yaml
SPRING_DATASOURCE_URL: "jdbc:postgresql://postgres.ticketmaster.svc.cluster.local:5432/mydatabase"
SPRING_DATASOURCE_USERNAME: "myuser"
SPRING_DATASOURCE_PASSWORD: "secret"

SPRING_DATA_REDIS_HOST: "redis.ticketmaster.svc.cluster.local"
SPRING_DATA_REDIS_PORT: "6379"

SPRING_KAFKA_BOOTSTRAP_SERVERS: "kafka.ticketmaster.svc.cluster.local:9092"
SPRING_KAFKA_STREAMS_PROPERTIES_SCHEMA_REGISTRY_URL: "http://schema-registry.ticketmaster.svc.cluster.local:8081"
```

### Kubernetes DNS 服務發現
Kubernetes DNS 自動解析：
```
{service}.{namespace}.svc.cluster.local
```

例如：
- `postgres.ticketmaster.svc.cluster.local` → postgres pod
- `kafka.ticketmaster.svc.cluster.local` → kafka pod
- `redis.ticketmaster.svc.cluster.local` → redis pod

這實現了 **無需 IP 配置的服務發現**。

### 服務類型

| 服務 | 類型 | 端口 | 說明 |
|------|------|------|------|
| api | LoadBalancer | 8080 | 外部訪問入口 |
| reservation-processor | ClusterIP | 8180 | 內部通訊 |
| seat-processor | ClusterIP | N/A | 內部通訊 |
| kafka | ClusterIP | 9092 | 內部通訊 |
| postgres | ClusterIP | 5432 | 內部通訊 |
| redis | ClusterIP | 6379 | 內部通訊 |
| schema-registry | ClusterIP | 8081 | 內部通訊 |

---

## 數據流程

### 訂票流程
```
1. 客戶端發送 POST /bookings
   ↓
2. API (LoadBalancer 8080) 接收
   ↓
3. API 通過 HTTP 呼叫 reservation-processor:8180
   ↓
4. Reservation Processor 發布 "booking" 事件到 Kafka
   ↓
5. Seat Processor 訂閱 Kafka，更新座位狀態
   ↓
6. Redis 快取座位可用性
   ↓
7. Reservation Processor 查詢 PostgreSQL，儲存訂單
   ↓
8. Reservation Processor 通知 API DeferredResult
   ↓
9. API 回應客戶端
```

### 數據存儲策略

```
┌─────────────────┐
│  熱數據 (Redis) │  → 票券庫存、座位快取
│  讀取速度: 微秒  │
└─────────────────┘
        ↓
┌──────────────────────┐
│ 冷數據 (PostgreSQL)  │  → 訂單、用戶、歷史紀錄
│ 讀取速度: 毫秒       │
└──────────────────────┘
        ↓
┌──────────────────────┐
│ 事件流 (Kafka)       │  → 不可變事件日誌
│ 重放速度: 秒         │
└──────────────────────┘
```

---

## Kubernetes 配置詳解

### Namespace (命名空間)
```yaml
apiVersion: v1
kind: Namespace
metadata:
  name: ticketmaster
```

**作用**: 隔離資源，多個應用可共存一個集群

### Deployment 結構
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {service-name}
  namespace: ticketmaster
spec:
  replicas: {數量}
  selector:
    matchLabels:
      app: {service-name}
  template:
    metadata:
      labels:
        app: {service-name}  # Label 用於 Service 選擇
    spec:
      containers:
        - name: {container-name}
          image: {image:tag}
          ports:
            - containerPort: {port}
          resources:
            requests:
              cpu: {最少保留}
              memory: {最少保留}
            limits:
              cpu: {最多使用}
              memory: {最多使用}
          livenessProbe:    # 存活探針
            httpGet:
              path: /actuator/health
              port: {port}
            initialDelaySeconds: {延遲}
            periodSeconds: {檢查周期}
          readinessProbe:   # 就緒探針
            httpGet:
              path: /actuator/health
              port: {port}
            initialDelaySeconds: {延遲}
            periodSeconds: {檢查周期}
```

### Service (服務)
```yaml
apiVersion: v1
kind: Service
metadata:
  name: {service-name}
  namespace: ticketmaster
spec:
  type: LoadBalancer | ClusterIP | NodePort
  selector:
    app: {service-name}  # 選擇對應的 pods
  ports:
    - port: {service-port}
      targetPort: {container-port}
```

### PersistentVolumeClaim (持久化存儲)
```yaml
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: {pvc-name}
  namespace: ticketmaster
spec:
  accessModes: [ReadWriteOnce]  # 單個 pod 讀寫
  resources:
    requests:
      storage: {容量}
```

---

## 高併發優化設計

### 1. 應用層優化

| 優化 | 方法 | 效果 |
|------|------|------|
| **低延遲 GC** | ZGC (Z Garbage Collector) | GC 暫停 < 10ms |
| **堆預熱** | AlwaysPreTouch | 避免缺頁中斷 |
| **異步響應** | DeferredResult | 服務器線程池不阻塞 |
| **橫向擴展** | 10 replicas + LB | 10 倍吞吐量 |

### 2. 消息層優化

| 優化 | 方法 | 效果 |
|------|------|------|
| **高吞吐** | Kafka 8 network threads | 並發連接 |
| **快 I/O** | 16 IO threads | 磁碟寫入性能 |
| **大緩衝** | 1MB TCP 緩衝 | 減少網路等待 |
| **並行處理** | 分區分配 | 10 個 processor 分擔負載 |

### 3. 數據層優化

| 優化 | 方法 | 效果 |
|------|------|------|
| **快速讀取** | Redis 快取 | 熱數據 µs 級延遲 |
| **無鎖流程** | Kafka Streams 拓撲 | 避免資料庫行鎖 |
| **本地狀態** | RocksDB (Seat Processor) | O(1) 狀態查詢 |
| **事件重放** | WAL + Kafka | 故障恢復無數據丟失 |

### 4. 資源配置策略

**API**: 小堆 + 高 CPU（頻繁 GC）
```yaml
requests: CPU 1, Memory 1Gi
limits:   CPU 4, Memory 2Gi
```

**Reservation Processor**: 中堆 + 中 CPU（中等狀態）
```yaml
requests: CPU 1, Memory 1Gi
limits:   CPU 2, Memory 2Gi
```

**Seat Processor**: 大堆 + 高 CPU（大量本地狀態）
```yaml
requests: CPU 2, Memory 2Gi
limits:   CPU 4, Memory 4Gi
```

---

## 部署命令

### 1. 創建命名空間
```bash
kubectl apply -f k8s/namespace.yaml
```

### 2. 部署基礎設施 (順序很重要)
```bash
# 1. PostgreSQL (需要時間初始化)
kubectl apply -f k8s/infra/postgres.yaml
sleep 30  # 等待 postgres 啟動

# 2. Kafka (需要 PostgreSQL 之前也可以)
kubectl apply -f k8s/infra/kafka.yaml
sleep 30  # 等待 Kafka 啟動

# 3. Schema Registry (需要 Kafka)
kubectl apply -f k8s/infra/schema-registry.yaml

# 4. Redis (獨立)
kubectl apply -f k8s/infra/redis.yaml
```

### 3. 部署應用
```bash
# ConfigMap 必須先部署（提供環境配置）
kubectl apply -f k8s/app/configmap.yaml

# 應用服務（順序無關，但等待基礎設施就緒）
kubectl apply -f k8s/app/api.yaml
kubectl apply -f k8s/app/reservation-processor.yaml
kubectl apply -f k8s/app/seat-processor.yaml
```

### 4. 檢查狀態
```bash
# 查看所有 pods
kubectl get pods -n ticketmaster

# 查看 pods 詳細信息
kubectl describe pods -n ticketmaster

# 查看 pod 日誌
kubectl logs -n ticketmaster -f {pod-name}

# 查看服務
kubectl get svc -n ticketmaster

# 測試連接
kubectl port-forward -n ticketmaster svc/api 8080:8080
curl http://localhost:8080/api/events
```

### 5. 清理資源
```bash
kubectl delete namespace ticketmaster
```

---

## 常見問題

### Q: 為什麼 API 和 Processor 都要 10 replicas？
A:
- API：需要分擔外部 HTTP 連接
- Processor：Kafka Streams 需要並行處理不同分區
- 兩層都 10：實現 100+ 並發連接

### Q: PostgreSQL 為什麼只有 1 replica？
A:
- 關係型數據庫難以水平擴展（多主複制複雜）
- 單機 PostgreSQL 足以支持高吞吐（瓶頸在應用層）
- 生產環境應該用 cloud database 或 PostgreSQL cluster

### Q: Kafka 為什麼不需要多副本？
A:
- 此專案是開發/演示環境，簡化配置
- 實際應用應該部署 3 個 broker 的 Kafka 集群
- 當前設置適合學習和測試

### Q: Redis 數據丟失怎麼辦？
A:
- Redis 主要用快取，非關鍵數據
- 失效數據可從 PostgreSQL 重新加載
- 生產環境應該啟用 Redis persistence (RDB/AOF)

### Q: 如何監控這些服務？
A:
- 應用已集成 OpenTelemetry
- OTLP 端點：Grafana LGTM 棧（port 4317/4318）
- 可視化：Grafana UI（port 3000）

---

## 重點回顧

✅ **TicketMaster K8s 架構**:
- 應用層：3 個微服務各 10 replicas（30 pods 總數）
- 基礎設施層：4 個支撐服務（Kafka, PostgreSQL, Redis, Schema Registry）
- 服務發現：Kubernetes DNS 自動解析
- 數據流：事件驅動 + 異步響應 + 快取加速
- 高併發：ZGC + Kafka Streams + 橫向擴展

🎯 **為什麼這樣設計**:
- 無悲觀鎖（資料庫行鎖）：高併發下避免阻塞
- 無樂觀鎖重試：Kafka Streams 確保事件順序處理
- WAL + 事件重放：故障恢復無數據丟失
- 分層快取：Redis → PostgreSQL → Kafka 冷熱分離

---

## 相關文檔
- [CLAUDE.md](../../CLAUDE.md) - 項目概述
- [compose.yaml](../../compose.yaml) - Docker Compose 本地開發
- 部署腳本：`k8s/deploy.sh`
