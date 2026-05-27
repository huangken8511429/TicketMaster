package com.keer.ticketmaster.ticket.given;

import com.keer.ticketmaster.ScenarioContext;
import com.keer.ticketmaster.avro.SectionInitCommand;
import com.keer.ticketmaster.avro.TicketCommand;
import com.keer.ticketmaster.avro.TicketState;
import com.keer.ticketmaster.config.StateStore;
import com.keer.ticketmaster.config.StoreKeyUtil;
import com.keer.ticketmaster.config.Topic;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.zh_tw.假如;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StoreQueryParameters;
import org.apache.kafka.streams.state.KeyValueIterator;
import org.apache.kafka.streams.state.QueryableStoreTypes;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.config.StreamsBuilderFactoryBean;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class TicketGivenSteps {

    @Autowired
    private ScenarioContext scenarioContext;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private StreamsBuilderFactoryBean streamsBuilderFactoryBean;

    @假如("系統中沒有任何票券資料")
    public void 系統中沒有任何票券資料() {
        // Ticket 已遷移到 Kafka KTable —— scenarios 透過獨立 eventId 隔離。
        // EmbeddedKafka 在啟動時會清空 state store，無需顯式 deleteAll。
    }

    @假如("^該活動已存在以下票券:$")
    public void 該活動已存在以下票券(DataTable dataTable) throws Exception {
        Long eventId = (Long) scenarioContext.get("createdEventId");

        List<Map<String, String>> rows = dataTable.asMaps(String.class, String.class);

        Map<String, List<String>> sectionSeats = new LinkedHashMap<>();
        Map<String, List<String>> sectionReserved = new LinkedHashMap<>();

        long now = Instant.now().toEpochMilli();

        for (Map<String, String> row : rows) {
            String seatNumber = row.get("seatNumber");
            String section = seatNumber.substring(0, seatNumber.indexOf('-'));
            int col = Integer.parseInt(seatNumber.substring(seatNumber.indexOf('-') + 1));

            String statusRaw = row.get("status");
            String status = "RESERVED".equals(statusRaw) ? "BOOKED" : statusRaw;

            String ticketId = UUID.randomUUID().toString();
            TicketCommand cmd = TicketCommand.newBuilder()
                    .setTicketId(ticketId)
                    .setEventId(eventId)
                    .setSection(section)
                    .setSeatRow(0)
                    .setSeatCol(col)
                    .setPrice(row.get("price"))
                    .setStatus(status)
                    .setUserId(null)
                    .setTimestamp(now)
                    .build();

            kafkaTemplate.send(Topic.TICKET_COMMANDS, ticketId, cmd).get(5, TimeUnit.SECONDS);

            sectionSeats.computeIfAbsent(section, k -> new ArrayList<>()).add(seatNumber);

            if (!"AVAILABLE".equals(statusRaw)) {
                sectionReserved.computeIfAbsent(section, k -> new ArrayList<>()).add(seatNumber);
            }
        }

        // Publish 1 SectionInitCommand per section (for booking-related scenarios)
        for (Map.Entry<String, List<String>> entry : sectionSeats.entrySet()) {
            String section = entry.getKey();
            int totalSeats = entry.getValue().size();
            List<String> reserved = sectionReserved.getOrDefault(section, List.of());

            String key = StoreKeyUtil.seatKey(eventId, section, 0);
            SectionInitCommand command = SectionInitCommand.newBuilder()
                    .setEventId(eventId)
                    .setSection(section)
                    .setRows(1)
                    .setSeatsPerRow(totalSeats)
                    .setSubPartitions(1)
                    .setInitialReserved(reserved)
                    .build();

            kafkaTemplate.send(Topic.SECTION_INIT, key, command).get(5, TimeUnit.SECONDS);
        }

        // Wait for ticket-state KTable to reflect all produced records.
        int expected = rows.size();
        Long firstEventId = eventId;
        awaitTicketCount(firstEventId, expected, 10_000);
    }

    private void awaitTicketCount(Long eventId, int expected, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            KafkaStreams streams = streamsBuilderFactoryBean.getKafkaStreams();
            if (streams != null && streams.state() == KafkaStreams.State.RUNNING) {
                try {
                    ReadOnlyKeyValueStore<String, TicketState> store = streams.store(
                            StoreQueryParameters.fromNameAndType(
                                    StateStore.TICKET_STATE,
                                    QueryableStoreTypes.<String, TicketState>keyValueStore()
                            )
                    );
                    int count = 0;
                    try (KeyValueIterator<String, TicketState> it = store.all()) {
                        while (it.hasNext()) {
                            TicketState s = it.next().value;
                            if (s != null && eventId.equals(s.getEventId())) {
                                count++;
                            }
                        }
                    }
                    if (count >= expected) return;
                } catch (Exception ignored) {
                    // Store not ready yet — retry
                }
            }
            Thread.sleep(150);
        }
    }
}
