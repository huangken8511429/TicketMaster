package com.keer.ticketmaster.ticket.stream;

import com.keer.ticketmaster.avro.SectionSeatState;
import com.keer.ticketmaster.avro.SeatEvent;
import com.keer.ticketmaster.config.KafkaConstants;
import com.keer.ticketmaster.reservation.service.SeatAvailabilityChecker;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.state.KeyValueStore;

import java.util.HashMap;

public class SeatEventMaterializeProcessor implements Processor<String, SeatEvent, Void, Void> {

    private final SeatAvailabilityChecker availableSeatCache;
    private KeyValueStore<String, SectionSeatState> seatStore;

    public SeatEventMaterializeProcessor(SeatAvailabilityChecker availableSeatCache) {
        this.availableSeatCache = availableSeatCache;
    }

    @Override
    public void init(ProcessorContext<Void, Void> context) {
        this.seatStore = context.getStateStore(KafkaConstants.SEAT_INVENTORY_STORE);
    }

    @Override
    public void process(Record<String, SeatEvent> record) {
        SeatEvent event = record.value();
        String storeKey = event.getEventId() + "-" + event.getSection();

        SectionSeatState sectionState = seatStore.get(storeKey);
        if (sectionState == null) {
            sectionState = SectionSeatState.newBuilder()
                    .setEventId(event.getEventId())
                    .setSection(event.getSection())
                    .setSeatStatuses(new HashMap<>())
                    .setAvailableCount(0)
                    .build();
        }

        String previousStatus = sectionState.getSeatStatuses().get(event.getSeatNumber());
        String newStatus = event.getStatus().name();

        sectionState.getSeatStatuses().put(event.getSeatNumber(), newStatus);

        // Maintain availableCount
        boolean wasAvailable = "AVAILABLE".equals(previousStatus);
        boolean isAvailable = "AVAILABLE".equals(newStatus);
        if (!wasAvailable && isAvailable) {
            sectionState.setAvailableCount(sectionState.getAvailableCount() + 1);
        } else if (wasAvailable && !isAvailable) {
            sectionState.setAvailableCount(sectionState.getAvailableCount() - 1);
        }

        seatStore.put(storeKey, sectionState);
        availableSeatCache.set(event.getEventId(), event.getSection(), sectionState.getAvailableCount());
    }
}
