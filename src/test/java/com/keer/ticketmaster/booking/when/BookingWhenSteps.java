package com.keer.ticketmaster.booking.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keer.ticketmaster.ScenarioContext;
import com.keer.ticketmaster.request.BookingRequest;
import com.keer.ticketmaster.response.BookingResponse;
import io.cucumber.java.zh_tw.當;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

public class BookingWhenSteps {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ScenarioContext scenarioContext;

    @當("^使用者「(.+)」預訂活動的「(.+)」區 (\\d+) 個連續座位$")
    public void 使用者預訂連續座位(String userId, String section, int seatCount) throws Exception {
        Long eventId = (Long) scenarioContext.get("createdEventId");

        BookingRequest request = new BookingRequest(eventId, section, seatCount, userId);

        // POST now returns DeferredResult — use asyncDispatch for MockMvc
        MvcResult asyncResult = mockMvc.perform(
                post("/api/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andReturn();

        MvcResult result;
        if (asyncResult.getRequest().isAsyncStarted()) {
            result = mockMvc.perform(asyncDispatch(asyncResult)).andReturn();
        } else {
            result = asyncResult;
        }

        scenarioContext.setLastResponse(result);

        // Extract bookingId from the full BookingResponse
        String responseBody = result.getResponse().getContentAsString();
        if (result.getResponse().getStatus() == 200 && !responseBody.isEmpty()) {
            BookingResponse response = objectMapper.readValue(responseBody, BookingResponse.class);
            scenarioContext.set("bookingId", response.getBookingId());
        }
    }
}
