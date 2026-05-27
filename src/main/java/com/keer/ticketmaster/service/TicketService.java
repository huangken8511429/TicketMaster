package com.keer.ticketmaster.service;

import com.keer.ticketmaster.avro.TicketCommand;
import com.keer.ticketmaster.avro.TicketState;
import com.keer.ticketmaster.config.StateStore;
import com.keer.ticketmaster.config.Topic;
import com.keer.ticketmaster.request.TicketRequest;
import com.keer.ticketmaster.response.TicketResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StoreQueryParameters;
import org.apache.kafka.streams.state.KeyValueIterator;
import org.apache.kafka.streams.state.QueryableStoreTypes;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.config.StreamsBuilderFactoryBean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Ticket metadata service backed by Kafka Streams KTable instead of JPA.
 *
 * Writes are produced to {@link Topic#TICKET_COMMANDS}; reads go through
 * the local {@link StateStore#TICKET_STATE} keyed-value store materialized
 * by {@code TicketStateTopology}.
 *
 * NOTE: this is the admin path — low QPS, occasional. The booking hot path
 * does not touch this service.
 */
@Service
@Profile({"api", "default"})
@RequiredArgsConstructor
@Slf4j
public class TicketService {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final StreamsBuilderFactoryBean streamsBuilderFactoryBean;

    public TicketResponse createTicket(TicketRequest request) {
        if (request.getEventId() == null) {
            return null;
        }

        String ticketId = UUID.randomUUID().toString();
        long now = Instant.now().toEpochMilli();

        TicketCommand cmd = TicketCommand.newBuilder()
                .setTicketId(ticketId)
                .setEventId(request.getEventId())
                .setSection(request.getSection() == null ? "" : request.getSection())
                .setSeatRow(request.getRow())
                .setSeatCol(request.getCol())
                .setPrice(request.getPrice() == null ? "0" : request.getPrice().toPlainString())
                .setStatus("AVAILABLE")
                .setUserId(null)
                .setTimestamp(now)
                .build();

        kafkaTemplate.send(Topic.TICKET_COMMANDS, ticketId, cmd);

        return TicketResponse.builder()
                .id(ticketId)
                .eventId(request.getEventId())
                .eventName(null)
                .section(cmd.getSection())
                .seatRow(cmd.getSeatRow())
                .seatCol(cmd.getSeatCol())
                .status(cmd.getStatus())
                .price(new BigDecimal(cmd.getPrice()))
                .userId(null)
                .build();
    }

    public TicketResponse getTicket(String id) {
        ReadOnlyKeyValueStore<String, TicketState> store = openStore();
        if (store == null) {
            return null;
        }
        TicketState state = store.get(id);
        return state == null ? null : toResponse(state);
    }

    public List<TicketResponse> getTicketsByEvent(Long eventId) {
        return scanByEvent(eventId, null);
    }

    @Cacheable(value = "tickets:available", key = "#eventId")
    public List<TicketResponse> getAvailableTicketsByEvent(Long eventId) {
        return scanByEvent(eventId, "AVAILABLE");
    }

    @CacheEvict(value = "tickets:available", key = "#eventId")
    public void evictAvailableTicketsCache(Long eventId) {
        // Spring handles eviction
    }

    private List<TicketResponse> scanByEvent(Long eventId, String statusFilter) {
        ReadOnlyKeyValueStore<String, TicketState> store = openStore();
        if (store == null) {
            return List.of();
        }
        List<TicketResponse> out = new ArrayList<>();
        try (KeyValueIterator<String, TicketState> it = store.all()) {
            while (it.hasNext()) {
                TicketState state = it.next().value;
                if (state == null) continue;
                if (!eventId.equals(state.getEventId())) continue;
                if (statusFilter != null && !statusFilter.equals(state.getStatus())) continue;
                out.add(toResponse(state));
            }
        }
        return out;
    }

    private ReadOnlyKeyValueStore<String, TicketState> openStore() {
        KafkaStreams streams = streamsBuilderFactoryBean.getKafkaStreams();
        if (streams == null || streams.state() != KafkaStreams.State.RUNNING) {
            return null;
        }
        try {
            return streams.store(StoreQueryParameters.fromNameAndType(
                    StateStore.TICKET_STATE,
                    QueryableStoreTypes.keyValueStore()
            ));
        } catch (Exception e) {
            log.debug("Ticket store not ready: {}", e.getMessage());
            return null;
        }
    }

    private TicketResponse toResponse(TicketState s) {
        return TicketResponse.builder()
                .id(s.getTicketId())
                .eventId(s.getEventId())
                .eventName(null)
                .section(s.getSection())
                .seatRow(s.getSeatRow())
                .seatCol(s.getSeatCol())
                .status(s.getStatus())
                .price(new BigDecimal(s.getPrice()))
                .userId(s.getUserId())
                .build();
    }
}
