package com.keer.ticketmaster.event.then;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;
import com.keer.ticketmaster.ScenarioContext;
import com.keer.ticketmaster.event.dto.EventResponse;
import io.cucumber.java.zh_tw.那麼;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class EventThenSteps {

    @Autowired
    private ScenarioContext scenarioContext;

    @Autowired
    private ObjectMapper objectMapper;

    @那麼("^活動資訊包含名稱「(.+)」、描述「(.+)」、日期「(.+)」$")
    public void 活動資訊包含正確資訊(String expectedName, String expectedDescription, String expectedDate) throws Exception {
        MvcResult result = scenarioContext.getLastResponse();
        assertNotNull(result, "應該有前一個HTTP回應");

        String responseBody = result.getResponse().getContentAsString();
        EventResponse response = objectMapper.readValue(responseBody, EventResponse.class);

        assertEquals(expectedName, response.getName(),
                "活動名稱應為 " + expectedName);
        assertEquals(expectedDescription, response.getDescription(),
                "活動描述應為 " + expectedDescription);
        assertEquals(expectedDate, response.getEventDate().toString(),
                "活動日期應為 " + expectedDate);
        assertNotNull(response.getVenueId(), "場館 ID 應不為空");
    }

    @那麼("^系統應該回傳 (\\d+) 個活動$")
    public void 系統應該回傳N個活動(int expectedCount) throws Exception {
        MvcResult result = scenarioContext.getLastResponse();
        assertNotNull(result, "應該有前一個HTTP回應");

        String responseBody = result.getResponse().getContentAsString();
        CollectionType listType = objectMapper.getTypeFactory()
                .constructCollectionType(List.class, EventResponse.class);
        List<EventResponse> events = objectMapper.readValue(responseBody, listType);

        assertNotNull(events, "回應應能解析為 EventResponse 列表");
        assertEquals(expectedCount, events.size(),
                "應該回傳 " + expectedCount + " 個活動，實際為 " + events.size());
    }
}
