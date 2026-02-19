package com.keer.ticketmaster.config;

import com.keer.ticketmaster.avro.SectionSeatState;
import com.keer.ticketmaster.avro.ReservationRequestedEvent;
import com.keer.ticketmaster.avro.ReservationResultEvent;
import com.keer.ticketmaster.avro.SeatEvent;
import com.keer.ticketmaster.reservation.service.SeatAvailabilityChecker;
import com.keer.ticketmaster.ticket.stream.SeatAllocationProcessor;
import com.keer.ticketmaster.ticket.stream.SeatEventMaterializeProcessor;
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.state.StoreBuilder;
import org.apache.kafka.streams.state.Stores;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.EnableKafkaStreams;

import java.util.Map;

@Configuration
@Profile({"seat-processor", "default"})
@EnableKafkaStreams
public class SeatProcessorStreamsConfig {

    @Value("${spring.kafka.streams.properties[schema.registry.url]}")
    private String schemaRegistryUrl;

    @Bean
    public org.apache.kafka.streams.kstream.KStream<String, ReservationResultEvent> seatPipeline(
            StreamsBuilder builder,
            SeatAvailabilityChecker availableSeatCache) {

        Map<String, String> serdeConfig = Map.of("schema.registry.url", schemaRegistryUrl);

        SpecificAvroSerde<SeatEvent> seatEventSerde = newAvroSerde(serdeConfig);
        SpecificAvroSerde<SectionSeatState> seatStateSerde = newAvroSerde(serdeConfig);
        SpecificAvroSerde<ReservationRequestedEvent> requestedSerde = newAvroSerde(serdeConfig);
        SpecificAvroSerde<ReservationResultEvent> resultSerde = newAvroSerde(serdeConfig);

        StoreBuilder<KeyValueStore<String, SectionSeatState>> seatStoreBuilder =
                Stores.keyValueStoreBuilder(
                        Stores.persistentKeyValueStore(KafkaConstants.SEAT_INVENTORY_STORE),
                        Serdes.String(),
                        seatStateSerde
                );
        builder.addStateStore(seatStoreBuilder);

        // Step 1: seat-events → materialize to seat-inventory-store
        builder.stream(KafkaConstants.TOPIC_SEAT_EVENTS, Consumed.with(Serdes.String(), seatEventSerde))
                .process(() -> new SeatEventMaterializeProcessor(availableSeatCache), KafkaConstants.SEAT_INVENTORY_STORE);

        // Step 3: reservation-requests → SeatAllocationProcessor → reservation-results
        builder.stream(KafkaConstants.TOPIC_RESERVATION_REQUESTS, Consumed.with(Serdes.String(), requestedSerde))
                .process(() -> new SeatAllocationProcessor(availableSeatCache), KafkaConstants.SEAT_INVENTORY_STORE)
                .to(KafkaConstants.TOPIC_RESERVATION_RESULTS, Produced.with(Serdes.String(), resultSerde));

        return null;
    }

    private static <T extends org.apache.avro.specific.SpecificRecord> SpecificAvroSerde<T> newAvroSerde(
            Map<String, String> serdeConfig) {
        SpecificAvroSerde<T> serde = new SpecificAvroSerde<>();
        serde.configure(serdeConfig, false);
        return serde;
    }
}
