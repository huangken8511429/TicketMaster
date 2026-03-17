package com.keer.ticketmaster.ticket.given;

import com.keer.ticketmaster.ScenarioContext;
import com.keer.ticketmaster.avro.SectionInitCommand;
import com.keer.ticketmaster.config.Topic;
import com.keer.ticketmaster.po.Event;
import com.keer.ticketmaster.repository.EventRepository;
import com.keer.ticketmaster.po.Seat;
import com.keer.ticketmaster.po.Ticket;
import com.keer.ticketmaster.repository.TicketRepository;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.zh_tw.假如;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.util.*;
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

        // Save tickets to DB
        // Group seats by section for SectionInitCommand
        Map<String, List<String>> sectionSeats = new LinkedHashMap<>();
        Map<String, List<String>> sectionReserved = new LinkedHashMap<>();

        for (Map<String, String> row : rows) {
            String seatNumber = row.get("seatNumber");
            String section = seatNumber.substring(0, seatNumber.indexOf('-'));
            int col = Integer.parseInt(seatNumber.substring(seatNumber.indexOf('-') + 1));

            Ticket ticket = new Ticket();
            ticket.setEvent(event);
            ticket.setSeat(new Seat(section, 0, col));
            ticket.setPrice(new BigDecimal(row.get("price")));
            String status = row.get("status");
            if ("RESERVED".equals(status)) {
                ticket.setStatus(Ticket.TicketStatus.BOOKED);
            } else {
                ticket.setStatus(Ticket.TicketStatus.valueOf(status));
            }
            ticketRepository.save(ticket);

            sectionSeats.computeIfAbsent(section, k -> new ArrayList<>()).add(seatNumber);

            if (!"AVAILABLE".equals(row.get("status"))) {
                sectionReserved.computeIfAbsent(section, k -> new ArrayList<>()).add(seatNumber);
            }
        }

        // Publish 1 SectionInitCommand per section
        for (Map.Entry<String, List<String>> entry : sectionSeats.entrySet()) {
            String section = entry.getKey();
            int totalSeats = entry.getValue().size();
            List<String> reserved = sectionReserved.getOrDefault(section, List.of());

            String key = eventId + "-" + section;
            SectionInitCommand command = SectionInitCommand.newBuilder()
                    .setEventId(eventId)
                    .setSection(section)
                    .setRows(1)
                    .setSeatsPerRow(totalSeats)
                    .setInitialReserved(reserved)
                    .build();

            kafkaTemplate.send(Topic.SECTION_INIT, key, command).get(5, TimeUnit.SECONDS);
        }

        // Wait for Kafka Streams to process section init
        Thread.sleep(2000);
    }
}
