sequenceDiagram
participant C as 🙋 用戶
participant API1 as API Pod-1
participant API2 as API Pod-2
participant KT1 as 🗄️KTable (Pod-1)
participant KT2 as 🗄️KTable (Pod-2)

      C->>API1: GET /api/reservations/abc-123

      API1->>KT1: queryMetadataForKey(abc-123)
      Note over KT1: KeyQueryMetadata 回傳：<br/>activeHost = API Pod-2

      alt Key 在本地
          KT1-->>API1: 直接查 local store
          API1-->>C: HTTP 200 {status: CONFIRMED, seats: [...]}
      else Key 在其他 Pod
          API1->>API2: HTTP GET /internal/reservations/abc-123
          API2->>KT2: queryLocalStore(abc-123)
          KT2-->>API2: ReservationCompletedEvent
          API2-->>API1: HTTP 200 ReservationResponse
          API1-->>C: HTTP 200 {status: CONFIRMED, seats: [...]}
      end

      rect rgb(255, 248, 240)
          Note over C,KT2: 如果結果還沒到 KTable（極少見）
          API1->>API1: pendingRequests.register(abc-123, DeferredResult)
          Note over API1: 等待 foreach callback 或 30s timeout
      end