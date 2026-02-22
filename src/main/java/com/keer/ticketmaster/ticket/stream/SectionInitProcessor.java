package com.keer.ticketmaster.ticket.stream;

import com.keer.ticketmaster.avro.SectionInitCommand;
import com.keer.ticketmaster.avro.SectionSeatState;
import com.keer.ticketmaster.config.KafkaConstants;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.state.KeyValueStore;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class SectionInitProcessor
        implements Processor<String, SectionInitCommand, String, SectionSeatState> {

    private ProcessorContext<String, SectionSeatState> context;
    private KeyValueStore<String, SectionSeatState> seatStore;

    @Override
    public void init(ProcessorContext<String, SectionSeatState> context) {
        this.context = context;
        this.seatStore = context.getStateStore(KafkaConstants.SEAT_INVENTORY_STORE);
    }

    @Override
    public void process(Record<String, SectionInitCommand> record) {
        SectionInitCommand command = record.value();
        long eventId = command.getEventId();
        String section = command.getSection();
        int rows = command.getRows();
        int seatsPerRow = command.getSeatsPerRow();

        Set<String> reserved = new HashSet<>(command.getInitialReserved());

        Map<String, String> seatStatuses = new HashMap<>();
        int totalSeats = rows * seatsPerRow;
        int availableCount = 0;
        int globalIndex = 1;

        for (int row = 1; row <= rows; row++) {
            for (int col = 1; col <= seatsPerRow; col++) {
                String seatName = section + "-" + globalIndex;
                if (reserved.contains(seatName)) {
                    seatStatuses.put(seatName, "RESERVED");
                } else {
                    seatStatuses.put(seatName, "AVAILABLE");
                    availableCount++;
                }
                globalIndex++;
            }
        }

        String storeKey = eventId + "-" + section;
        SectionSeatState state = SectionSeatState.newBuilder()
                .setEventId(eventId)
                .setSection(section)
                .setSeatStatuses(seatStatuses)
                .setAvailableCount(availableCount)
                .build();

        seatStore.put(storeKey, state);
        context.forward(new Record<>(storeKey, state, record.timestamp()));
    }
}
