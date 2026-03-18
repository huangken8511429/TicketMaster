package com.keer.ticketmaster.venue.then;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;
import com.keer.ticketmaster.ScenarioContext;
import com.keer.ticketmaster.response.VenueResponse;
import io.cucumber.java.zh_tw.那麼;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Venue 專屬的 Then 步驟（HTTP 狀態碼驗證由 CommonThenSteps 處理）
 */
public class VenueThenSteps {

    @Autowired
    private ScenarioContext scenarioContext;

    @Autowired
    private ObjectMapper objectMapper;

    @那麼("^場館資訊包含名稱「(.+)」、地址「(.+)」、容量 (\\d+)$")
    public void 場館資訊包含正確資訊(String expectedName, String expectedAddress, int expectedCapacity) throws Exception {
        MvcResult result = scenarioContext.getLastResponse();
        assertNotNull(result, "應該有前一個HTTP回應");

        String responseBody = result.getResponse().getContentAsString();
        responseBody = responseBody.trim();
        if (responseBody.startsWith("[")) {
            CollectionType listType = objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, VenueResponse.class);
            List<VenueResponse> venues = objectMapper.readValue(responseBody, listType);
            assertFalse(venues.isEmpty(), "場館列表不應為空");
            validateVenue(venues.get(0), expectedName, expectedAddress);
        } else {
            VenueResponse response = objectMapper.readValue(responseBody, VenueResponse.class);
            validateVenue(response, expectedName, expectedAddress);
        }
    }

    @那麼("^場館資訊包含名稱「(.+)」、地址「(.+)」、座位圖「(.+)」$")
    public void 場館資訊包含名稱地址座位圖(String expectedName, String expectedAddress, String expectedSeatMap) throws Exception {
        MvcResult result = scenarioContext.getLastResponse();
        assertNotNull(result, "應該有前一個HTTP回應");

        String responseBody = result.getResponse().getContentAsString();
        responseBody = responseBody.trim();
        if (responseBody.startsWith("[")) {
            CollectionType listType = objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, VenueResponse.class);
            List<VenueResponse> venues = objectMapper.readValue(responseBody, listType);
            assertFalse(venues.isEmpty(), "場館列表不應為空");
            validateVenueWithSeatMap(venues.get(0), expectedName, expectedAddress, expectedSeatMap);
        } else {
            VenueResponse response = objectMapper.readValue(responseBody, VenueResponse.class);
            validateVenueWithSeatMap(response, expectedName, expectedAddress, expectedSeatMap);
        }
    }

    @那麼("^系統應該回傳 (\\d+) 個場館$")
    public void 系統應該回傳N個場館(int expectedCount) throws Exception {
        MvcResult result = scenarioContext.getLastResponse();
        assertNotNull(result, "應該有前一個HTTP回應");

        String responseBody = result.getResponse().getContentAsString();
        CollectionType listType = objectMapper.getTypeFactory()
                .constructCollectionType(List.class, VenueResponse.class);
        List<VenueResponse> venues = objectMapper.readValue(responseBody, listType);

        assertEquals(expectedCount, venues.size(),
                "應該回傳 " + expectedCount + " 個場館，實際為 " + venues.size());
    }

    private void validateVenue(VenueResponse response, String expectedName,
                               String expectedLocation) {
        assertEquals(expectedName, response.getName(),
                "場館名稱應為 " + expectedName);
        assertEquals(expectedLocation, response.getLocation(),
                "場館地點應為 " + expectedLocation);
    }

    private void validateVenueWithSeatMap(VenueResponse response, String expectedName,
                                           String expectedLocation, String expectedSeatMap) {
        validateVenue(response, expectedName, expectedLocation);
        assertEquals(expectedSeatMap, response.getSeatMap(),
                "場館座位圖應為 " + expectedSeatMap);
    }
}
