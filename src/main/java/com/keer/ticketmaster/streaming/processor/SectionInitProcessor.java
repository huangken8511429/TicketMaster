package com.keer.ticketmaster.streaming.processor;

import com.keer.ticketmaster.avro.SectionInitCommand;
import com.keer.ticketmaster.avro.SectionSeatState;
import com.keer.ticketmaster.config.StateStore;
import com.keer.ticketmaster.config.StoreKeyUtil;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.state.KeyValueStore;

import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Set;

/**
 * Initializes seat inventory for event sections using bitmap storage.
 * Supports sub-partitioning: splits a section into N independent sub-partitions,
 * each managing its own seat range for parallel processing.
 */
public class SectionInitProcessor
        implements Processor<String, SectionInitCommand, String, SectionSeatState> {

    private ProcessorContext<String, SectionSeatState> context;
    private KeyValueStore<String, SectionSeatState> seatStore;

    @Override
    public void init(ProcessorContext<String, SectionSeatState> context) {
        this.context = context;
        this.seatStore = context.getStateStore(StateStore.SEAT_INVENTORY);
    }

    @Override
    public void process(Record<String, SectionInitCommand> record) {
        SectionInitCommand command = record.value();
        long eventId = command.getEventId();
        String section = command.getSection();
        int rows = command.getRows();
        int seatsPerRow = command.getSeatsPerRow();
        int subPartitions = Math.max(1, command.getSubPartitions());

        Set<String> reserved = new HashSet<>(command.getInitialReserved());

        int totalSeats = rows * seatsPerRow;
        int seatsPerSubPartition = totalSeats / subPartitions;
        int remainder = totalSeats % subPartitions;

        int globalOffset = 1;

        for (int sp = 0; sp < subPartitions; sp++) {
            // Last sub-partition gets the remainder seats
            int subPartSeats = seatsPerSubPartition + (sp < remainder ? 1 : 0);
            int seatOffset = globalOffset;

            // Create bitmap: bit[i]=1 means seat at (seatOffset + i) is available
            int bitmapBytes = (subPartSeats + 7) / 8;
            byte[] bitmap = new byte[bitmapBytes];
            int availableCount = 0;

            for (int i = 0; i < subPartSeats; i++) {
                String seatName = section + "-" + (seatOffset + i);
                if (!reserved.contains(seatName)) {
                    // Set bit i to 1 (available)
                    bitmap[i / 8] |= (byte) (1 << (i % 8));
                    availableCount++;
                }
            }

            String storeKey = StoreKeyUtil.seatKey(eventId, section, sp);
            SectionSeatState state = SectionSeatState.newBuilder()
                    .setEventId(eventId)
                    .setSection(section)
                    .setSubPartition(sp)
                    .setTotalSubPartitions(subPartitions)
                    .setSeatBitmap(ByteBuffer.wrap(bitmap))
                    .setTotalSeats(subPartSeats)
                    .setSeatOffset(seatOffset)
                    .setAvailableCount(availableCount)
                    .build();

            seatStore.put(storeKey, state);
            context.forward(new Record<>(storeKey, state, record.timestamp()));

            globalOffset += subPartSeats;
        }
    }
}
