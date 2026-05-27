package com.keer.ticketmaster.streaming.topology;

import com.keer.ticketmaster.avro.TicketCommand;
import com.keer.ticketmaster.avro.TicketState;
import com.keer.ticketmaster.config.StateStore;
import com.keer.ticketmaster.config.Topic;
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.KTable;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.state.KeyValueStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.Map;

/**
 * Ticket metadata KTable topology.
 *
 * Replaces the JPA Ticket entity. Runs in the api role so that the
 * REST controllers can do Interactive Query against the local store.
 *
 * Flow:
 *   ticket-commands  (key=ticketId, value=TicketCommand)
 *     → fold into TicketState
 *     → materialize as KTable (state.ticket store)
 *     → emit to ticket-state compacted topic
 */
@Configuration
@Profile({"api", "default"})
public class TicketStateTopology {

    @Value("${spring.kafka.streams.properties[schema.registry.url]}")
    private String schemaRegistryUrl;

    @Autowired
    public void ticketStatePipeline(StreamsBuilder builder) {
        Map<String, String> serdeConfig = Map.of("schema.registry.url", schemaRegistryUrl);

        SpecificAvroSerde<TicketCommand> commandSerde = new SpecificAvroSerde<>();
        commandSerde.configure(serdeConfig, false);
        SpecificAvroSerde<TicketState> stateSerde = new SpecificAvroSerde<>();
        stateSerde.configure(serdeConfig, false);

        KTable<String, TicketState> ticketTable = builder
                .stream(Topic.TICKET_COMMANDS, Consumed.with(Serdes.String(), commandSerde))
                .mapValues(TicketStateTopology::toState)
                .toTable(
                        Materialized.<String, TicketState, KeyValueStore<Bytes, byte[]>>as(StateStore.TICKET_STATE)
                                .withKeySerde(Serdes.String())
                                .withValueSerde(stateSerde)
                );

        ticketTable.toStream()
                .to(Topic.TICKET_STATE, Produced.with(Serdes.String(), stateSerde));
    }

    private static TicketState toState(TicketCommand cmd) {
        return TicketState.newBuilder()
                .setTicketId(cmd.getTicketId())
                .setEventId(cmd.getEventId())
                .setSection(cmd.getSection())
                .setSeatRow(cmd.getSeatRow())
                .setSeatCol(cmd.getSeatCol())
                .setPrice(cmd.getPrice())
                .setStatus(cmd.getStatus())
                .setUserId(cmd.getUserId())
                .setUpdatedAt(cmd.getTimestamp())
                .build();
    }
}
