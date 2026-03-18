package com.keer.ticketmaster.event.given;

import com.keer.ticketmaster.ScenarioContext;
import com.keer.ticketmaster.po.Event;
import com.keer.ticketmaster.po.Performer;
import com.keer.ticketmaster.po.Section;
import com.keer.ticketmaster.po.Venue;
import com.keer.ticketmaster.repository.EventRepository;
import com.keer.ticketmaster.repository.PerformerRepository;
import com.keer.ticketmaster.repository.VenueRepository;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.zh_tw.假如;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
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
    private PerformerRepository performerRepository;

    @Autowired
    private ScenarioContext scenarioContext;

    @假如("^系統中已存在一個表演者，名稱為「(.+)」，描述為「(.+)」$")
    public void 系統中已存在一個表演者(String name, String description) {
        Performer performer = new Performer();
        performer.setName(name);
        performer.setDescription(description);
        Performer saved = performerRepository.save(performer);
        scenarioContext.set("createdPerformerId", saved.getId());
    }

    @假如("系統中沒有任何活動資料")
    public void 系統中沒有任何活動資料() {
        eventRepository.deleteAll();
    }

    @假如("^系統中已存在一個活動，名稱為「(.+)」，描述為「(.+)」，日期為「(.+)」，關聯場館為該場館$")
    public void 系統中已存在一個活動_舊格式(String name, String description, String eventDate) {
        Long venueId = (Long) scenarioContext.get("createdVenueId");
        Venue venue = venueRepository.findById(venueId).orElseThrow();

        Event event = new Event();
        event.setName(name);
        event.setDescription(description);
        event.setEventStartTime(LocalDateTime.parse(eventDate + "T00:00:00"));
        event.setVenue(venue);
        Event saved = eventRepository.save(event);

        scenarioContext.set("createdEventId", saved.getId());
    }

    @假如("^系統中已存在一個活動，名稱為「(.+)」，描述為「(.+)」，開始時間為「(.+)」，關聯場館為該場館$")
    public void 系統中已存在一個活動(String name, String description, String eventStartTime) {
        Long venueId = (Long) scenarioContext.get("createdVenueId");
        Venue venue = venueRepository.findById(venueId).orElseThrow();

        Event event = new Event();
        event.setName(name);
        event.setDescription(description);
        event.setEventStartTime(LocalDateTime.parse(eventStartTime));
        event.setVenue(venue);
        Event saved = eventRepository.save(event);

        scenarioContext.set("createdEventId", saved.getId());
    }

    @假如("^系統中已存在一個活動，名稱為「(.+)」，描述為「(.+)」，開始時間為「(.+)」，結束時間為「(.+)」，關聯表演者為該表演者，關聯場館為該場館，包含以下區域:$")
    public void 系統中已存在一個活動含表演者場館區域(String name, String description, String startTime, String endTime, DataTable dataTable) {
        Long venueId = (Long) scenarioContext.get("createdVenueId");
        Venue venue = venueRepository.findById(venueId).orElseThrow();
        Long performerId = (Long) scenarioContext.get("createdPerformerId");
        Performer performer = performerRepository.findById(performerId).orElseThrow();

        Event event = new Event();
        event.setName(name);
        event.setDescription(description);
        event.setEventStartTime(LocalDateTime.parse(startTime));
        event.setEventEndTime(LocalDateTime.parse(endTime));
        event.setVenue(venue);
        event.setPerformer(performer);

        List<Section> sections = dataTable.asMaps(String.class, String.class).stream()
                .map(row -> {
                    Section section = new Section();
                    section.setName(row.get("name"));
                    section.setRows(Integer.parseInt(row.get("rows")));
                    section.setCols(Integer.parseInt(row.get("cols")));
                    section.setAvailableSeats(Integer.parseInt(row.get("availableSeats")));
                    return section;
                }).toList();
        event.setSections(new java.util.ArrayList<>(sections));

        Event saved = eventRepository.save(event);
        scenarioContext.set("createdEventId", saved.getId());
    }

    @假如("系統中已存在以下活動:")
    public void 系統中已存在以下活動(DataTable dataTable) {
        Long venueId = (Long) scenarioContext.get("createdVenueId");
        Venue venue = venueRepository.findById(venueId).orElseThrow();

        Performer performer = null;
        Long performerId = (Long) scenarioContext.get("createdPerformerId");
        if (performerId != null) {
            performer = performerRepository.findById(performerId).orElse(null);
        }

        List<Map<String, String>> rows = dataTable.asMaps(String.class, String.class);
        for (Map<String, String> row : rows) {
            Event event = new Event();
            event.setName(row.get("name"));
            event.setDescription(row.get("description"));
            String st = row.get("eventStartTime") != null ? row.get("eventStartTime") : row.get("eventDate") + "T00:00:00";
            event.setEventStartTime(LocalDateTime.parse(st));
            if (row.get("eventEndTime") != null) {
                event.setEventEndTime(LocalDateTime.parse(row.get("eventEndTime")));
            }
            event.setVenue(venue);
            event.setPerformer(performer);
            eventRepository.save(event);
        }
    }
}
