sequenceDiagram
participant C as Client
participant API as API Pod (tm-api)
participant Cache as SectionStatusCache
participant T_CMD as 📨 reservation-commands
participant RP as Reservation Processor (tm-reservation)
participant Store as 🗄️seat-inventory-store
participant T_COMP as 📨 reservation-completed
participant KT as 🗄️reservation-query-store<br/>(KTable in API Pod)
participant T_STATUS as 📨 section-status

      C->>API: POST /api/reservations<br/>{eventId: 1, section: A, seatCount: 3, userId: user1}
      API->>Cache: hasEnoughSeats(1, A, 3)?
      Cache-->>API: true (200 >= 3)
      API-->>C: HTTP 202 {reservationId: abc-123}

      API->>T_CMD: ReservationCommand<br/>Key: 1-A (eventId-section)<br/>{reservationId: abc-123, seatCount: 3}

      T_CMD->>RP: SeatAllocationProcessor 消費
      RP->>Store: get(1-A) → SectionSeatState
      Note over RP: 找到連續空位 A-1, A-2, A-3<br/>標記 RESERVED<br/>availableCount: 200 → 197
      RP->>Store: put(1-A, updated state)

      RP->>T_COMP: ReservationCompletedEvent<br/>Key: abc-123<br/>{status: CONFIRMED, allocatedSeats: [A-1, A-2, A-3]}

      RP->>T_STATUS: SectionStatusEvent {availableCount: 197}
      T_STATUS->>Cache: 更新 {1-A → 197}

      T_COMP->>KT: KTable 更新
      Note over KT: foreach callback 觸發
      KT->>API: pendingRequests.resolve(abc-123)
      API-->>C: HTTP 200 {status: CONFIRMED, seats: [A-1, A-2, A-3]}