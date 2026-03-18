package com.keer.ticketmaster.config;

/**
 * Centralized store key construction for seat inventory and related state.
 * Single point of change if key format ever needs to evolve.
 */
public class StoreKeyUtil {

    public static String seatKey(long eventId, String section, int subPartition) {
        return eventId + "-" + section + "-" + subPartition;
    }
}
