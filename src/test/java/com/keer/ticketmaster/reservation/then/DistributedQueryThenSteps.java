package com.keer.ticketmaster.reservation.then;

import com.keer.ticketmaster.ScenarioContext;
import com.keer.ticketmaster.reservation.dto.ReservationResponse;
import io.cucumber.java.zh_tw.並且;
import io.cucumber.java.zh_tw.那麼;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;

public class DistributedQueryThenSteps {

    @Autowired
    private ScenarioContext scenarioContext;

    @那麼("兩個實例都應成功回傳預訂資料")
    public void 兩個實例都應成功回傳預訂資料() {
        ReservationResponse response1 = (ReservationResponse) scenarioContext.get("response1");
        ReservationResponse response2 = (ReservationResponse) scenarioContext.get("response2");

        assertNotNull(response1, "Instance 1（本地查詢）應回傳預訂資料");
        assertNotNull(response2, "Instance 2（遠端查詢）應回傳預訂資料");
    }

    @並且("兩個實例回傳的預訂資料應完全一致")
    public void 兩個實例回傳的預訂資料應完全一致() {
        ReservationResponse response1 = (ReservationResponse) scenarioContext.get("response1");
        ReservationResponse response2 = (ReservationResponse) scenarioContext.get("response2");

        assertEquals(response1.getReservationId(), response2.getReservationId(), "reservationId 應相同");
        assertEquals(response1.getEventId(), response2.getEventId(), "eventId 應相同");
        assertEquals(response1.getUserId(), response2.getUserId(), "userId 應相同");
        assertEquals(response1.getStatus(), response2.getStatus(), "status 應相同");
        assertEquals(response1.getSection(), response2.getSection(), "section 應相同");
        assertEquals(response1.getSeatCount(), response2.getSeatCount(), "seatCount 應相同");
        assertEquals(response1.getAllocatedSeats(), response2.getAllocatedSeats(), "allocatedSeats 應相同");
        assertEquals(response1.getCreatedAt(), response2.getCreatedAt(), "createdAt 應相同");
    }

    @並且("^查詢結果的預訂狀態應為「(.+)」$")
    public void 查詢結果的預訂狀態應為(String expectedStatus) {
        ReservationResponse response1 = (ReservationResponse) scenarioContext.get("response1");

        assertEquals(expectedStatus, response1.getStatus(), "預訂狀態應為 " + expectedStatus);
    }

    @SuppressWarnings("unchecked")
    @並且("Instance 2 應透過 HTTP 遠端查詢 Instance 1")
    public void instance2應透過HTTP遠端查詢instance1() {
        String reservationId = (String) scenarioContext.get("reservationId");
        int port1 = (int) scenarioContext.get("port1");

        var uriSpec = (RestClient.RequestHeadersUriSpec<? extends RestClient.RequestHeadersSpec<?>>)
                scenarioContext.get("uriSpec");

        verify(uriSpec).uri("http://localhost:" + port1 + "/internal/reservations/" + reservationId);
    }
}
