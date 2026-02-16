package com.keer.ticketmaster.ticket.given;

import com.keer.ticketmaster.ScenarioContext;
import com.keer.ticketmaster.avro.SeatEvent;
import com.keer.ticketmaster.avro.SeatStateStatus;
import com.keer.ticketmaster.config.KafkaStreamsConfig;
import com.keer.ticketmaster.event.model.Event;
import com.keer.ticketmaster.event.repository.EventRepository;
import com.keer.ticketmaster.ticket.model.Ticket;
import com.keer.ticketmaster.ticket.repository.TicketRepository;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.zh_tw.假如;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class TicketGivenSteps {

    @Autowired
    private TicketRepository ticketRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private ScenarioContext scenarioContext;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @假如("系統中沒有任何票券資料")
    public void 系統中沒有任何票券資料() {
        ticketRepository.deleteAll();
    }

    @假如("^該活動已存在以下票券:$")
    public void 該活動已存在以下票券(DataTable dataTable) throws Exception {
        Long eventId = (Long) scenarioContext.get("createdEventId");
        Event event = eventRepository.findById(eventId).orElseThrow();

        List<Map<String, String>> rows = dataTable.asMaps(String.class, String.class);
        for (Map<String, String> row : rows) {
            Ticket ticket = new Ticket();
            ticket.setEvent(event);
            ticket.setSeatNumber(row.get("seatNumber"));
            ticket.setPrice(new BigDecimal(row.get("price")));
            ticket.setStatus(Ticket.TicketStatus.valueOf(row.get("status")));
            ticketRepository.save(ticket);

            // Publish Avro SeatEvent to Kafka for Streams topology materialization
            String seatNumber = row.get("seatNumber");
            String section = seatNumber.substring(0, seatNumber.indexOf('-'));
            SeatEvent seatEvent = SeatEvent.newBuilder()
                    .setEventId(eventId)
                    .setSeatNumber(seatNumber)
                    .setSection(section)
                    .setStatus(SeatStateStatus.valueOf(row.get("status")))
                    .setTimestamp(Instant.now().toEpochMilli())
                    .build();
            String eventKey = eventId.toString();
            kafkaTemplate.send(KafkaStreamsConfig.TOPIC_SEAT_EVENTS, eventKey, seatEvent).get(5, TimeUnit.SECONDS);
        }

        // Wait for Kafka Streams to process seat events
        Thread.sleep(2000);
    }
}
