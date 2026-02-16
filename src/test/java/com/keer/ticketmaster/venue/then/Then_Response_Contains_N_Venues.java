package com.keer.ticketmaster.venue.then;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.keer.ticketmaster.ScenarioContext;
import com.keer.ticketmaster.venue.dto.VenueResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Then: 系統應該回傳 {count} 個場館
 * 驗證查詢所有場館的回傳數量
 */
public class Then_Response_Contains_N_Venues {

    @Autowired
    private ScenarioContext scenarioContext;

    @Autowired
    private ObjectMapper objectMapper;

    public void execute(int expectedCount) throws Exception {
        MvcResult result = scenarioContext.getLastResponse();
        assertNotNull(result, "應該有前一個HTTP回應");

        String responseBody = result.getResponse().getContentAsString();
        CollectionType listType = objectMapper.getTypeFactory()
                .constructCollectionType(List.class, VenueResponse.class);
        List<VenueResponse> venues = objectMapper.readValue(responseBody, listType);

        assertNotNull(venues, "回應應能解析為 VenueResponse 列表");
        assertEquals(expectedCount, venues.size(),
                "應該回傳 " + expectedCount + " 個場館，實際為 " + venues.size());
    }
}
