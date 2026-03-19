package com.keer.ticketmaster.streaming.processor;

import com.keer.ticketmaster.avro.*;
import com.keer.ticketmaster.config.StateStore;
import com.keer.ticketmaster.config.Topic;
import com.keer.ticketmaster.streaming.SectionStatusMapper;
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

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Test base that mirrors the SeatProcessorTopology with bitmap-based state
 * and sub-partition support.
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

        // Init path: section-init → SectionInitProcessor → section-status
        builder.stream(Topic.SECTION_INIT, Consumed.with(Serdes.String(), sectionInitSerde))
                .process(SectionInitProcessor::new, StateStore.SEAT_INVENTORY)
                .mapValues(SectionStatusMapper::toStatusEvent)
                .to(Topic.SECTION_STATUS, Produced.with(Serdes.String(), statusEventSerde));

        // Allocation path: seat-allocation-requests → SeatAllocationProcessor → seat-allocation-results
        var completedStream = builder.stream(Topic.SEAT_ALLOCATION_REQUESTS, Consumed.with(Serdes.String(), commandSerde))
                .process(SeatAllocationProcessor::new, StateStore.SEAT_INVENTORY);

        completedStream.to(Topic.SEAT_ALLOCATION_RESULTS, Produced.with(Serdes.String(), completedSerde));

        // Status update path: allocation results → SectionStatusEmitter → section-status
        completedStream
                .process(SectionStatusEmitter::new, StateStore.SEAT_INVENTORY)
                .mapValues(SectionStatusMapper::toStatusEvent)
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
        initSection(eventId, section, rows, seatsPerRow, List.of());
    }

    protected void initSection(long eventId, String section, int rows, int seatsPerRow, List<String> initialReserved) {
        // Key must match allocation key format: eventId-section-subPartition
        String key = eventId + "-" + section + "-0";
        SectionInitCommand command = SectionInitCommand.newBuilder()
                .setEventId(eventId)
                .setSection(section)
                .setRows(rows)
                .setSeatsPerRow(seatsPerRow)
                .setSubPartitions(1)
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
                .setTargetSubPartition(0)
                .setTimestamp(System.currentTimeMillis())
                .build();
    }

    /**
     * Check if a seat is available in the bitmap-based state.
     */
    protected boolean isSeatAvailable(SectionSeatState state, int seatIndex) {
        ByteBuffer buffer = state.getSeatBitmap();
        byte[] bitmap = new byte[buffer.remaining()];
        buffer.get(bitmap);
        buffer.rewind();
        int localIndex = seatIndex - state.getSeatOffset();
        if (localIndex < 0 || localIndex >= state.getTotalSeats()) return false;
        return (bitmap[localIndex / 8] & (1 << (localIndex % 8))) != 0;
    }

    /**
     * Get list of available seat names from bitmap state.
     */
    protected List<String> getAvailableSeats(SectionSeatState state) {
        ByteBuffer buffer = state.getSeatBitmap();
        byte[] bitmap = new byte[buffer.remaining()];
        buffer.get(bitmap);
        buffer.rewind();
        List<String> seats = new ArrayList<>();
        for (int i = 0; i < state.getTotalSeats(); i++) {
            if ((bitmap[i / 8] & (1 << (i % 8))) != 0) {
                seats.add(state.getSection() + "-" + (state.getSeatOffset() + i));
            }
        }
        return seats;
    }

    private <T extends org.apache.avro.specific.SpecificRecord> SpecificAvroSerde<T> newAvroSerde(
            Map<String, String> serdeConfig) {
        SpecificAvroSerde<T> serde = new SpecificAvroSerde<>(schemaRegistryClient);
        serde.configure(serdeConfig, false);
        return serde;
    }
}
