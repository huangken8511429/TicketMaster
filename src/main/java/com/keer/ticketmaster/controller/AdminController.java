package com.keer.ticketmaster.controller;

import com.keer.ticketmaster.avro.SectionInitCommand;
import com.keer.ticketmaster.config.StoreKeyUtil;
import com.keer.ticketmaster.config.Topic;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin")
@Profile({"api", "default"})
@RequiredArgsConstructor
public class AdminController {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    /**
     * Initialize a section's seat inventory in Kafka Streams state store.
     * Sends one init command PER sub-partition with key = eventId-section-sp,
     * ensuring co-partitioning with booking allocation requests.
     */
    @PostMapping("/sections/init")
    public ResponseEntity<Map<String, String>> initSection(
            @RequestParam long eventId,
            @RequestParam String section,
            @RequestParam int rows,
            @RequestParam int seatsPerRow,
            @RequestParam(defaultValue = "1") int subPartitions) {

        for (int sp = 0; sp < subPartitions; sp++) {
            String key = StoreKeyUtil.seatKey(eventId, section, sp);
            SectionInitCommand command = SectionInitCommand.newBuilder()
                    .setEventId(eventId)
                    .setSection(section)
                    .setRows(rows)
                    .setSeatsPerRow(seatsPerRow)
                    .setSubPartitions(subPartitions)
                    .setInitialReserved(List.of())
                    .build();

            kafkaTemplate.send(Topic.SECTION_INIT, key, command);
        }

        return ResponseEntity.ok(Map.of(
                "status", "sent",
                "subPartitions", String.valueOf(subPartitions)));
    }
}
