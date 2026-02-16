package com.keer.ticketmaster.ticket.stream;

import com.keer.ticketmaster.avro.ReservationRequestedEvent;
import com.keer.ticketmaster.avro.ReservationResultEvent;
import com.keer.ticketmaster.avro.SeatState;
import com.keer.ticketmaster.avro.SeatStateStatus;
import com.keer.ticketmaster.config.KafkaStreamsConfig;
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
        long eventId = request.getEventId();
        String section = request.getSection();
        int seatCount = request.getSeatCount();

        String keyPrefix = eventId + "-" + section;
        List<SeatState> availableSeats = new ArrayList<>();

        try (KeyValueIterator<String, SeatState> iterator = seatStore.prefixScan(
                keyPrefix, org.apache.kafka.common.serialization.Serdes.String().serializer())) {
            while (iterator.hasNext()) {
                var entry = iterator.next();
                SeatState seat = entry.value;
                if (SeatStateStatus.AVAILABLE == seat.getStatus()) {
                    availableSeats.add(seat);
                }
            }
        }

        availableSeats.sort(Comparator.comparing(SeatState::getSeatNumber));

        List<String> allocatedSeats = findConsecutiveSeats(availableSeats, seatCount);

        ReservationResultEvent result;
        if (allocatedSeats != null && allocatedSeats.size() == seatCount) {
            for (String seatNumber : allocatedSeats) {
                String seatKey = eventId + "-" + seatNumber;
                SeatState seat = seatStore.get(seatKey);
                if (seat != null) {
                    SeatState updated = SeatState.newBuilder(seat)
                            .setStatus(SeatStateStatus.RESERVED)
                            .setReservationId(reservationId)
                            .build();
                    seatStore.put(seatKey, updated);
                }
            }

            result = ReservationResultEvent.newBuilder()
                    .setReservationId(reservationId)
                    .setSuccess(true)
                    .setAllocatedSeats(allocatedSeats)
                    .setFailureReason(null)
                    .setTimestamp(Instant.now().toEpochMilli())
                    .build();
        } else {
            result = ReservationResultEvent.newBuilder()
                    .setReservationId(reservationId)
                    .setSuccess(false)
                    .setAllocatedSeats(List.of())
                    .setFailureReason("Not enough consecutive available seats in section " + section)
                    .setTimestamp(Instant.now().toEpochMilli())
                    .build();
        }

        String eventKey = String.valueOf(eventId);
        context.forward(new Record<>(eventKey, result, record.timestamp()));
    }

    private List<String> findConsecutiveSeats(List<SeatState> availableSeats, int seatCount) {
        if (availableSeats.size() < seatCount) {
            return null;
        }

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
