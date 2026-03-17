package com.keer.ticketmaster.ticket.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keer.ticketmaster.ScenarioContext;
import com.keer.ticketmaster.request.TicketRequest;
import io.cucumber.java.zh_tw.當;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

public class TicketWhenSteps {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ScenarioContext scenarioContext;

    @當("^我建立一張票券，座位號為「(.+)」，價格為 (\\d+)$")
    public void 我建立一張票券(String seatNumber, int price) throws Exception {
        Long eventId = (Long) scenarioContext.get("createdEventId");

        // Parse seatNumber like "A-1" into section="A", col=1
        String section = seatNumber.substring(0, seatNumber.indexOf('-'));
        int col = Integer.parseInt(seatNumber.substring(seatNumber.indexOf('-') + 1));
        TicketRequest request = new TicketRequest(eventId, section, 0, col, new BigDecimal(price));

        MvcResult result = mockMvc.perform(
                post("/api/tickets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andReturn();

        scenarioContext.setLastResponse(result);
    }

    @當("我查詢該活動的所有票券")
    public void 我查詢該活動的所有票券() throws Exception {
        Long eventId = (Long) scenarioContext.get("createdEventId");

        MvcResult result = mockMvc.perform(
                get("/api/tickets")
                        .param("eventId", eventId.toString())
                        .contentType(MediaType.APPLICATION_JSON))
                .andReturn();

        scenarioContext.setLastResponse(result);
    }

    @當("我查詢該活動的可用票券")
    public void 我查詢該活動的可用票券() throws Exception {
        Long eventId = (Long) scenarioContext.get("createdEventId");

        MvcResult result = mockMvc.perform(
                get("/api/tickets/available")
                        .param("eventId", eventId.toString())
                        .contentType(MediaType.APPLICATION_JSON))
                .andReturn();

        scenarioContext.setLastResponse(result);
    }
}
