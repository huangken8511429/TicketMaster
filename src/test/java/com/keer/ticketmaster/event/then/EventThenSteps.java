package com.keer.ticketmaster.event.then;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;
import com.keer.ticketmaster.ScenarioContext;
import com.keer.ticketmaster.response.EventResponse;
import io.cucumber.java.zh_tw.那麼;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;

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
        assertNotNull(response.getEventStartTime(), "活動開始時間應不為空");
        assertNotNull(response.getVenueId(), "場館 ID 應不為空");
    }

    @那麼("^活動資訊包含名稱「(.+)」、描述「(.+)」、開始時間「(.+)」、結束時間「(.+)」$")
    public void 活動資訊包含名稱描述開始結束時間(String expectedName, String expectedDescription, String expectedStartTime, String expectedEndTime) throws Exception {
        MvcResult result = scenarioContext.getLastResponse();
        assertNotNull(result, "應該有前一個HTTP回應");

        String responseBody = result.getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(responseBody);

        assertEquals(expectedName, json.get("name").asText(), "活動名稱不符");
        assertEquals(expectedDescription, json.get("description").asText(), "活動描述不符");
        assertTrue(json.get("eventStartTime").asText().startsWith(expectedStartTime), "開始時間不符");
        assertTrue(json.get("eventEndTime").asText().startsWith(expectedEndTime), "結束時間不符");
    }

    @那麼("^活動關聯的表演者名稱為「(.+)」$")
    public void 活動關聯的表演者名稱為(String expectedPerformerName) throws Exception {
        MvcResult result = scenarioContext.getLastResponse();
        assertNotNull(result, "應該有前一個HTTP回應");

        String responseBody = result.getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(responseBody);

        assertEquals(expectedPerformerName, json.get("performerName").asText(), "表演者名稱不符");
    }

    @那麼("^活動包含 (\\d+) 個區域$")
    public void 活動包含N個區域(int expectedCount) throws Exception {
        MvcResult result = scenarioContext.getLastResponse();
        assertNotNull(result, "應該有前一個HTTP回應");

        String responseBody = result.getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(responseBody);

        assertEquals(expectedCount, json.get("sectionCount").asInt(), "區域數量不符");
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
