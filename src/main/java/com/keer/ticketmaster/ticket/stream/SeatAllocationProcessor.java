package com.keer.ticketmaster.ticket.stream;

import com.keer.ticketmaster.config.KafkaStreamsConfig;
import com.keer.ticketmaster.reservation.event.ReservationRequestedEvent;
import com.keer.ticketmaster.reservation.event.ReservationResultEvent;
import com.keer.ticketmaster.ticket.model.SeatState;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.state.KeyValueIterator;
import org.apache.kafka.streams.state.KeyValueStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class SeatAllocationProcessor
        implements Processor<String, ReservationRequestedEvent, String, ReservationResultEvent> {

    private ProcessorContext<String, ReservationResultEvent> context;
    private KeyValueStore<String, SeatState> seatStore;

    @Override
    public void init(ProcessorContext<String, ReservationResultEvent> context) {
        this.context = context;
        this.seatStore = context.getStateStore(KafkaStreamsConfig.SEAT_INVENTORY_STORE);
    }

    @Override
    public void process(Record<String, ReservationRequestedEvent> record) {
        ReservationRequestedEvent request = record.value();
        String reservationId = request.getReservationId();
        Long eventId = request.getEventId();
        String section = request.getSection();
        int seatCount = request.getSeatCount();

        // Scan seat-inventory-store for seats matching eventId + section prefix
        String keyPrefix = eventId + "-" + section;
        List<SeatState> availableSeats = new ArrayList<>();

        try (KeyValueIterator<String, SeatState> iterator = seatStore.prefixScan(keyPrefix, org.apache.kafka.common.serialization.Serdes.String().serializer())) {
            while (iterator.hasNext()) {
                var entry = iterator.next();
                SeatState seat = entry.value;
                if ("AVAILABLE".equals(seat.getStatus())) {
                    availableSeats.add(seat);
                }
            }
        }

        // Sort by seat number for consecutive seat allocation
        availableSeats.sort(Comparator.comparing(SeatState::getSeatNumber));

        // Find consecutive available seats
        List<String> allocatedSeats = findConsecutiveSeats(availableSeats, seatCount);

        ReservationResultEvent result;
        if (allocatedSeats != null && allocatedSeats.size() == seatCount) {
            // Mark seats as RESERVED
            for (String seatNumber : allocatedSeats) {
                String seatKey = eventId + "-" + seatNumber;
                SeatState seat = seatStore.get(seatKey);
                if (seat != null) {
                    seat.setStatus("RESERVED");
                    seat.setReservationId(reservationId);
                    seatStore.put(seatKey, seat);
                }
            }

            result = new ReservationResultEvent(
                    reservationId,
                    true,
                    allocatedSeats,
                    null,
                    Instant.now()
            );
        } else {
            result = new ReservationResultEvent(
                    reservationId,
                    false,
                    List.of(),
                    "Not enough consecutive available seats in section " + section,
                    Instant.now()
            );
        }

        context.forward(new Record<>(reservationId, result, record.timestamp()));
    }

    private List<String> findConsecutiveSeats(List<SeatState> availableSeats, int seatCount) {
        if (availableSeats.size() < seatCount) {
            return null;
        }

        // Try to find a consecutive run of seatCount seats
        // Seats are sorted by seatNumber (e.g., A-001, A-002, A-003)
        for (int i = 0; i <= availableSeats.size() - seatCount; i++) {
            boolean consecutive = true;
            List<String> candidate = new ArrayList<>();
            candidate.add(availableSeats.get(i).getSeatNumber());

            for (int j = 1; j < seatCount; j++) {
                String prevSeat = availableSeats.get(i + j - 1).getSeatNumber();
                String currSeat = availableSeats.get(i + j).getSeatNumber();

                if (!areConsecutive(prevSeat, currSeat)) {
                    consecutive = false;
                    break;
                }
                candidate.add(currSeat);
            }

            if (consecutive) {
                return candidate;
            }
        }
        return null;
    }

    private boolean areConsecutive(String seat1, String seat2) {
        // Seat format: "A-001", "A-002" etc.
        // Extract the numeric suffix and check if they differ by 1
        int num1 = extractSeatNumber(seat1);
        int num2 = extractSeatNumber(seat2);
        return num2 - num1 == 1;
    }

    private int extractSeatNumber(String seatNumber) {
        // Extract numeric part after the last '-'
        int lastDash = seatNumber.lastIndexOf('-');
        if (lastDash >= 0 && lastDash < seatNumber.length() - 1) {
            return Integer.parseInt(seatNumber.substring(lastDash + 1));
        }
        return -1;
    }
}
