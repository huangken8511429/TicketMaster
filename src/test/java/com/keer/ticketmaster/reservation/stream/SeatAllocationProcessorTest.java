package com.keer.ticketmaster.reservation.stream;

import com.keer.ticketmaster.avro.ReservationCommand;
import com.keer.ticketmaster.avro.ReservationCompletedEvent;
import com.keer.ticketmaster.avro.SectionSeatState;
import org.apache.kafka.streams.state.KeyValueStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SeatAllocationProcessorTest extends StreamProcessorTestBase {

    @Test
    void allocateConsecutiveSeats_shouldConfirm() {
        initSection(1L, "A", 1, 5);
        drainSectionStatus();

        pipeReservation("r1", 1L, "A", 3, "user1");

        ReservationCompletedEvent event = reservationCompletedOutput.readValue();
        assertEquals("CONFIRMED", event.getStatus());
        assertEquals("r1", event.getReservationId());
        assertEquals(3, event.getSeatCount());
        assertEquals(List.of("A-1", "A-2", "A-3"), event.getAllocatedSeats());

        // Verify state store updated
        SectionSeatState state = getSeatInventoryStore().get("1-A");
        assertEquals(2, state.getAvailableCount());
        assertEquals("RESERVED", state.getSeatStatuses().get("A-1"));
        assertEquals("RESERVED", state.getSeatStatuses().get("A-2"));
        assertEquals("RESERVED", state.getSeatStatuses().get("A-3"));
        assertEquals("AVAILABLE", state.getSeatStatuses().get("A-4"));
    }

    @Test
    void notEnoughSeats_shouldReject() {
        initSection(1L, "A", 1, 2);
        drainSectionStatus();

        pipeReservation("r1", 1L, "A", 3, "user1");

        ReservationCompletedEvent event = reservationCompletedOutput.readValue();
        assertEquals("REJECTED", event.getStatus());
        assertTrue(event.getAllocatedSeats().isEmpty());
    }

    @Test
    void nonConsecutiveAvailable_shouldReject() {
        // A-1 available, A-2 reserved, A-3 available, A-4 available, A-5 available
        initSection(1L, "A", 1, 5, List.of("A-2"));
        drainSectionStatus();

        // Request 3 consecutive — A-1 alone can't form 3, A-3,A-4,A-5 can!
        // Actually A-3,A-4,A-5 ARE consecutive, so this should CONFIRM
        // Let's test with a scenario where no 3-consecutive exist
        // A-1,A-2 available, A-3 reserved, A-4,A-5 available → no 3-consecutive
        initSection(2L, "B", 1, 5, List.of("B-3"));
        drainSectionStatus();

        pipeReservation("r1", 2L, "B", 3, "user1");

        ReservationCompletedEvent event = reservationCompletedOutput.readValue();
        assertEquals("REJECTED", event.getStatus());
        assertTrue(event.getAllocatedSeats().isEmpty());
    }

    @Test
    void exactlyEnoughConsecutive_shouldConfirm() {
        initSection(1L, "A", 1, 3);
        drainSectionStatus();

        pipeReservation("r1", 1L, "A", 3, "user1");

        ReservationCompletedEvent event = reservationCompletedOutput.readValue();
        assertEquals("CONFIRMED", event.getStatus());
        assertEquals(List.of("A-1", "A-2", "A-3"), event.getAllocatedSeats());

        SectionSeatState state = getSeatInventoryStore().get("1-A");
        assertEquals(0, state.getAvailableCount());
    }

    @Test
    void allReserved_shouldReject() {
        initSection(1L, "A", 1, 3, List.of("A-1", "A-2", "A-3"));
        drainSectionStatus();

        pipeReservation("r1", 1L, "A", 1, "user1");

        ReservationCompletedEvent event = reservationCompletedOutput.readValue();
        assertEquals("REJECTED", event.getStatus());
    }

    @Test
    void noSectionData_shouldReject() {
        // No initSection call — state store is empty
        pipeReservation("r1", 99L, "Z", 1, "user1");

        ReservationCompletedEvent event = reservationCompletedOutput.readValue();
        assertEquals("REJECTED", event.getStatus());
        assertTrue(event.getAllocatedSeats().isEmpty());
    }

    @Test
    void availableCountDecrementsAfterAllocation() {
        initSection(1L, "A", 1, 5);
        drainSectionStatus();

        // First allocation: 2 seats
        pipeReservation("r1", 1L, "A", 2, "user1");
        ReservationCompletedEvent e1 = reservationCompletedOutput.readValue();
        assertEquals("CONFIRMED", e1.getStatus());

        SectionSeatState state1 = getSeatInventoryStore().get("1-A");
        assertEquals(3, state1.getAvailableCount());

        // Second allocation: 2 more seats
        pipeReservation("r2", 1L, "A", 2, "user2");
        ReservationCompletedEvent e2 = reservationCompletedOutput.readValue();
        assertEquals("CONFIRMED", e2.getStatus());

        SectionSeatState state2 = getSeatInventoryStore().get("1-A");
        assertEquals(1, state2.getAvailableCount());

        // Third allocation: request 2 but only 1 left
        pipeReservation("r3", 1L, "A", 2, "user3");
        ReservationCompletedEvent e3 = reservationCompletedOutput.readValue();
        assertEquals("REJECTED", e3.getStatus());
    }

    @Test
    void sectionStatusEmittedAfterAllocation() {
        initSection(1L, "A", 1, 5);
        drainSectionStatus();

        pipeReservation("r1", 1L, "A", 3, "user1");
        reservationCompletedOutput.readValue(); // drain

        // SectionStatusEmitter should have forwarded updated state
        assertFalse(sectionStatusOutput.isEmpty());
        var statusEvent = sectionStatusOutput.readValue();
        assertEquals(2, statusEvent.getAvailableCount());
        assertEquals(1L, statusEvent.getEventId());
        assertEquals("A", statusEvent.getSection());
    }

    private void pipeReservation(String reservationId, long eventId, String section, int seatCount, String userId) {
        String key = eventId + "-" + section;
        ReservationCommand command = buildReservationCommand(reservationId, eventId, section, seatCount, userId);
        reservationCommandInput.pipeInput(key, command);
    }

    private void drainSectionStatus() {
        sectionStatusOutput.readKeyValuesToList();
    }
}
