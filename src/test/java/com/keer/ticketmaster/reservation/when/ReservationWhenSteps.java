package com.keer.ticketmaster.reservation.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keer.ticketmaster.ScenarioContext;
import com.keer.ticketmaster.reservation.dto.ReservationRequest;
import io.cucumber.java.zh_tw.當;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

public class ReservationWhenSteps {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ScenarioContext scenarioContext;

    @當("^使用者「(.+)」預定活動的「(.+)」區 (\\d+) 個連續座位$")
    public void 使用者預定連續座位(String userId, String section, int seatCount) throws Exception {
        Long eventId = (Long) scenarioContext.get("createdEventId");

        ReservationRequest request = new ReservationRequest(eventId, section, seatCount, userId);

        MvcResult result = mockMvc.perform(
                post("/api/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andReturn();

        scenarioContext.setLastResponse(result);

        // Extract reservationId from response for polling
        String responseBody = result.getResponse().getContentAsString();
        Map<String, String> responseMap = objectMapper.readValue(responseBody,
                objectMapper.getTypeFactory().constructMapType(Map.class, String.class, String.class));
        scenarioContext.set("reservationId", responseMap.get("reservationId"));
    }
}
