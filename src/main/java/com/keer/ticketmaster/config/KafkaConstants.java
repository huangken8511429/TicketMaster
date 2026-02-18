package com.keer.ticketmaster.config;

public final class KafkaConstants {

    private KafkaConstants() {}

    public static final String RESERVATION_STORE = "reservation-store";
    public static final String RESERVATION_QUERY_STORE = "reservation-query-store";
    public static final String SEAT_INVENTORY_STORE = "seat-inventory-store";

    public static final String TOPIC_RESERVATION_COMMANDS = "reservation-commands";
    public static final String TOPIC_RESERVATION_REQUESTS = "reservation-requests";
    public static final String TOPIC_RESERVATION_RESULTS = "reservation-results";
    public static final String TOPIC_RESERVATION_COMPLETED = "reservation-completed";
    public static final String TOPIC_SEAT_EVENTS = "seat-events";
}
