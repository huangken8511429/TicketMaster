package com.keer.ticketmaster.streaming.processor;

import com.keer.ticketmaster.avro.*;
import com.keer.ticketmaster.config.StateStore;
import com.keer.ticketmaster.config.Topic;
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
    protected TestInputTopic<String, BookingCommand> seatAllocationRequestInput;
    protected TestOutputTopic<String, SectionStatusEvent> sectionStatusOutput;
    protected TestOutputTopic<String, BookingCompletedEvent> seatAllocationResultOutput;

    private SchemaRegistryClient schemaRegistryClient;

    @BeforeEach
    void setUp() {
        schemaRegistryClient = new MockSchemaRegistryClient();
        Map<String, String> serdeConfig = Map.of("schema.registry.url", "mock://test");

        SpecificAvroSerde<BookingCommand> commandSerde = newAvroSerde(serdeConfig);
        SpecificAvroSerde<BookingCompletedEvent> completedSerde = newAvroSerde(serdeConfig);
        SpecificAvroSerde<SectionInitCommand> sectionInitSerde = newAvroSerde(serdeConfig);
        SpecificAvroSerde<SectionSeatState> seatStateSerde = newAvroSerde(serdeConfig);
        SpecificAvroSerde<SectionStatusEvent> statusEventSerde = newAvroSerde(serdeConfig);

        StreamsBuilder builder = new StreamsBuilder();

        StoreBuilder<KeyValueStore<String, SectionSeatState>> seatStoreBuilder =
                Stores.keyValueStoreBuilder(
                        Stores.persistentKeyValueStore(StateStore.SEAT_INVENTORY),
                        Serdes.String(),
                        seatStateSerde
                );
        builder.addStateStore(seatStoreBuilder);

        // Init path: section-init -> SectionInitProcessor -> section-status
        builder.stream(Topic.SECTION_INIT, Consumed.with(Serdes.String(), sectionInitSerde))
                .process(SectionInitProcessor::new, StateStore.SEAT_INVENTORY)
                .mapValues((ValueMapper<SectionSeatState, SectionStatusEvent>) state ->
                        SectionStatusEvent.newBuilder()
                                .setEventId(state.getEventId())
                                .setSection(state.getSection())
                                .setAvailableCount(state.getAvailableCount())
                                .setTimestamp(System.currentTimeMillis())
                                .build())
                .to(Topic.SECTION_STATUS, Produced.with(Serdes.String(), statusEventSerde));

        // Allocation path: seat-allocation-requests -> SeatAllocationProcessor -> seat-allocation-results
        var completedStream = builder.stream(Topic.SEAT_ALLOCATION_REQUESTS, Consumed.with(Serdes.String(), commandSerde))
                .process(SeatAllocationProcessor::new, StateStore.SEAT_INVENTORY);

        completedStream.to(Topic.SEAT_ALLOCATION_RESULTS, Produced.with(Serdes.String(), completedSerde));

        // Status update path: allocation results -> SectionStatusEmitter -> section-status
        completedStream
                .process(SectionStatusEmitter::new, StateStore.SEAT_INVENTORY)
                .mapValues((ValueMapper<SectionSeatState, SectionStatusEvent>) state ->
                        SectionStatusEvent.newBuilder()
                                .setEventId(state.getEventId())
                                .setSection(state.getSection())
                                .setAvailableCount(state.getAvailableCount())
                                .setTimestamp(System.currentTimeMillis())
                                .build())
                .to(Topic.SECTION_STATUS, Produced.with(Serdes.String(), statusEventSerde));

        Topology topology = builder.build();

        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, "test-app");
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.StringSerde.class);
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, SpecificAvroSerde.class);
        props.put("schema.registry.url", "mock://test");

        testDriver = new TopologyTestDriver(topology, props);

        sectionInitInput = testDriver.createInputTopic(
                Topic.SECTION_INIT,
                new StringSerializer(),
                sectionInitSerde.serializer()
        );

        seatAllocationRequestInput = testDriver.createInputTopic(
                Topic.SEAT_ALLOCATION_REQUESTS,
                new StringSerializer(),
                commandSerde.serializer()
        );

        sectionStatusOutput = testDriver.createOutputTopic(
                Topic.SECTION_STATUS,
                new StringDeserializer(),
                statusEventSerde.deserializer()
        );

        seatAllocationResultOutput = testDriver.createOutputTopic(
                Topic.SEAT_ALLOCATION_RESULTS,
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
        return testDriver.getKeyValueStore(StateStore.SEAT_INVENTORY);
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

    protected BookingCommand buildBookingCommand(String bookingId, long eventId, String section, int seatCount, String userId) {
        return BookingCommand.newBuilder()
                .setBookingId(bookingId)
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
