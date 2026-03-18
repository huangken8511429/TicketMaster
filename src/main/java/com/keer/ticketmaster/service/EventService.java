package com.keer.ticketmaster.service;

import com.keer.ticketmaster.avro.SectionInitCommand;
import com.keer.ticketmaster.config.Topic;
import com.keer.ticketmaster.request.SectionRequest;
import com.keer.ticketmaster.request.EventRequest;
import com.keer.ticketmaster.response.EventResponse;
import com.keer.ticketmaster.po.Event;
import com.keer.ticketmaster.po.Section;
import com.keer.ticketmaster.repository.EventRepository;
import com.keer.ticketmaster.po.Performer;
import com.keer.ticketmaster.repository.PerformerRepository;
import com.keer.ticketmaster.po.Venue;
import com.keer.ticketmaster.repository.VenueRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@Profile({"api", "default"})
@RequiredArgsConstructor
public class EventService {

    private final EventRepository eventRepository;
    private final VenueRepository venueRepository;
    private final PerformerRepository performerRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public EventResponse createEvent(EventRequest request) {
        Venue venue = venueRepository.findById(request.getVenueId()).orElse(null);
        if (venue == null) {
            return null;
        }

        Performer performer = null;
        if (request.getPerformerId() != null) {
            performer = performerRepository.findById(request.getPerformerId()).orElse(null);
        }

        Event event = new Event();
        event.setName(request.getName());
        event.setDescription(request.getDescription());
        event.setEventStartTime(request.getEventStartTime());
        event.setEventEndTime(request.getEventEndTime());
        event.setVenue(venue);
        event.setPerformer(performer);

        // Create Section entities
        if (request.getSections() != null) {
            List<Section> sections = new ArrayList<>();
            for (SectionRequest sr : request.getSections()) {
                Section section = new Section();
                section.setName(sr.getName());
                section.setRows(sr.getRows());
                section.setCols(sr.getSeatsPerRow());
                section.setAvailableSeats(sr.getRows() * sr.getSeatsPerRow());
                sections.add(section);
            }
            event.setSections(sections);
        }

        Event saved = eventRepository.save(event);

        int totalSeats = 0;
        if (request.getSections() != null) {
            for (SectionRequest section : request.getSections()) {
                totalSeats += publishSectionInit(saved.getId(), section);
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

    private int publishSectionInit(Long eventId, SectionRequest section) {
        String key = eventId + "-" + section.getName();
        int totalSeats = section.getRows() * section.getSeatsPerRow();

        SectionInitCommand command = SectionInitCommand.newBuilder()
                .setEventId(eventId)
                .setSection(section.getName())
                .setRows(section.getRows())
                .setSeatsPerRow(section.getSeatsPerRow())
                .setInitialReserved(List.of())
                .build();

        kafkaTemplate.send(Topic.SECTION_INIT, key, command);
        return totalSeats;
    }

    private EventResponse toResponse(Event event, Integer totalSeats) {
        return EventResponse.builder()
                .id(event.getId())
                .name(event.getName())
                .description(event.getDescription())
                .eventStartTime(event.getEventStartTime())
                .eventEndTime(event.getEventEndTime())
                .venueId(event.getVenue().getId())
                .venueName(event.getVenue().getName())
                .performerName(event.getPerformer() != null ? event.getPerformer().getName() : null)
                .totalSeats(totalSeats)
                .sectionCount(event.getSections() != null ? event.getSections().size() : 0)
                .build();
    }
}
