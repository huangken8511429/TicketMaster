package com.keer.ticketmaster.config;

import com.keer.ticketmaster.avro.SectionSeatState;
import com.keer.ticketmaster.avro.ReservationCompletedEvent;
import com.keer.ticketmaster.avro.ReservationRequestedEvent;
import com.keer.ticketmaster.avro.SectionInitCommand;
import com.keer.ticketmaster.ticket.stream.SeatAllocationProcessor;
import com.keer.ticketmaster.ticket.stream.SectionInitProcessor;
import com.keer.ticketmaster.ticket.stream.SectionStatusEmitter;
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
    public org.apache.kafka.streams.kstream.KStream<String, ReservationCompletedEvent> seatPipeline(
            StreamsBuilder builder) {

        Map<String, String> serdeConfig = Map.of("schema.registry.url", schemaRegistryUrl);

        SpecificAvroSerde<SectionInitCommand> sectionInitSerde = newAvroSerde(serdeConfig);
        SpecificAvroSerde<SectionSeatState> seatStateSerde = newAvroSerde(serdeConfig);
        SpecificAvroSerde<ReservationRequestedEvent> requestedSerde = newAvroSerde(serdeConfig);
        SpecificAvroSerde<ReservationCompletedEvent> completedSerde = newAvroSerde(serdeConfig);

        StoreBuilder<KeyValueStore<String, SectionSeatState>> seatStoreBuilder =
                Stores.keyValueStoreBuilder(
                        Stores.persistentKeyValueStore(KafkaConstants.SEAT_INVENTORY_STORE),
                        Serdes.String(),
                        seatStateSerde
                );
        builder.addStateStore(seatStoreBuilder);

        // Init path: section-init → SectionInitProcessor → section-status
        builder.stream(KafkaConstants.TOPIC_SECTION_INIT, Consumed.with(Serdes.String(), sectionInitSerde))
                .process(SectionInitProcessor::new, KafkaConstants.SEAT_INVENTORY_STORE)
                .to(KafkaConstants.TOPIC_SECTION_STATUS, Produced.with(Serdes.String(), seatStateSerde));

        // Allocation path: reservation-requests → SeatAllocationProcessor → reservation-completed
        var completedStream = builder.stream(KafkaConstants.TOPIC_RESERVATION_REQUESTS, Consumed.with(Serdes.String(), requestedSerde))
                .process(SeatAllocationProcessor::new, KafkaConstants.SEAT_INVENTORY_STORE);

        completedStream.to(KafkaConstants.TOPIC_RESERVATION_COMPLETED, Produced.with(Serdes.String(), completedSerde));

        // State sharing path: allocation results → SectionStatusEmitter → section-status
        completedStream
                .process(SectionStatusEmitter::new, KafkaConstants.SEAT_INVENTORY_STORE)
                .to(KafkaConstants.TOPIC_SECTION_STATUS, Produced.with(Serdes.String(), seatStateSerde));

        return null;
    }

    private static <T extends org.apache.avro.specific.SpecificRecord> SpecificAvroSerde<T> newAvroSerde(
            Map<String, String> serdeConfig) {
        SpecificAvroSerde<T> serde = new SpecificAvroSerde<>();
        serde.configure(serdeConfig, false);
        return serde;
    }
}
