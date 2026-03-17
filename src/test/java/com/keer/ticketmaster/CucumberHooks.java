package com.keer.ticketmaster;

import com.keer.ticketmaster.repository.EventRepository;
import com.keer.ticketmaster.repository.TicketRepository;
import com.keer.ticketmaster.repository.VenueRepository;
import io.cucumber.java.Before;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 共用 Cucumber Hooks — 每個 scenario 前清理資料（依 FK 順序）
 */
public class CucumberHooks {

    @Autowired
    private TicketRepository ticketRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private VenueRepository venueRepository;

    @Autowired
    private ScenarioContext scenarioContext;

    @Before
    public void cleanUp() {
        // 按照 FK 依賴順序：先刪子表，再刪父表
        ticketRepository.deleteAll();
        eventRepository.deleteAll();
        venueRepository.deleteAll();
        scenarioContext.clear();
    }
}
