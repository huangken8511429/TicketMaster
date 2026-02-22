package com.keer.ticketmaster.reservation.stream;

import com.keer.ticketmaster.avro.ReservationCommand;
import com.keer.ticketmaster.avro.ReservationRequestedEvent;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;

import java.time.Instant;

public class ReservationCommandProcessor
        implements Processor<String, ReservationCommand, String, ReservationRequestedEvent> {

    private ProcessorContext<String, ReservationRequestedEvent> context;

    @Override
    public void init(ProcessorContext<String, ReservationRequestedEvent> context) {
        this.context = context;
    }

    @Override
    public void process(Record<String, ReservationCommand> record) {
        ReservationCommand command = record.value();

        ReservationRequestedEvent event = ReservationRequestedEvent.newBuilder()
                .setReservationId(command.getReservationId())
                .setEventId(command.getEventId())
                .setSection(command.getSection())
                .setSeatCount(command.getSeatCount())
                .setUserId(command.getUserId())
                .setTimestamp(Instant.now().toEpochMilli())
                .build();

        String seatKey = command.getEventId() + "-" + command.getSection();
        context.forward(new Record<>(seatKey, event, record.timestamp()));
    }
}
