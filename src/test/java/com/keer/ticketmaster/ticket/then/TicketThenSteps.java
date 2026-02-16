package com.keer.ticketmaster.ticket.then;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;
import com.keer.ticketmaster.ScenarioContext;
import com.keer.ticketmaster.ticket.dto.TicketResponse;
import io.cucumber.java.zh_tw.那麼;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class TicketThenSteps {

    @Autowired
    private ScenarioContext scenarioContext;

    @Autowired
    private ObjectMapper objectMapper;

    @那麼("^票券資訊包含座位號「(.+)」、價格 (\\d+)、狀態「(.+)」$")
    public void 票券資訊包含正確資訊(String expectedSeat, int expectedPrice, String expectedStatus) throws Exception {
        MvcResult result = scenarioContext.getLastResponse();
        assertNotNull(result, "應該有前一個HTTP回應");

        String responseBody = result.getResponse().getContentAsString();
        TicketResponse response = objectMapper.readValue(responseBody, TicketResponse.class);

        assertEquals(expectedSeat, response.getSeatNumber(),
                "座位號應為 " + expectedSeat);
        assertEquals(expectedPrice, response.getPrice().intValue(),
                "價格應為 " + expectedPrice);
        assertEquals(expectedStatus, response.getStatus(),
                "狀態應為 " + expectedStatus);
    }

    @那麼("^系統應該回傳 (\\d+) 張票券$")
    public void 系統應該回傳N張票券(int expectedCount) throws Exception {
        MvcResult result = scenarioContext.getLastResponse();
        assertNotNull(result, "應該有前一個HTTP回應");

        String responseBody = result.getResponse().getContentAsString();
        CollectionType listType = objectMapper.getTypeFactory()
                .constructCollectionType(List.class, TicketResponse.class);
        List<TicketResponse> tickets = objectMapper.readValue(responseBody, listType);

        assertEquals(expectedCount, tickets.size(),
                "應該回傳 " + expectedCount + " 張票券，實際為 " + tickets.size());
    }
}
