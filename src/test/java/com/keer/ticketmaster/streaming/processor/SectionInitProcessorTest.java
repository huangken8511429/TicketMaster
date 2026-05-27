package com.keer.ticketmaster.streaming.processor;

import com.keer.ticketmaster.avro.SectionSeatState;
import com.keer.ticketmaster.avro.SectionStatusEvent;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.state.KeyValueStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SectionInitProcessorTest extends StreamProcessorTestBase {

    @Test
    void normalInit_shouldCreateAllSeatsAvailable() {
        initSection(1L, "A", 2, 3);

        KeyValueStore<String, SectionSeatState> store = getSeatInventoryStore();
        SectionSeatState state = store.get("1-A-0");

        assertNotNull(state);
        assertEquals(1L, state.getEventId());
        assertEquals("A", state.getSection());
        assertEquals(0, state.getSubPartition());
        assertEquals(6, state.getAvailableCount());
        assertEquals(6, state.getTotalSeats());
        assertEquals(1, state.getSeatOffset());

        List<String> seats = getAvailableSeats(state);
        assertEquals(6, seats.size());
        assertEquals(List.of("A-1", "A-2", "A-3", "A-4", "A-5", "A-6"), seats);

        assertFalse(sectionStatusOutput.isEmpty());
        KeyValue<String, SectionStatusEvent> output = sectionStatusOutput.readKeyValue();
        assertEquals("1-A-0", output.key);
        assertEquals(1L, output.value.getEventId());
        assertEquals("A", output.value.getSection());
        assertEquals(6, output.value.getAvailableCount());
    }

    @Test
    void initWithReservedSeats_shouldExcludeReservedAndReduceCount() {
        initSection(1L, "A", 2, 3, List.of("A-1", "A-2"));

        SectionSeatState state = getSeatInventoryStore().get("1-A-0");

        assertEquals(4, state.getAvailableCount());
        assertFalse(isSeatAvailable(state, 1));
        assertFalse(isSeatAvailable(state, 2));
        assertTrue(isSeatAvailable(state, 3));

        SectionStatusEvent statusEvent = sectionStatusOutput.readValue();
        assertEquals(4, statusEvent.getAvailableCount());
    }

    @Test
    void duplicateInit_shouldOverwriteState() {
        initSection(1L, "A", 2, 3, List.of("A-1"));
        sectionStatusOutput.readKeyValue();

        initSection(1L, "A", 1, 2);

        SectionSeatState state = getSeatInventoryStore().get("1-A-0");
        assertEquals(2, state.getAvailableCount());
        assertEquals(2, getAvailableSeats(state).size());

        SectionStatusEvent statusEvent = sectionStatusOutput.readValue();
        assertEquals(2, statusEvent.getAvailableCount());
    }

    @Test
    void zeroSeats_shouldProduceEmptyBitmap() {
        initSection(1L, "A", 0, 5);

        SectionSeatState state = getSeatInventoryStore().get("1-A-0");
        assertNotNull(state);
        assertEquals(0, state.getAvailableCount());
        assertEquals(0, state.getTotalSeats());

        SectionStatusEvent statusEvent = sectionStatusOutput.readValue();
        assertEquals(0, statusEvent.getAvailableCount());
    }

    @Test
    void zeroSeatsPerRow_shouldProduceEmptyBitmap() {
        initSection(1L, "A", 3, 0);

        SectionSeatState state = getSeatInventoryStore().get("1-A-0");
        assertNotNull(state);
        assertEquals(0, state.getAvailableCount());
        assertEquals(0, state.getTotalSeats());
    }
}
