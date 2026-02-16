package com.keer.ticketmaster.reservation.stream;

import com.keer.ticketmaster.config.KafkaStreamsConfig;
import com.keer.ticketmaster.reservation.event.ReservationCommand;
import com.keer.ticketmaster.reservation.event.ReservationRequestedEvent;
import com.keer.ticketmaster.reservation.model.ReservationState;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.state.KeyValueStore;

import java.time.Instant;
import java.util.List;

public class ReservationCommandProcessor
        implements Processor<String, ReservationCommand, String, ReservationRequestedEvent> {

    private ProcessorContext<String, ReservationRequestedEvent> context;
    private KeyValueStore<String, ReservationState> reservationStore;

    @Override
    public void init(ProcessorContext<String, ReservationRequestedEvent> context) {
        this.context = context;
        this.reservationStore = context.getStateStore(KafkaStreamsConfig.RESERVATION_STORE);
    }

    @Override
    public void process(Record<String, ReservationCommand> record) {
        ReservationCommand command = record.value();
        String reservationId = command.getReservationId();

        // Write PENDING state to reservation-store
        ReservationState state = new ReservationState(
                reservationId,
                command.getEventId(),
                command.getSection(),
                command.getSeatCount(),
                command.getUserId(),
                "PENDING",
                List.of(),
                Instant.now(),
                Instant.now()
        );
        reservationStore.put(reservationId, state);

        // Forward to reservation-requests
        ReservationRequestedEvent event = new ReservationRequestedEvent(
                reservationId,
                command.getEventId(),
                command.getSection(),
                command.getSeatCount(),
                command.getUserId(),
                Instant.now()
        );
        context.forward(new Record<>(reservationId, event, record.timestamp()));
    }
}
