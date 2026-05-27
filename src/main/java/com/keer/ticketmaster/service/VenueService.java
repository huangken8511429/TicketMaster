package com.keer.ticketmaster.service;

import com.keer.ticketmaster.request.VenueRequest;
import com.keer.ticketmaster.response.VenueResponse;
import com.keer.ticketmaster.po.Venue;
import com.keer.ticketmaster.repository.VenueRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Profile({"api", "default"})
@RequiredArgsConstructor
public class VenueService {

    private final VenueRepository venueRepository;

    @Transactional
    public VenueResponse createVenue(VenueRequest request) {
        Venue venue = new Venue();
        venue.setName(request.getName());
        venue.setLocation(request.getLocation());
        venue.setSeatMap(request.getSeatMap());
        Venue saved = venueRepository.save(venue);
        return toResponse(saved);
    }

    public VenueResponse getVenue(Long id) {
        return venueRepository.findById(id)
                .map(this::toResponse)
                .orElse(null);
    }

    public List<VenueResponse> getAllVenues() {
        return venueRepository.findAll().stream()
                .map(this::toResponse)
                .toList();
    }

    private VenueResponse toResponse(Venue venue) {
        return VenueResponse.builder()
                .id(venue.getId())
                .name(venue.getName())
                .location(venue.getLocation())
                .seatMap(venue.getSeatMap())
                .build();
    }
}
