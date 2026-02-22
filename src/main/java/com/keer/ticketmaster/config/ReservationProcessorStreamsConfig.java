package com.keer.ticketmaster.config;

import com.keer.ticketmaster.avro.*;
import com.keer.ticketmaster.reservation.stream.ReservationCommandProcessor;
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.*;
import org.apache.kafka.streams.state.KeyValueStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.EnableKafkaStreams;

import java.util.Map;

@Configuration
@Profile({"reservation-processor", "default"})
@EnableKafkaStreams
public class ReservationProcessorStreamsConfig {

    @Value("${spring.kafka.streams.properties[schema.registry.url]}")
    private String schemaRegistryUrl;

    @Bean
    public KStream<String, ReservationCompletedEvent> reservationPipeline(StreamsBuilder builder) {

        Map<String, String> serdeConfig = Map.of("schema.registry.url", schemaRegistryUrl);

        SpecificAvroSerde<ReservationCommand> commandSerde = newAvroSerde(serdeConfig);
        SpecificAvroSerde<ReservationRequestedEvent> requestedSerde = newAvroSerde(serdeConfig);
        SpecificAvroSerde<ReservationCompletedEvent> completedSerde = newAvroSerde(serdeConfig);

        // Step 2: reservation-commands → ReservationCommandProcessor → reservation-requests
        builder.stream(KafkaConstants.TOPIC_RESERVATION_COMMANDS, Consumed.with(Serdes.String(), commandSerde))
                .process(ReservationCommandProcessor::new)
                .to(KafkaConstants.TOPIC_RESERVATION_REQUESTS, Produced.with(Serdes.String(), requestedSerde));

        // Step 5: reservation-completed → KTable query store
        builder.stream(KafkaConstants.TOPIC_RESERVATION_COMPLETED, Consumed.with(Serdes.String(), completedSerde))
                .toTable(
                        Materialized.<String, ReservationCompletedEvent, KeyValueStore<org.apache.kafka.common.utils.Bytes, byte[]>>as(KafkaConstants.RESERVATION_QUERY_STORE)
                                .withKeySerde(Serdes.String())
                                .withValueSerde(completedSerde)
                );

        return null;
    }

    private static <T extends org.apache.avro.specific.SpecificRecord> SpecificAvroSerde<T> newAvroSerde(
            Map<String, String> serdeConfig) {
        SpecificAvroSerde<T> serde = new SpecificAvroSerde<>();
        serde.configure(serdeConfig, false);
        return serde;
    }
}
