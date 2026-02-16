package com.keer.ticketmaster.venue.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keer.ticketmaster.ScenarioContext;
import com.keer.ticketmaster.venue.dto.VenueRequest;
import com.keer.ticketmaster.venue.repository.VenueRepository;
import io.cucumber.java.zh_tw.當;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

public class VenueWhenSteps {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ScenarioContext scenarioContext;

    @Autowired
    private VenueRepository venueRepository;

    @當("^我建立一個場館，名稱為「(.+)」，地址為「(.+)」，容量為 (\\d+)$")
    public void 我建立一個場館(String name, String address, int capacity) throws Exception {
        VenueRequest request = new VenueRequest(name, address, capacity);

        MvcResult result = mockMvc.perform(
                post("/api/venues")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andReturn();

        scenarioContext.setLastResponse(result);
    }

    @當("我按 ID 查詢場館")
    public void 我按ID查詢場館() throws Exception {
        // 獲取最後創建的場館 ID，或從場館表中獲取第一個
        Long venueId = scenarioContext.get("createdVenueId") != null ?
                (Long) scenarioContext.get("createdVenueId") :
                venueRepository.findAll().stream().findFirst().orElseThrow().getId();

        scenarioContext.set("queryVenueId", venueId);

        MvcResult result = mockMvc.perform(
                get("/api/venues/{id}", venueId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andReturn();

        scenarioContext.setLastResponse(result);
    }

    @當("我查詢所有場館")
    public void 我查詢所有場館() throws Exception {
        MvcResult result = mockMvc.perform(
                get("/api/venues")
                        .contentType(MediaType.APPLICATION_JSON))
                .andReturn();

        scenarioContext.setLastResponse(result);
    }
}
