package com.keer.ticketmaster.event.service;

import com.keer.ticketmaster.event.dto.EventRequest;
import com.keer.ticketmaster.event.dto.EventResponse;
import com.keer.ticketmaster.event.model.Event;
import com.keer.ticketmaster.event.repository.EventRepository;
import com.keer.ticketmaster.venue.model.Venue;
import com.keer.ticketmaster.venue.repository.VenueRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class EventService {

    private final EventRepository eventRepository;
    private final VenueRepository venueRepository;

    public EventResponse createEvent(EventRequest request) {
        Venue venue = venueRepository.findById(request.getVenueId()).orElse(null);
        if (venue == null) {
            return null;
        }

        Event event = new Event();
        event.setName(request.getName());
        event.setDescription(request.getDescription());
        event.setEventDate(request.getEventDate());
        event.setVenue(venue);
        Event saved = eventRepository.save(event);
        return toResponse(saved);
    }

    public EventResponse getEvent(Long id) {
        return eventRepository.findById(id)
                .map(this::toResponse)
                .orElse(null);
    }

    public List<EventResponse> getAllEvents() {
        return eventRepository.findAll().stream()
                .map(this::toResponse)
                .toList();
    }

    private EventResponse toResponse(Event event) {
        return EventResponse.builder()
                .id(event.getId())
                .name(event.getName())
                .description(event.getDescription())
                .eventDate(event.getEventDate())
                .venueId(event.getVenue().getId())
                .venueName(event.getVenue().getName())
                .build();
    }
}
