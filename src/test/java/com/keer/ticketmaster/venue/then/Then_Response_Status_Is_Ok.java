package com.keer.ticketmaster.venue.then;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keer.ticketmaster.ScenarioContext;
import com.keer.ticketmaster.venue.dto.VenueResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Then: 系統應該回傳 200 OK
 * 驗證查詢場館的成功回應
 */
public class Then_Response_Status_Is_Ok {

    @Autowired
    private ScenarioContext scenarioContext;

    @Autowired
    private ObjectMapper objectMapper;

    public void execute() throws Exception {
        MvcResult result = scenarioContext.getLastResponse();
        assertNotNull(result, "應該有前一個HTTP回應");

        // 驗證HTTP狀態碼
        int status = result.getResponse().getStatus();
        assertEquals(200, status, "HTTP狀態碼應為 200 OK");

        // 驗證回應體包含 VenueResponse
        String responseBody = result.getResponse().getContentAsString();
        assertNotNull(responseBody, "回應體不應為空");

        VenueResponse response = objectMapper.readValue(responseBody, VenueResponse.class);
        assertNotNull(response, "回應應能解析為 VenueResponse");
        assertNotNull(response.getId(), "場館 ID 應不為空");
    }
}
