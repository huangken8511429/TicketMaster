package com.keer.ticketmaster.streaming;

import com.keer.ticketmaster.avro.SectionSeatState;
import com.keer.ticketmaster.avro.SectionStatusEvent;

/**
 * Shared mapper: SectionSeatState → SectionStatusEvent.
 * Used by all topology init paths and status update paths.
 */
public class SectionStatusMapper {

    public static SectionStatusEvent toStatusEvent(SectionSeatState state) {
        return SectionStatusEvent.newBuilder()
                .setEventId(state.getEventId())
                .setSection(state.getSection())
                .setSubPartition(state.getSubPartition())
                .setTotalSubPartitions(state.getTotalSubPartitions())
                .setAvailableCount(state.getAvailableCount())
                .setTimestamp(System.currentTimeMillis())
                .build();
    }
}
