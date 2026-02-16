package com.keer.ticketmaster.venue.when;

import com.keer.ticketmaster.ScenarioContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * When: 我按 ID 查詢場館
 * 通過 GET /api/venues/{id} 查詢單一場館
 */
public class When_I_Query_Venue_By_Id {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ScenarioContext scenarioContext;

    public void execute(Long venueId) throws Exception {
        MvcResult result = mockMvc.perform(
                get("/api/venues/{id}", venueId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andReturn();

        scenarioContext.setLastResponse(result);
    }
}
