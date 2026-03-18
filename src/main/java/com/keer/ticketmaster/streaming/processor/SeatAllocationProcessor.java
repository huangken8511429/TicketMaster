package com.keer.ticketmaster.streaming.processor;

import com.keer.ticketmaster.avro.SectionSeatState;
import com.keer.ticketmaster.avro.BookingCommand;
import com.keer.ticketmaster.avro.BookingCompletedEvent;
import com.keer.ticketmaster.config.StateStore;
import com.keer.ticketmaster.config.StoreKeyUtil;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.state.KeyValueStore;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Allocates consecutive seats using bitmap-based state store.
 *
 * Bitmap operations:
 * - findConsecutiveBits(): O(n) scan for N consecutive set bits
 * - clearBits(): mark allocated seats as taken
 * - State size: ~63 bytes per 500 seats (vs ~100KB with List<String>)
 */
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
        int subPartition = command.getTargetSubPartition();

        String storeKey = StoreKeyUtil.seatKey(eventId, section, subPartition);
        SectionSeatState sectionState = seatStore.get(storeKey);

        // Fast fail: no section data or not enough available seats
        if (sectionState == null || sectionState.getAvailableCount() < seatCount) {
            context.forward(new Record<>(bookingId, buildRejected(command), record.timestamp()));
            return;
        }

        // Get bitmap as byte array
        ByteBuffer bitmapBuffer = sectionState.getSeatBitmap();
        byte[] bitmap = new byte[bitmapBuffer.remaining()];
        bitmapBuffer.get(bitmap);
        bitmapBuffer.rewind();

        int totalSeats = sectionState.getTotalSeats();
        int seatOffset = sectionState.getSeatOffset();

        // Find consecutive available seats in bitmap
        int startBit = findConsecutiveBits(bitmap, totalSeats, seatCount);

        if (startBit < 0) {
            context.forward(new Record<>(bookingId, buildRejected(command), record.timestamp()));
            return;
        }

        // Allocate: clear bits and build seat name list
        List<String> allocatedSeats = new ArrayList<>(seatCount);
        for (int i = startBit; i < startBit + seatCount; i++) {
            bitmap[i / 8] &= (byte) ~(1 << (i % 8));
            allocatedSeats.add(section + "-" + (seatOffset + i));
        }

        // Update state
        int newAvailableCount = sectionState.getAvailableCount() - seatCount;
        sectionState.setSeatBitmap(ByteBuffer.wrap(bitmap));
        sectionState.setAvailableCount(newAvailableCount);
        seatStore.put(storeKey, sectionState);

        BookingCompletedEvent result = BookingCompletedEvent.newBuilder()
                .setBookingId(bookingId)
                .setEventId(eventId)
                .setUserId(userId)
                .setStatus("CONFIRMED")
                .setSection(section)
                .setSeatCount(seatCount)
                .setSubPartition(subPartition)
                .setAllocatedSeats(allocatedSeats)
                .setTimestamp(Instant.now().toEpochMilli())
                .build();

        context.forward(new Record<>(bookingId, result, record.timestamp()));
    }

    /**
     * Find a block of `count` consecutive set bits (available seats) in the bitmap.
     * Returns the start bit index, or -1 if not found.
     */
    private int findConsecutiveBits(byte[] bitmap, int totalSeats, int count) {
        int consecutive = 0;
        int start = -1;

        for (int i = 0; i < totalSeats; i++) {
            boolean available = (bitmap[i / 8] & (1 << (i % 8))) != 0;

            if (available) {
                if (consecutive == 0) start = i;
                consecutive++;
                if (consecutive == count) return start;
            } else {
                consecutive = 0;
            }
        }
        return -1;
    }

    private BookingCompletedEvent buildRejected(BookingCommand command) {
        return BookingCompletedEvent.newBuilder()
                .setBookingId(command.getBookingId())
                .setEventId(command.getEventId())
                .setUserId(command.getUserId())
                .setStatus("REJECTED")
                .setSection(command.getSection())
                .setSeatCount(command.getSeatCount())
                .setSubPartition(command.getTargetSubPartition())
                .setAllocatedSeats(List.of())
                .setTimestamp(Instant.now().toEpochMilli())
                .build();
    }
}
