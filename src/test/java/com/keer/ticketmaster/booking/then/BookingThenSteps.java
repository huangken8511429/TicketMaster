package com.keer.ticketmaster.booking.then;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keer.ticketmaster.ScenarioContext;
import com.keer.ticketmaster.response.BookingResponse;
import io.cucumber.java.zh_tw.並且;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

public class BookingThenSteps {

    @Autowired
    private ScenarioContext scenarioContext;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MockMvc mockMvc;

    private BookingResponse lastBookingResponse;

    @並且("^等待預定處理完成後，預定狀態應為「(.+)」$")
    public void 等待預定處理完成後狀態應為(String expectedStatus) throws Exception {
        String bookingId = (String) scenarioContext.get("bookingId");
        assertNotNull(bookingId, "應該有 bookingId");

        // GET is now synchronous (Interactive Query on state store) — poll until result appears
        BookingResponse response = null;
        int maxAttempts = 30;
        for (int i = 0; i < maxAttempts; i++) {
            try {
                MvcResult result = mockMvc.perform(
                        get("/api/bookings/" + bookingId)
                                .contentType(MediaType.APPLICATION_JSON))
                        .andReturn();

                if (result.getResponse().getStatus() == 200) {
                    String body = result.getResponse().getContentAsString();
                    response = objectMapper.readValue(body, BookingResponse.class);
                    if (!("PENDING".equals(response.getStatus()))) {
                        break;
                    }
                }
            } catch (Exception e) {
                // Kafka Streams may not be RUNNING yet — retry
            }
            Thread.sleep(500);
        }

        assertNotNull(response, "應該能取得預定資訊");
        assertEquals(expectedStatus, response.getStatus(),
                "預定狀態應為 " + expectedStatus);
        this.lastBookingResponse = response;
    }

    @並且("^預定應包含 (\\d+) 個「(.+)」區的連續座位$")
    public void 預定應包含連續座位(int expectedCount, String expectedSection) {
        assertNotNull(lastBookingResponse, "應該先取得預定資訊");
        List<String> allocatedSeats = lastBookingResponse.getAllocatedSeats();

        assertNotNull(allocatedSeats, "應該有分配的座位");
        assertEquals(expectedCount, allocatedSeats.size(),
                "分配的座位數應為 " + expectedCount);

        // Verify all seats belong to the expected section
        for (String seat : allocatedSeats) {
            assertTrue(seat.startsWith(expectedSection + "-"),
                    "座位 " + seat + " 應屬於 " + expectedSection + " 區");
        }

        // Verify seats are consecutive
        for (int i = 1; i < allocatedSeats.size(); i++) {
            int prev = extractNumber(allocatedSeats.get(i - 1));
            int curr = extractNumber(allocatedSeats.get(i));
            assertEquals(prev + 1, curr,
                    "座位應為連續：" + allocatedSeats.get(i - 1) + " → " + allocatedSeats.get(i));
        }
    }

    private int extractNumber(String seatNumber) {
        int lastDash = seatNumber.lastIndexOf('-');
        return Integer.parseInt(seatNumber.substring(lastDash + 1));
    }
}
