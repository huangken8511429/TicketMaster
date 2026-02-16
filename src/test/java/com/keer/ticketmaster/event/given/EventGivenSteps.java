package com.keer.ticketmaster.event.given;

import com.keer.ticketmaster.ScenarioContext;
import com.keer.ticketmaster.event.model.Event;
import com.keer.ticketmaster.event.repository.EventRepository;
import com.keer.ticketmaster.venue.model.Venue;
import com.keer.ticketmaster.venue.repository.VenueRepository;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.zh_tw.假如;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Event module Given step definitions
 * 場館相關的 Given 步驟由 VenueGivenSteps 處理（Cucumber glue 自動掃描）
 */
public class EventGivenSteps {

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private VenueRepository venueRepository;

    @Autowired
    private ScenarioContext scenarioContext;

    @假如("系統中沒有任何活動資料")
    public void 系統中沒有任何活動資料() {
        eventRepository.deleteAll();
    }

    @假如("^系統中已存在一個活動，名稱為「(.+)」，描述為「(.+)」，日期為「(.+)」，關聯場館為該場館$")
    public void 系統中已存在一個活動(String name, String description, String eventDate) {
        // 從 ScenarioContext 取得前一步驟建立的場館 ID
        Long venueId = (Long) scenarioContext.get("createdVenueId");
        Venue venue = venueRepository.findById(venueId).orElseThrow();

        Event event = new Event();
        event.setName(name);
        event.setDescription(description);
        event.setEventDate(LocalDate.parse(eventDate));
        event.setVenue(venue);
        Event saved = eventRepository.save(event);

        scenarioContext.set("createdEventId", saved.getId());
    }

    @假如("系統中已存在以下活動:")
    public void 系統中已存在以下活動(DataTable dataTable) {
        Long venueId = (Long) scenarioContext.get("createdVenueId");
        Venue venue = venueRepository.findById(venueId).orElseThrow();

        List<Map<String, String>> rows = dataTable.asMaps(String.class, String.class);
        for (Map<String, String> row : rows) {
            Event event = new Event();
            event.setName(row.get("name"));
            event.setDescription(row.get("description"));
            event.setEventDate(LocalDate.parse(row.get("eventDate")));
            event.setVenue(venue);
            eventRepository.save(event);
        }
    }
}
