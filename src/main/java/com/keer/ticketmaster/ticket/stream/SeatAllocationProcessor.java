package com.keer.ticketmaster.ticket.stream;

import com.keer.ticketmaster.avro.SectionSeatState;
import com.keer.ticketmaster.avro.ReservationCompletedEvent;
import com.keer.ticketmaster.avro.ReservationRequestedEvent;
import com.keer.ticketmaster.config.KafkaConstants;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.state.KeyValueStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class SeatAllocationProcessor
        implements Processor<String, ReservationRequestedEvent, String, ReservationCompletedEvent> {

    private ProcessorContext<String, ReservationCompletedEvent> context;
    private KeyValueStore<String, SectionSeatState> seatStore;

    @Override
    public void init(ProcessorContext<String, ReservationCompletedEvent> context) {
        this.context = context;
        this.seatStore = context.getStateStore(KafkaConstants.SEAT_INVENTORY_STORE);
    }

    @Override
    public void process(Record<String, ReservationRequestedEvent> record) {
        ReservationRequestedEvent request = record.value();
        String reservationId = request.getReservationId();
        long eventId = request.getEventId();
        String section = request.getSection();
        int seatCount = request.getSeatCount();
        String userId = request.getUserId();

        String storeKey = eventId + "-" + section;
        SectionSeatState sectionState = seatStore.get(storeKey);

        // Fast fail: no section data or not enough available seats
        if (sectionState == null || sectionState.getAvailableCount() < seatCount) {
            ReservationCompletedEvent result = ReservationCompletedEvent.newBuilder()
                    .setReservationId(reservationId)
                    .setEventId(eventId)
                    .setUserId(userId)
                    .setStatus("REJECTED")
                    .setSection(section)
                    .setSeatCount(seatCount)
                    .setAllocatedSeats(List.of())
                    .setTimestamp(Instant.now().toEpochMilli())
                    .build();
            context.forward(new Record<>(reservationId, result, record.timestamp()));
            return;
        }

        // Collect available seats from the map
        Map<String, String> seatStatuses = sectionState.getSeatStatuses();
        List<String> availableSeats = new ArrayList<>();
        for (Map.Entry<String, String> entry : seatStatuses.entrySet()) {
            if ("AVAILABLE".equals(entry.getValue())) {
                availableSeats.add(entry.getKey());
            }
        }

        availableSeats.sort(Comparator.comparing(this::extractSeatNumber));

        List<String> allocatedSeats = findConsecutiveSeats(availableSeats, seatCount);

        ReservationCompletedEvent result;
        if (allocatedSeats != null && allocatedSeats.size() == seatCount) {
            for (String seatNumber : allocatedSeats) {
                seatStatuses.put(seatNumber, "RESERVED");
            }
            sectionState.setAvailableCount(sectionState.getAvailableCount() - seatCount);
            seatStore.put(storeKey, sectionState);

            result = ReservationCompletedEvent.newBuilder()
                    .setReservationId(reservationId)
                    .setEventId(eventId)
                    .setUserId(userId)
                    .setStatus("CONFIRMED")
                    .setSection(section)
                    .setSeatCount(seatCount)
                    .setAllocatedSeats(allocatedSeats)
                    .setTimestamp(Instant.now().toEpochMilli())
                    .build();
        } else {
            result = ReservationCompletedEvent.newBuilder()
                    .setReservationId(reservationId)
                    .setEventId(eventId)
                    .setUserId(userId)
                    .setStatus("REJECTED")
                    .setSection(section)
                    .setSeatCount(seatCount)
                    .setAllocatedSeats(List.of())
                    .setTimestamp(Instant.now().toEpochMilli())
                    .build();
        }

        context.forward(new Record<>(reservationId, result, record.timestamp()));
    }

    private List<String> findConsecutiveSeats(List<String> availableSeats, int seatCount) {
        if (availableSeats.size() < seatCount) {
            return null;
        }

        for (int i = 0; i <= availableSeats.size() - seatCount; i++) {
            boolean consecutive = true;
            List<String> candidate = new ArrayList<>();
            candidate.add(availableSeats.get(i));

            for (int j = 1; j < seatCount; j++) {
                String prevSeat = availableSeats.get(i + j - 1);
                String currSeat = availableSeats.get(i + j);

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
        int num1 = extractSeatNumber(seat1);
        int num2 = extractSeatNumber(seat2);
        return num2 - num1 == 1;
    }

    private int extractSeatNumber(String seatNumber) {
        int lastDash = seatNumber.lastIndexOf('-');
        if (lastDash >= 0 && lastDash < seatNumber.length() - 1) {
            return Integer.parseInt(seatNumber.substring(lastDash + 1));
        }
        return -1;
    }
}
