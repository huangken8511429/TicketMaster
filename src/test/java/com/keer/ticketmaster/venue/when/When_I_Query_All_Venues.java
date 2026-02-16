package com.keer.ticketmaster.venue.when;

import com.keer.ticketmaster.ScenarioContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * When: 我查詢所有場館
 * 通過 GET /api/venues 查詢所有場館列表
 */
public class When_I_Query_All_Venues {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ScenarioContext scenarioContext;

    public void execute() throws Exception {
        MvcResult result = mockMvc.perform(
                get("/api/venues")
                        .contentType(MediaType.APPLICATION_JSON))
                .andReturn();

        scenarioContext.setLastResponse(result);
    }
}
