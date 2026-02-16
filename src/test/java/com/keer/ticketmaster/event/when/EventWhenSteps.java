package com.keer.ticketmaster.event.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keer.ticketmaster.ScenarioContext;
import com.keer.ticketmaster.event.dto.EventRequest;
import com.keer.ticketmaster.event.repository.EventRepository;
import io.cucumber.java.zh_tw.當;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

public class EventWhenSteps {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ScenarioContext scenarioContext;

    @Autowired
    private EventRepository eventRepository;

    @當("^我建立一個活動，名稱為「(.+)」，描述為「(.+)」，日期為「(.+)」，關聯場館為該場館$")
    public void 我建立一個活動(String name, String description, String eventDate) throws Exception {
        Long venueId = (Long) scenarioContext.get("createdVenueId");

        EventRequest request = new EventRequest(name, description, LocalDate.parse(eventDate), venueId);

        MvcResult result = mockMvc.perform(
                post("/api/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andReturn();

        scenarioContext.setLastResponse(result);
    }

    @當("我按 ID 查詢活動")
    public void 我按ID查詢活動() throws Exception {
        Long eventId = scenarioContext.get("createdEventId") != null ?
                (Long) scenarioContext.get("createdEventId") :
                eventRepository.findAll().stream().findFirst().orElseThrow().getId();

        MvcResult result = mockMvc.perform(
                get("/api/events/{id}", eventId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andReturn();

        scenarioContext.setLastResponse(result);
    }

    @當("我查詢所有活動")
    public void 我查詢所有活動() throws Exception {
        MvcResult result = mockMvc.perform(
                get("/api/events")
                        .contentType(MediaType.APPLICATION_JSON))
                .andReturn();

        scenarioContext.setLastResponse(result);
    }
}
