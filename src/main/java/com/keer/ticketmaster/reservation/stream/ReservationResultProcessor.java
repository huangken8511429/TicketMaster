package com.keer.ticketmaster.reservation.stream;

import com.keer.ticketmaster.config.KafkaStreamsConfig;
import com.keer.ticketmaster.reservation.event.ReservationCompletedEvent;
import com.keer.ticketmaster.reservation.event.ReservationResultEvent;
import com.keer.ticketmaster.reservation.model.ReservationState;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.state.KeyValueStore;

import java.time.Instant;

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

        // Update reservation-store
        ReservationState state = reservationStore.get(reservationId);
        if (state != null) {
            if (result.isSuccess()) {
                state.setStatus("CONFIRMED");
                state.setAllocatedSeats(result.getAllocatedSeats());
            } else {
                state.setStatus("REJECTED");
            }
            state.setUpdatedAt(Instant.now());
            reservationStore.put(reservationId, state);
        }

        // Forward ReservationCompletedEvent
        String status = result.isSuccess() ? "CONFIRMED" : "REJECTED";
        String userId = state != null ? state.getUserId() : "unknown";

        ReservationCompletedEvent completedEvent = new ReservationCompletedEvent(
                reservationId,
                userId,
                status,
                result.getAllocatedSeats(),
                Instant.now()
        );
        context.forward(new Record<>(reservationId, completedEvent, record.timestamp()));
    }
}
