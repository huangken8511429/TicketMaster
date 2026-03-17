package com.keer.ticketmaster.streaming.processor;

import com.keer.ticketmaster.avro.BookingCommand;
import com.keer.ticketmaster.avro.BookingCompletedEvent;
import com.keer.ticketmaster.avro.SectionSeatState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SeatAllocationProcessorTest extends StreamProcessorTestBase {

    @Test
    void allocateConsecutiveSeats_shouldConfirm() {
        initSection(1L, "A", 1, 5);
        drainSectionStatus();

        pipeAllocationRequest("r1", 1L, "A", 3, "user1");

        BookingCompletedEvent event = seatAllocationResultOutput.readValue();
        assertEquals("CONFIRMED", event.getStatus());
        assertEquals("r1", event.getBookingId());
        assertEquals(3, event.getSeatCount());
        assertEquals(List.of("A-1", "A-2", "A-3"), event.getAllocatedSeats());

        // Verify state store updated: allocated seats removed from available list
        SectionSeatState state = getSeatInventoryStore().get("1-A");
        assertEquals(2, state.getAvailableCount());
        assertFalse(state.getAvailableSeats().contains("A-1"));
        assertFalse(state.getAvailableSeats().contains("A-2"));
        assertFalse(state.getAvailableSeats().contains("A-3"));
        assertTrue(state.getAvailableSeats().contains("A-4"));
        assertTrue(state.getAvailableSeats().contains("A-5"));
    }

    @Test
    void notEnoughSeats_shouldReject() {
        initSection(1L, "A", 1, 2);
        drainSectionStatus();

        pipeAllocationRequest("r1", 1L, "A", 3, "user1");

        BookingCompletedEvent event = seatAllocationResultOutput.readValue();
        assertEquals("REJECTED", event.getStatus());
        assertTrue(event.getAllocatedSeats().isEmpty());
    }

    @Test
    void nonConsecutiveAvailable_shouldReject() {
        initSection(1L, "A", 1, 5, List.of("A-2"));
        drainSectionStatus();

        initSection(2L, "B", 1, 5, List.of("B-3"));
        drainSectionStatus();

        pipeAllocationRequest("r1", 2L, "B", 3, "user1");

        BookingCompletedEvent event = seatAllocationResultOutput.readValue();
        assertEquals("REJECTED", event.getStatus());
        assertTrue(event.getAllocatedSeats().isEmpty());
    }

    @Test
    void exactlyEnoughConsecutive_shouldConfirm() {
        initSection(1L, "A", 1, 3);
        drainSectionStatus();

        pipeAllocationRequest("r1", 1L, "A", 3, "user1");

        BookingCompletedEvent event = seatAllocationResultOutput.readValue();
        assertEquals("CONFIRMED", event.getStatus());
        assertEquals(List.of("A-1", "A-2", "A-3"), event.getAllocatedSeats());

        SectionSeatState state = getSeatInventoryStore().get("1-A");
        assertEquals(0, state.getAvailableCount());
    }

    @Test
    void allReserved_shouldReject() {
        initSection(1L, "A", 1, 3, List.of("A-1", "A-2", "A-3"));
        drainSectionStatus();

        pipeAllocationRequest("r1", 1L, "A", 1, "user1");

        BookingCompletedEvent event = seatAllocationResultOutput.readValue();
        assertEquals("REJECTED", event.getStatus());
    }

    @Test
    void noSectionData_shouldReject() {
        pipeAllocationRequest("r1", 99L, "Z", 1, "user1");

        BookingCompletedEvent event = seatAllocationResultOutput.readValue();
        assertEquals("REJECTED", event.getStatus());
        assertTrue(event.getAllocatedSeats().isEmpty());
    }

    @Test
    void availableCountDecrementsAfterAllocation() {
        initSection(1L, "A", 1, 5);
        drainSectionStatus();

        pipeAllocationRequest("r1", 1L, "A", 2, "user1");
        BookingCompletedEvent e1 = seatAllocationResultOutput.readValue();
        assertEquals("CONFIRMED", e1.getStatus());

        SectionSeatState state1 = getSeatInventoryStore().get("1-A");
        assertEquals(3, state1.getAvailableCount());

        pipeAllocationRequest("r2", 1L, "A", 2, "user2");
        BookingCompletedEvent e2 = seatAllocationResultOutput.readValue();
        assertEquals("CONFIRMED", e2.getStatus());

        SectionSeatState state2 = getSeatInventoryStore().get("1-A");
        assertEquals(1, state2.getAvailableCount());

        pipeAllocationRequest("r3", 1L, "A", 2, "user3");
        BookingCompletedEvent e3 = seatAllocationResultOutput.readValue();
        assertEquals("REJECTED", e3.getStatus());
    }

    @Test
    void sectionStatusEmittedAfterAllocation() {
        initSection(1L, "A", 1, 5);
        drainSectionStatus();

        pipeAllocationRequest("r1", 1L, "A", 3, "user1");
        seatAllocationResultOutput.readValue(); // drain

        assertFalse(sectionStatusOutput.isEmpty());
        var statusEvent = sectionStatusOutput.readValue();
        assertEquals(2, statusEvent.getAvailableCount());
        assertEquals(1L, statusEvent.getEventId());
        assertEquals("A", statusEvent.getSection());
    }

    private void pipeAllocationRequest(String bookingId, long eventId, String section, int seatCount, String userId) {
        String key = eventId + "-" + section;
        BookingCommand command = buildBookingCommand(bookingId, eventId, section, seatCount, userId);
        seatAllocationRequestInput.pipeInput(key, command);
    }

    private void drainSectionStatus() {
        sectionStatusOutput.readKeyValuesToList();
    }
}
