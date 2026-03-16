package com.keer.ticketmaster.streaming.seat;

import com.keer.ticketmaster.avro.*;
import com.keer.ticketmaster.config.KafkaConstants;
import io.confluent.kafka.schemaregistry.client.MockSchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.*;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.kstream.ValueMapper;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.state.StoreBuilder;
import org.apache.kafka.streams.state.Stores;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.util.Map;
import java.util.Properties;

/**
 * Test base that mirrors the SeatProcessorTopology:
 * - section-init -> SectionInitProcessor -> section-status
 * - seat-allocation-requests -> SeatAllocationProcessor -> seat-allocation-results
 * - allocation results -> SectionStatusEmitter -> section-status
 */
public abstract class StreamProcessorTestBase {

    protected TopologyTestDriver testDriver;
    protected TestInputTopic<String, SectionInitCommand> sectionInitInput;
    protected TestInputTopic<String, ReservationCommand> seatAllocationRequestInput;
    protected TestOutputTopic<String, SectionStatusEvent> sectionStatusOutput;
    protected TestOutputTopic<String, ReservationCompletedEvent> seatAllocationResultOutput;

    private SchemaRegistryClient schemaRegistryClient;

    @BeforeEach
    void setUp() {
        schemaRegistryClient = new MockSchemaRegistryClient();
        Map<String, String> serdeConfig = Map.of("schema.registry.url", "mock://test");

        SpecificAvroSerde<ReservationCommand> commandSerde = newAvroSerde(serdeConfig);
        SpecificAvroSerde<ReservationCompletedEvent> completedSerde = newAvroSerde(serdeConfig);
        SpecificAvroSerde<SectionInitCommand> sectionInitSerde = newAvroSerde(serdeConfig);
        SpecificAvroSerde<SectionSeatState> seatStateSerde = newAvroSerde(serdeConfig);
        SpecificAvroSerde<SectionStatusEvent> statusEventSerde = newAvroSerde(serdeConfig);

        StreamsBuilder builder = new StreamsBuilder();

        StoreBuilder<KeyValueStore<String, SectionSeatState>> seatStoreBuilder =
                Stores.keyValueStoreBuilder(
                        Stores.persistentKeyValueStore(KafkaConstants.SEAT_INVENTORY_STORE),
                        Serdes.String(),
                        seatStateSerde
                );
        builder.addStateStore(seatStoreBuilder);

        // Init path: section-init -> SectionInitProcessor -> section-status
        builder.stream(KafkaConstants.TOPIC_SECTION_INIT, Consumed.with(Serdes.String(), sectionInitSerde))
                .process(SectionInitProcessor::new, KafkaConstants.SEAT_INVENTORY_STORE)
                .mapValues((ValueMapper<SectionSeatState, SectionStatusEvent>) state ->
                        SectionStatusEvent.newBuilder()
                                .setEventId(state.getEventId())
                                .setSection(state.getSection())
                                .setAvailableCount(state.getAvailableCount())
                                .setTimestamp(System.currentTimeMillis())
                                .build())
                .to(KafkaConstants.TOPIC_SECTION_STATUS, Produced.with(Serdes.String(), statusEventSerde));

        // Allocation path: seat-allocation-requests -> SeatAllocationProcessor -> seat-allocation-results
        var completedStream = builder.stream(KafkaConstants.TOPIC_SEAT_ALLOCATION_REQUESTS, Consumed.with(Serdes.String(), commandSerde))
                .process(SeatAllocationProcessor::new, KafkaConstants.SEAT_INVENTORY_STORE);

        completedStream.to(KafkaConstants.TOPIC_SEAT_ALLOCATION_RESULTS, Produced.with(Serdes.String(), completedSerde));

        // Status update path: allocation results -> SectionStatusEmitter -> section-status
        completedStream
                .process(SectionStatusEmitter::new, KafkaConstants.SEAT_INVENTORY_STORE)
                .mapValues((ValueMapper<SectionSeatState, SectionStatusEvent>) state ->
                        SectionStatusEvent.newBuilder()
                                .setEventId(state.getEventId())
                                .setSection(state.getSection())
                                .setAvailableCount(state.getAvailableCount())
                                .setTimestamp(System.currentTimeMillis())
                                .build())
                .to(KafkaConstants.TOPIC_SECTION_STATUS, Produced.with(Serdes.String(), statusEventSerde));

        Topology topology = builder.build();

        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, "test-app");
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.StringSerde.class);
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, SpecificAvroSerde.class);
        props.put("schema.registry.url", "mock://test");

        testDriver = new TopologyTestDriver(topology, props);

        sectionInitInput = testDriver.createInputTopic(
                KafkaConstants.TOPIC_SECTION_INIT,
                new StringSerializer(),
                sectionInitSerde.serializer()
        );

        seatAllocationRequestInput = testDriver.createInputTopic(
                KafkaConstants.TOPIC_SEAT_ALLOCATION_REQUESTS,
                new StringSerializer(),
                commandSerde.serializer()
        );

        sectionStatusOutput = testDriver.createOutputTopic(
                KafkaConstants.TOPIC_SECTION_STATUS,
                new StringDeserializer(),
                statusEventSerde.deserializer()
        );

        seatAllocationResultOutput = testDriver.createOutputTopic(
                KafkaConstants.TOPIC_SEAT_ALLOCATION_RESULTS,
                new StringDeserializer(),
                completedSerde.deserializer()
        );
    }

    @AfterEach
    void tearDown() {
        if (testDriver != null) {
            testDriver.close();
        }
    }

    protected KeyValueStore<String, SectionSeatState> getSeatInventoryStore() {
        return testDriver.getKeyValueStore(KafkaConstants.SEAT_INVENTORY_STORE);
    }

    protected void initSection(long eventId, String section, int rows, int seatsPerRow) {
        initSection(eventId, section, rows, seatsPerRow, java.util.List.of());
    }

    protected void initSection(long eventId, String section, int rows, int seatsPerRow, java.util.List<String> initialReserved) {
        String key = eventId + "-" + section;
        SectionInitCommand command = SectionInitCommand.newBuilder()
                .setEventId(eventId)
                .setSection(section)
                .setRows(rows)
                .setSeatsPerRow(seatsPerRow)
                .setInitialReserved(initialReserved)
                .build();
        sectionInitInput.pipeInput(key, command);
    }

    protected ReservationCommand buildReservationCommand(String reservationId, long eventId, String section, int seatCount, String userId) {
        return ReservationCommand.newBuilder()
                .setReservationId(reservationId)
                .setEventId(eventId)
                .setSection(section)
                .setSeatCount(seatCount)
                .setUserId(userId)
                .setTimestamp(System.currentTimeMillis())
                .build();
    }

    private <T extends org.apache.avro.specific.SpecificRecord> SpecificAvroSerde<T> newAvroSerde(
            Map<String, String> serdeConfig) {
        SpecificAvroSerde<T> serde = new SpecificAvroSerde<>(schemaRegistryClient);
        serde.configure(serdeConfig, false);
        return serde;
    }
}
