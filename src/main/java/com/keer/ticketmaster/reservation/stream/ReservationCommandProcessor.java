package com.keer.ticketmaster.reservation.stream;

import com.keer.ticketmaster.avro.ReservationCommand;
import com.keer.ticketmaster.avro.ReservationRequestedEvent;
import com.keer.ticketmaster.avro.ReservationState;
import com.keer.ticketmaster.config.KafkaConstants;
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
        this.reservationStore = context.getStateStore(KafkaConstants.RESERVATION_STORE);
    }

    @Override
    public void process(Record<String, ReservationCommand> record) {
        ReservationCommand command = record.value();
        String reservationId = command.getReservationId();

        ReservationState state = ReservationState.newBuilder()
                .setReservationId(reservationId)
                .setEventId(command.getEventId())
                .setSection(command.getSection())
                .setSeatCount(command.getSeatCount())
                .setUserId(command.getUserId())
                .setStatus("PENDING")
                .setAllocatedSeats(List.of())
                .setCreatedAt(Instant.now().toEpochMilli())
                .setUpdatedAt(Instant.now().toEpochMilli())
                .build();
        reservationStore.put(reservationId, state);

        ReservationRequestedEvent event = ReservationRequestedEvent.newBuilder()
                .setReservationId(reservationId)
                .setEventId(command.getEventId())
                .setSection(command.getSection())
                .setSeatCount(command.getSeatCount())
                .setUserId(command.getUserId())
                .setTimestamp(Instant.now().toEpochMilli())
                .build();

        String eventKey = String.valueOf(command.getEventId());
        context.forward(new Record<>(eventKey, event, record.timestamp()));
    }
}
