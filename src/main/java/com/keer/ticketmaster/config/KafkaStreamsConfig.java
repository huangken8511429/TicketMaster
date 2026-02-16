package com.keer.ticketmaster.config;

import com.keer.ticketmaster.reservation.event.ReservationCommand;
import com.keer.ticketmaster.reservation.event.ReservationCompletedEvent;
import com.keer.ticketmaster.reservation.event.ReservationRequestedEvent;
import com.keer.ticketmaster.reservation.event.ReservationResultEvent;
import com.keer.ticketmaster.reservation.model.ReservationState;
import com.keer.ticketmaster.reservation.stream.ReservationCommandProcessor;
import com.keer.ticketmaster.reservation.stream.ReservationResultProcessor;
import com.keer.ticketmaster.ticket.event.SeatEvent;
import com.keer.ticketmaster.ticket.model.SeatState;
import com.keer.ticketmaster.ticket.stream.SeatAllocationProcessor;
import com.keer.ticketmaster.ticket.stream.SeatEventMaterializeProcessor;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.state.StoreBuilder;
import org.apache.kafka.streams.state.Stores;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafkaStreams;
import org.springframework.kafka.support.serializer.JsonSerde;

import java.util.Map;

@Configuration
@EnableKafkaStreams
public class KafkaStreamsConfig {

    public static final String RESERVATION_STORE = "reservation-store";
    public static final String SEAT_INVENTORY_STORE = "seat-inventory-store";

    public static final String TOPIC_RESERVATION_COMMANDS = "reservation-commands";
    public static final String TOPIC_RESERVATION_REQUESTS = "reservation-requests";
    public static final String TOPIC_RESERVATION_RESULTS = "reservation-results";
    public static final String TOPIC_RESERVATION_COMPLETED = "reservation-completed";
    public static final String TOPIC_SEAT_EVENTS = "seat-events";

    private static final Map<String, String> TRUSTED_PACKAGES = Map.of(
            "spring.json.trusted.packages", "com.keer.ticketmaster.*"
    );

    @Bean
    public KStream<String, ReservationCompletedEvent> reservationPipeline(StreamsBuilder builder) {
        JsonSerde<ReservationState> reservationStateSerde = newJsonSerde(ReservationState.class);
        JsonSerde<SeatState> seatStateSerde = newJsonSerde(SeatState.class);
        JsonSerde<SeatEvent> seatEventSerde = newJsonSerde(SeatEvent.class);
        JsonSerde<ReservationCommand> commandSerde = newJsonSerde(ReservationCommand.class);
        JsonSerde<ReservationRequestedEvent> requestedSerde = newJsonSerde(ReservationRequestedEvent.class);
        JsonSerde<ReservationResultEvent> resultSerde = newJsonSerde(ReservationResultEvent.class);
        JsonSerde<ReservationCompletedEvent> completedSerde = newJsonSerde(ReservationCompletedEvent.class);

        // State store: reservation-store
        StoreBuilder<KeyValueStore<String, ReservationState>> reservationStoreBuilder =
                Stores.keyValueStoreBuilder(
                        Stores.persistentKeyValueStore(RESERVATION_STORE),
                        Serdes.String(),
                        reservationStateSerde
                );
        builder.addStateStore(reservationStoreBuilder);

        // State store: seat-inventory-store
        StoreBuilder<KeyValueStore<String, SeatState>> seatStoreBuilder =
                Stores.keyValueStoreBuilder(
                        Stores.persistentKeyValueStore(SEAT_INVENTORY_STORE),
                        Serdes.String(),
                        seatStateSerde
                );
        builder.addStateStore(seatStoreBuilder);

        // 1. seat-events → materialize to seat-inventory-store
        builder.stream(TOPIC_SEAT_EVENTS, Consumed.with(Serdes.String(), seatEventSerde))
                .process(SeatEventMaterializeProcessor::new, SEAT_INVENTORY_STORE);

        // 2. reservation-commands → ReservationCommandProcessor → reservation-requests
        builder.stream(TOPIC_RESERVATION_COMMANDS, Consumed.with(Serdes.String(), commandSerde))
                .process(ReservationCommandProcessor::new, RESERVATION_STORE)
                .to(TOPIC_RESERVATION_REQUESTS, Produced.with(Serdes.String(), requestedSerde));

        // 3. reservation-requests → SeatAllocationProcessor → reservation-results
        builder.stream(TOPIC_RESERVATION_REQUESTS, Consumed.with(Serdes.String(), requestedSerde))
                .process(SeatAllocationProcessor::new, SEAT_INVENTORY_STORE)
                .to(TOPIC_RESERVATION_RESULTS, Produced.with(Serdes.String(), resultSerde));

        // 4. reservation-results → ReservationResultProcessor → reservation-completed
        KStream<String, ReservationCompletedEvent> completedStream =
                builder.stream(TOPIC_RESERVATION_RESULTS, Consumed.with(Serdes.String(), resultSerde))
                        .process(ReservationResultProcessor::new, RESERVATION_STORE);
        completedStream.to(TOPIC_RESERVATION_COMPLETED, Produced.with(Serdes.String(), completedSerde));

        return completedStream;
    }

    private static <T> JsonSerde<T> newJsonSerde(Class<T> clazz) {
        JsonSerde<T> serde = new JsonSerde<>(clazz);
        serde.configure(TRUSTED_PACKAGES, false);
        return serde;
    }
}
