package com.keer.ticketmaster.reservation.given;

import com.keer.ticketmaster.ScenarioContext;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Reservation Given steps.
 * Ticket creation is handled by TicketGivenSteps (shared step: 該活動已存在以下票券).
 * Venue/Event creation is handled by VenueGivenSteps/EventGivenSteps.
 */
public class ReservationGivenSteps {

    @Autowired
    private ScenarioContext scenarioContext;
}
