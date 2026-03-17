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
        SectionSeatState state = store.get("1-A");

        assertNotNull(state);
        assertEquals(1L, state.getEventId());
        assertEquals("A", state.getSection());
        assertEquals(6, state.getAvailableCount());

        List<String> seats = state.getAvailableSeats();
        assertEquals(6, seats.size());
        assertEquals(List.of("A-1", "A-2", "A-3", "A-4", "A-5", "A-6"), seats);

        assertFalse(sectionStatusOutput.isEmpty());
        KeyValue<String, SectionStatusEvent> output = sectionStatusOutput.readKeyValue();
        assertEquals("1-A", output.key);
        assertEquals(1L, output.value.getEventId());
        assertEquals("A", output.value.getSection());
        assertEquals(6, output.value.getAvailableCount());
    }

    @Test
    void initWithReservedSeats_shouldExcludeReservedAndReduceCount() {
        initSection(1L, "A", 2, 3, List.of("A-1", "A-2"));

        SectionSeatState state = getSeatInventoryStore().get("1-A");

        assertEquals(4, state.getAvailableCount());
        assertFalse(state.getAvailableSeats().contains("A-1"));
        assertFalse(state.getAvailableSeats().contains("A-2"));
        assertTrue(state.getAvailableSeats().contains("A-3"));

        SectionStatusEvent statusEvent = sectionStatusOutput.readValue();
        assertEquals(4, statusEvent.getAvailableCount());
    }

    @Test
    void duplicateInit_shouldOverwriteState() {
        initSection(1L, "A", 2, 3, List.of("A-1"));
        sectionStatusOutput.readKeyValue();

        initSection(1L, "A", 1, 2);

        SectionSeatState state = getSeatInventoryStore().get("1-A");
        assertEquals(2, state.getAvailableCount());
        assertEquals(2, state.getAvailableSeats().size());

        SectionStatusEvent statusEvent = sectionStatusOutput.readValue();
        assertEquals(2, statusEvent.getAvailableCount());
    }

    @Test
    void zeroSeats_shouldProduceEmptyList() {
        initSection(1L, "A", 0, 5);

        SectionSeatState state = getSeatInventoryStore().get("1-A");
        assertNotNull(state);
        assertEquals(0, state.getAvailableCount());
        assertTrue(state.getAvailableSeats().isEmpty());

        SectionStatusEvent statusEvent = sectionStatusOutput.readValue();
        assertEquals(0, statusEvent.getAvailableCount());
    }

    @Test
    void zeroSeatsPerRow_shouldProduceEmptyList() {
        initSection(1L, "A", 3, 0);

        SectionSeatState state = getSeatInventoryStore().get("1-A");
        assertNotNull(state);
        assertEquals(0, state.getAvailableCount());
        assertTrue(state.getAvailableSeats().isEmpty());
    }
}
