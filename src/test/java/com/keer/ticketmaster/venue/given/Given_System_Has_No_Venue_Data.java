package com.keer.ticketmaster.venue.given;

import com.keer.ticketmaster.venue.repository.VenueRepository;

/**
 * Given: 系統中沒有任何場館資料
 * 前置條件：清空所有場館資料，確保測試從乾淨狀態開始
 */
public class Given_System_Has_No_Venue_Data {

    private final VenueRepository venueRepository;

    public Given_System_Has_No_Venue_Data(VenueRepository venueRepository) {
        this.venueRepository = venueRepository;
    }

    public void execute() {
        venueRepository.deleteAll();
    }
}
