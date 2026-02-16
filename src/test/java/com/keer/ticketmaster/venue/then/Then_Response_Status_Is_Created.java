package com.keer.ticketmaster.venue.then;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keer.ticketmaster.ScenarioContext;
import com.keer.ticketmaster.venue.dto.VenueResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Then: 系統應該回傳 201 Created
 * 驗證建立場館的成功回應
 */
public class Then_Response_Status_Is_Created {

    @Autowired
    private ScenarioContext scenarioContext;

    @Autowired
    private ObjectMapper objectMapper;

    public void execute() throws Exception {
        MvcResult result = scenarioContext.getLastResponse();
        assertNotNull(result, "應該有前一個HTTP回應");

        // 驗證HTTP狀態碼
        int status = result.getResponse().getStatus();
        assertEquals(201, status, "HTTP狀態碼應為 201 Created");

        // 驗證回應體包含 VenueResponse
        String responseBody = result.getResponse().getContentAsString();
        assertNotNull(responseBody, "回應體不應為空");

        VenueResponse response = objectMapper.readValue(responseBody, VenueResponse.class);
        assertNotNull(response, "回應應能解析為 VenueResponse");
        assertNotNull(response.getId(), "場館 ID 應不為空");

        // 儲存建立的場館 ID 供後續步驟使用
        scenarioContext.set("createdVenueId", response.getId());
    }
}
