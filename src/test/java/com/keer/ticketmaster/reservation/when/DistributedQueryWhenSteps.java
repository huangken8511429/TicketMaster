package com.keer.ticketmaster.reservation.when;

import com.keer.ticketmaster.ScenarioContext;
import com.keer.ticketmaster.reservation.dto.ReservationResponse;
import com.keer.ticketmaster.reservation.service.InteractiveQueryService;
import io.cucumber.java.zh_tw.當;
import org.springframework.beans.factory.annotation.Autowired;

public class DistributedQueryWhenSteps {

    @Autowired
    private ScenarioContext scenarioContext;

    @當("^分別向兩個實例查詢預訂「(.+)」$")
    public void 分別向兩個實例查詢預訂(String reservationId) {
        InteractiveQueryService instance1 = (InteractiveQueryService) scenarioContext.get("instance1");
        InteractiveQueryService instance2 = (InteractiveQueryService) scenarioContext.get("instance2");

        ReservationResponse response1 = instance1.queryReservation(reservationId);
        ReservationResponse response2 = instance2.queryReservation(reservationId);

        scenarioContext.set("response1", response1);
        scenarioContext.set("response2", response2);
    }
}
