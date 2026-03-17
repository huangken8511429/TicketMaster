package com.keer.ticketmaster.streaming.processor;

import com.keer.ticketmaster.avro.BookingCompletedEvent;
import com.keer.ticketmaster.avro.SectionSeatState;
import com.keer.ticketmaster.config.StateStore;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.state.KeyValueStore;

public class SectionStatusEmitter
        implements Processor<String, BookingCompletedEvent, String, SectionSeatState> {

    private ProcessorContext<String, SectionSeatState> context;
    private KeyValueStore<String, SectionSeatState> seatStore;

    @Override
    public void init(ProcessorContext<String, SectionSeatState> context) {
        this.context = context;
        this.seatStore = context.getStateStore(StateStore.SEAT_INVENTORY);
    }

    @Override
    public void process(Record<String, BookingCompletedEvent> record) {
        BookingCompletedEvent event = record.value();
        String storeKey = event.getEventId() + "-" + event.getSection();

        SectionSeatState state = seatStore.get(storeKey);
        if (state != null) {
            context.forward(new Record<>(storeKey, state, record.timestamp()));
        }
    }
}
