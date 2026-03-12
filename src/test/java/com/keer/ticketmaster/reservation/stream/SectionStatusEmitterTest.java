package com.keer.ticketmaster.reservation.stream;

import com.keer.ticketmaster.avro.ReservationCommand;
import com.keer.ticketmaster.avro.ReservationCompletedEvent;
import com.keer.ticketmaster.avro.SectionStatusEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SectionStatusEmitterTest extends StreamProcessorTestBase {

    @Test
    void emitsStatusAfterConfirmedReservation() {
        initSection(1L, "A", 1, 5);
        // Drain init status event
        sectionStatusOutput.readKeyValuesToList();

        // Allocate 2 seats
        ReservationCommand command = buildReservationCommand("r1", 1L, "A", 2, "user1");
        reservationCommandInput.pipeInput("1-A", command);

        // Drain reservation-completed
        ReservationCompletedEvent completed = reservationCompletedOutput.readValue();
        assertEquals("CONFIRMED", completed.getStatus());

        // SectionStatusEmitter should forward updated state to section-status
        assertFalse(sectionStatusOutput.isEmpty());
        SectionStatusEvent statusEvent = sectionStatusOutput.readValue();
        assertEquals(1L, statusEvent.getEventId());
        assertEquals("A", statusEvent.getSection());
        assertEquals(3, statusEvent.getAvailableCount());
    }

    @Test
    void emitsStatusAfterRejectedReservation() {
        initSection(1L, "A", 1, 2);
        sectionStatusOutput.readKeyValuesToList();

        // Request 5 seats when only 2 available — REJECTED
        ReservationCommand command = buildReservationCommand("r1", 1L, "A", 5, "user1");
        reservationCommandInput.pipeInput("1-A", command);

        ReservationCompletedEvent completed = reservationCompletedOutput.readValue();
        assertEquals("REJECTED", completed.getStatus());

        // Emitter still forwards current state (unchanged) for REJECTED events
        assertFalse(sectionStatusOutput.isEmpty());
        SectionStatusEvent statusEvent = sectionStatusOutput.readValue();
        assertEquals(2, statusEvent.getAvailableCount());
    }

    @Test
    void noStateInStore_shouldNotEmit() {
        // No initSection — state store empty
        // Send a reservation that will be REJECTED (no section data)
        ReservationCommand command = buildReservationCommand("r1", 99L, "Z", 1, "user1");
        reservationCommandInput.pipeInput("99-Z", command);

        // Drain the REJECTED event
        reservationCompletedOutput.readValue();

        // SectionStatusEmitter should NOT forward because store has no data for 99-Z
        assertTrue(sectionStatusOutput.isEmpty());
    }
}
