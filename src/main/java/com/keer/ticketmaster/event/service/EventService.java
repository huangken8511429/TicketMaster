package com.keer.ticketmaster.event.service;

import com.keer.ticketmaster.avro.SeatEvent;
import com.keer.ticketmaster.avro.SeatStateStatus;
import com.keer.ticketmaster.config.KafkaConstants;
import com.keer.ticketmaster.event.dto.AreaRequest;
import com.keer.ticketmaster.event.dto.EventRequest;
import com.keer.ticketmaster.event.dto.EventResponse;
import com.keer.ticketmaster.event.model.Event;
import com.keer.ticketmaster.event.repository.EventRepository;
import com.keer.ticketmaster.venue.model.Venue;
import com.keer.ticketmaster.venue.repository.VenueRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
@Profile({"api", "default"})
@RequiredArgsConstructor
public class EventService {

    private final EventRepository eventRepository;
    private final VenueRepository venueRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

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

        int totalSeats = 0;
        if (request.getAreas() != null) {
            for (AreaRequest area : request.getAreas()) {
                totalSeats += publishSeatEvents(saved.getId(), area);
            }
        }

        return toResponse(saved, totalSeats);
    }

    public EventResponse getEvent(Long id) {
        return eventRepository.findById(id)
                .map(e -> toResponse(e, null))
                .orElse(null);
    }

    public List<EventResponse> getAllEvents() {
        return eventRepository.findAll().stream()
                .map(e -> toResponse(e, null))
                .toList();
    }

    private int publishSeatEvents(Long eventId, AreaRequest area) {
        String key = eventId.toString();
        int count = 0;
        for (int row = 1; row <= area.getRows(); row++) {
            for (int col = 1; col <= area.getSeatsPerRow(); col++) {
                SeatEvent seatEvent = SeatEvent.newBuilder()
                        .setEventId(eventId)
                        .setSeatNumber("R" + row + "-" + col)
                        .setSection(area.getSection())
                        .setStatus(SeatStateStatus.AVAILABLE)
                        .setTimestamp(Instant.now().toEpochMilli())
                        .build();
                kafkaTemplate.send(KafkaConstants.TOPIC_SEAT_EVENTS, key, seatEvent);
                count++;
            }
        }
        return count;
    }

    private EventResponse toResponse(Event event, Integer totalSeats) {
        return EventResponse.builder()
                .id(event.getId())
                .name(event.getName())
                .description(event.getDescription())
                .eventDate(event.getEventDate())
                .venueId(event.getVenue().getId())
                .venueName(event.getVenue().getName())
                .totalSeats(totalSeats)
                .build();
    }
}
