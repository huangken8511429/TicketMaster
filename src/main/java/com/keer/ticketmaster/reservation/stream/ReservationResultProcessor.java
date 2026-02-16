package com.keer.ticketmaster.reservation.stream;

import com.keer.ticketmaster.avro.ReservationCompletedEvent;
import com.keer.ticketmaster.avro.ReservationResultEvent;
import com.keer.ticketmaster.avro.ReservationState;
import com.keer.ticketmaster.config.KafkaStreamsConfig;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.state.KeyValueStore;

import java.time.Instant;
import java.util.List;

public class ReservationResultProcessor
        implements Processor<String, ReservationResultEvent, String, ReservationCompletedEvent> {

    private ProcessorContext<String, ReservationCompletedEvent> context;
    private KeyValueStore<String, ReservationState> reservationStore;

    @Override
    public void init(ProcessorContext<String, ReservationCompletedEvent> context) {
        this.context = context;
        this.reservationStore = context.getStateStore(KafkaStreamsConfig.RESERVATION_STORE);
    }

    @Override
    public void process(Record<String, ReservationResultEvent> record) {
        ReservationResultEvent result = record.value();
        String reservationId = result.getReservationId();

        ReservationState state = reservationStore.get(reservationId);
        if (state != null) {
            ReservationState.Builder updated = ReservationState.newBuilder(state)
                    .setUpdatedAt(Instant.now().toEpochMilli());

            if (result.getSuccess()) {
                updated.setStatus("CONFIRMED");
                updated.setAllocatedSeats(result.getAllocatedSeats());
            } else {
                updated.setStatus("REJECTED");
            }

            reservationStore.put(reservationId, updated.build());
        }

        String status = result.getSuccess() ? "CONFIRMED" : "REJECTED";
        String userId = state != null ? state.getUserId() : "unknown";
        long eventId = state != null ? state.getEventId() : 0L;

        ReservationCompletedEvent completedEvent = ReservationCompletedEvent.newBuilder()
                .setReservationId(reservationId)
                .setEventId(eventId)
                .setUserId(userId)
                .setStatus(status)
                .setAllocatedSeats(result.getAllocatedSeats() != null ? result.getAllocatedSeats() : List.of())
                .setTimestamp(Instant.now().toEpochMilli())
                .build();

        String eventKey = eventId != 0 ? String.valueOf(eventId) : reservationId;
        context.forward(new Record<>(eventKey, completedEvent, record.timestamp()));
    }
}
