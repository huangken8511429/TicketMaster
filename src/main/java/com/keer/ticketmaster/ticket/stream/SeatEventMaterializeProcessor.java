package com.keer.ticketmaster.ticket.stream;

import com.keer.ticketmaster.config.KafkaStreamsConfig;
import com.keer.ticketmaster.ticket.event.SeatEvent;
import com.keer.ticketmaster.ticket.model.SeatState;
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
        SeatState state = new SeatState(
                event.getSeatNumber(),
                event.getEventId(),
                event.getSection(),
                event.getStatus(),
                null
        );
        seatStore.put(record.key(), state);
    }
}
