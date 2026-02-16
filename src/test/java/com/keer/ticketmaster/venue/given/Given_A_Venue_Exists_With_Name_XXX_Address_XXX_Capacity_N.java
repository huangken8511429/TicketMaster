package com.keer.ticketmaster.venue.given;

import com.keer.ticketmaster.venue.model.Venue;
import com.keer.ticketmaster.venue.repository.VenueRepository;

/**
 * Given: 系統中已存在一個場館，名稱為「{name}」，地址為「{address}」，容量為 {capacity}
 * 前置條件：在資料庫中建立一個指定屬性的場館
 */
public class Given_A_Venue_Exists_With_Name_XXX_Address_XXX_Capacity_N {

    private final VenueRepository venueRepository;

    public Given_A_Venue_Exists_With_Name_XXX_Address_XXX_Capacity_N(VenueRepository venueRepository) {
        this.venueRepository = venueRepository;
    }

    public Venue execute(String name, String address, int capacity) {
        Venue venue = new Venue();
        venue.setName(name);
        venue.setAddress(address);
        venue.setCapacity(capacity);
        return venueRepository.save(venue);
    }
}
