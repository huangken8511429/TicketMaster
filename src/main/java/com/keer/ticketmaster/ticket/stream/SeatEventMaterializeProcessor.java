package com.keer.ticketmaster.ticket.stream;

import com.keer.ticketmaster.avro.SeatEvent;
import com.keer.ticketmaster.avro.SeatState;
import com.keer.ticketmaster.config.KafkaStreamsConfig;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.state.KeyValueStore;

public class SeatEventMaterializeProcessor implements Processor<String, SeatEvent, Void, Void> {

    private KeyValueStore<String, SeatState> seatStore;

    @Override
    public void init(ProcessorContext<Void, Void> context) {
        this.seatStore = context.getStateStore(KafkaStreamsConfig.SEAT_INVENTORY_STORE);
    }

    @Override
    public void process(Record<String, SeatEvent> record) {
        SeatEvent event = record.value();
        SeatState state = SeatState.newBuilder()
                .setSeatNumber(event.getSeatNumber())
                .setEventId(event.getEventId())
                .setSection(event.getSection())
                .setStatus(event.getStatus())
                .setReservationId(null)
                .build();
        String storeKey = event.getEventId() + "-" + event.getSeatNumber();
        seatStore.put(storeKey, state);
    }
}
