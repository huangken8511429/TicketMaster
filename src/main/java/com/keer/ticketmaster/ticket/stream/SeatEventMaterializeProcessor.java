package com.keer.ticketmaster.ticket.stream;

import com.keer.ticketmaster.avro.AreaSeatState;
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
    private KeyValueStore<String, AreaSeatState> seatStore;

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

        AreaSeatState area = seatStore.get(storeKey);
        if (area == null) {
            area = AreaSeatState.newBuilder()
                    .setEventId(event.getEventId())
                    .setSection(event.getSection())
                    .setSeatStatuses(new HashMap<>())
                    .setAvailableCount(0)
                    .build();
        }

        String previousStatus = area.getSeatStatuses().get(event.getSeatNumber());
        String newStatus = event.getStatus().name();

        area.getSeatStatuses().put(event.getSeatNumber(), newStatus);

        // Maintain availableCount
        boolean wasAvailable = "AVAILABLE".equals(previousStatus);
        boolean isAvailable = "AVAILABLE".equals(newStatus);
        if (!wasAvailable && isAvailable) {
            area.setAvailableCount(area.getAvailableCount() + 1);
        } else if (wasAvailable && !isAvailable) {
            area.setAvailableCount(area.getAvailableCount() - 1);
        }

        seatStore.put(storeKey, area);
        availableSeatCache.set(event.getEventId(), event.getSection(), area.getAvailableCount());
    }
}
