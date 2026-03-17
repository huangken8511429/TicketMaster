package com.keer.ticketmaster.streaming.processor;

import com.keer.ticketmaster.avro.SectionSeatState;
import com.keer.ticketmaster.avro.BookingCommand;
import com.keer.ticketmaster.avro.BookingCompletedEvent;
import com.keer.ticketmaster.config.StateStore;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.state.KeyValueStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class SeatAllocationProcessor
        implements Processor<String, BookingCommand, String, BookingCompletedEvent> {

    private ProcessorContext<String, BookingCompletedEvent> context;
    private KeyValueStore<String, SectionSeatState> seatStore;

    @Override
    public void init(ProcessorContext<String, BookingCompletedEvent> context) {
        this.context = context;
        this.seatStore = context.getStateStore(StateStore.SEAT_INVENTORY);
    }

    @Override
    public void process(Record<String, BookingCommand> record) {
        BookingCommand command = record.value();
        String bookingId = command.getBookingId();
        long eventId = command.getEventId();
        String section = command.getSection();
        int seatCount = command.getSeatCount();
        String userId = command.getUserId();

        String storeKey = eventId + "-" + section;
        SectionSeatState sectionState = seatStore.get(storeKey);

        // Fast fail: no section data or not enough available seats
        if (sectionState == null || sectionState.getAvailableCount() < seatCount) {
            context.forward(new Record<>(bookingId, buildRejected(bookingId, eventId, userId, section, seatCount), record.timestamp()));
            return;
        }

        // availableSeats is already sorted from init (by globalIndex order)
        List<String> availableSeats = sectionState.getAvailableSeats();
        int startIndex = findConsecutiveBlock(availableSeats, seatCount);

        if (startIndex < 0) {
            context.forward(new Record<>(bookingId, buildRejected(bookingId, eventId, userId, section, seatCount), record.timestamp()));
            return;
        }

        // Extract allocated seats and build new available list without them
        List<String> allocatedSeats = new ArrayList<>(availableSeats.subList(startIndex, startIndex + seatCount));
        List<String> newAvailable = new ArrayList<>(availableSeats.size() - seatCount);
        newAvailable.addAll(availableSeats.subList(0, startIndex));
        newAvailable.addAll(availableSeats.subList(startIndex + seatCount, availableSeats.size()));

        sectionState.setAvailableSeats(newAvailable);
        sectionState.setAvailableCount(newAvailable.size());
        seatStore.put(storeKey, sectionState);

        BookingCompletedEvent result = BookingCompletedEvent.newBuilder()
                .setBookingId(bookingId)
                .setEventId(eventId)
                .setUserId(userId)
                .setStatus("CONFIRMED")
                .setSection(section)
                .setSeatCount(seatCount)
                .setAllocatedSeats(allocatedSeats)
                .setTimestamp(Instant.now().toEpochMilli())
                .build();

        context.forward(new Record<>(bookingId, result, record.timestamp()));
    }

    /**
     * Find a block of `seatCount` consecutive seats in the sorted available list.
     * Returns the start index, or -1 if not found.
     */
    private int findConsecutiveBlock(List<String> availableSeats, int seatCount) {
        if (availableSeats.size() < seatCount) {
            return -1;
        }

        for (int i = 0; i <= availableSeats.size() - seatCount; i++) {
            boolean consecutive = true;
            for (int j = 1; j < seatCount; j++) {
                int prev = extractSeatNumber(availableSeats.get(i + j - 1));
                int curr = extractSeatNumber(availableSeats.get(i + j));
                if (curr - prev != 1) {
                    consecutive = false;
                    break;
                }
            }
            if (consecutive) {
                return i;
            }
        }
        return -1;
    }

    private int extractSeatNumber(String seatName) {
        int lastDash = seatName.lastIndexOf('-');
        return Integer.parseInt(seatName.substring(lastDash + 1));
    }

    private BookingCompletedEvent buildRejected(String bookingId, long eventId, String userId, String section, int seatCount) {
        return BookingCompletedEvent.newBuilder()
                .setBookingId(bookingId)
                .setEventId(eventId)
                .setUserId(userId)
                .setStatus("REJECTED")
                .setSection(section)
                .setSeatCount(seatCount)
                .setAllocatedSeats(List.of())
                .setTimestamp(Instant.now().toEpochMilli())
                .build();
    }
}
