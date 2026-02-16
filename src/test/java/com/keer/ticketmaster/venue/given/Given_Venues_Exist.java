package com.keer.ticketmaster.venue.given;

import com.keer.ticketmaster.venue.model.Venue;
import com.keer.ticketmaster.venue.repository.VenueRepository;

import java.util.List;

/**
 * Given: 系統中已存在以下場館（DataTable）
 * 前置條件：在資料庫中批量建立多個場館
 */
public class Given_Venues_Exist {

    private final VenueRepository venueRepository;

    public Given_Venues_Exist(VenueRepository venueRepository) {
        this.venueRepository = venueRepository;
    }

    public List<Venue> execute(List<Venue> venues) {
        return venueRepository.saveAll(venues);
    }
}
