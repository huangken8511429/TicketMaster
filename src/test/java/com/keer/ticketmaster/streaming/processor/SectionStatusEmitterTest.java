package com.keer.ticketmaster.streaming.processor;

import com.keer.ticketmaster.avro.BookingCommand;
import com.keer.ticketmaster.avro.BookingCompletedEvent;
import com.keer.ticketmaster.avro.SectionStatusEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SectionStatusEmitterTest extends StreamProcessorTestBase {

    @Test
    void emitsStatusAfterConfirmedReservation() {
        initSection(1L, "A", 1, 5);
        sectionStatusOutput.readKeyValuesToList();

        BookingCommand command = buildBookingCommand("r1", 1L, "A", 2, "user1");
        seatAllocationRequestInput.pipeInput("1-A", command);

        BookingCompletedEvent completed = seatAllocationResultOutput.readValue();
        assertEquals("CONFIRMED", completed.getStatus());

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

        BookingCommand command = buildBookingCommand("r1", 1L, "A", 5, "user1");
        seatAllocationRequestInput.pipeInput("1-A", command);

        BookingCompletedEvent completed = seatAllocationResultOutput.readValue();
        assertEquals("REJECTED", completed.getStatus());

        assertFalse(sectionStatusOutput.isEmpty());
        SectionStatusEvent statusEvent = sectionStatusOutput.readValue();
        assertEquals(2, statusEvent.getAvailableCount());
    }

    @Test
    void noStateInStore_shouldNotEmit() {
        BookingCommand command = buildBookingCommand("r1", 99L, "Z", 1, "user1");
        seatAllocationRequestInput.pipeInput("99-Z", command);

        seatAllocationResultOutput.readValue();

        assertTrue(sectionStatusOutput.isEmpty());
    }
}
