package com.keer.ticketmaster;

import com.keer.ticketmaster.repository.EventRepository;
import com.keer.ticketmaster.repository.PerformerRepository;
import com.keer.ticketmaster.repository.VenueRepository;
import io.cucumber.java.Before;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 共用 Cucumber Hooks — 每個 scenario 前清理資料（依 FK 順序）
 *
 * Ticket 已遷移到 Kafka KTable，不在此處清理；scenarios 用獨立 eventId
 * 達到資料隔離，state store 由 EmbeddedKafka 的 cleanup.on-startup 控制。
 */
public class CucumberHooks {

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private VenueRepository venueRepository;

    @Autowired
    private PerformerRepository performerRepository;

    @Autowired
    private ScenarioContext scenarioContext;

    @Before
    public void cleanUp() {
        eventRepository.deleteAll();
        venueRepository.deleteAll();
        performerRepository.deleteAll();
        scenarioContext.clear();
    }
}
