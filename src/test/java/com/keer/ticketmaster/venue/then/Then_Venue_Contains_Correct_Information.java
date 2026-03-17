package com.keer.ticketmaster.venue.then;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keer.ticketmaster.ScenarioContext;
import com.keer.ticketmaster.venue.dto.VenueResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Then: 場館資訊包含名稱「{name}」、地址「{address}」、容量 {capacity}
 * 驗證回傳的場館資訊是否正確
 */
public class Then_Venue_Contains_Correct_Information {

    @Autowired
    private ScenarioContext scenarioContext;

    @Autowired
    private ObjectMapper objectMapper;

    public void execute(String expectedName, String expectedLocation) throws Exception {
        MvcResult result = scenarioContext.getLastResponse();
        assertNotNull(result, "應該有前一個HTTP回應");

        String responseBody = result.getResponse().getContentAsString();
        VenueResponse response = objectMapper.readValue(responseBody, VenueResponse.class);

        assertEquals(expectedName, response.getName(),
                "場館名稱應為 " + expectedName);

        assertEquals(expectedLocation, response.getLocation(),
                "場館地點應為 " + expectedLocation);
    }
}
