package com.keer.ticketmaster.ticket.stream;

import com.keer.ticketmaster.avro.ReservationCompletedEvent;
import com.keer.ticketmaster.avro.SectionSeatState;
import com.keer.ticketmaster.config.KafkaConstants;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.state.KeyValueStore;

public class SectionStatusEmitter
        implements Processor<String, ReservationCompletedEvent, String, SectionSeatState> {

    private ProcessorContext<String, SectionSeatState> context;
    private KeyValueStore<String, SectionSeatState> seatStore;

    @Override
    public void init(ProcessorContext<String, SectionSeatState> context) {
        this.context = context;
        this.seatStore = context.getStateStore(KafkaConstants.SEAT_INVENTORY_STORE);
    }

    @Override
    public void process(Record<String, ReservationCompletedEvent> record) {
        ReservationCompletedEvent event = record.value();
        String storeKey = event.getEventId() + "-" + event.getSection();

        SectionSeatState state = seatStore.get(storeKey);
        if (state != null) {
            context.forward(new Record<>(storeKey, state, record.timestamp()));
        }
    }
}
