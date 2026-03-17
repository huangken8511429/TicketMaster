package com.keer.ticketmaster.config;

import com.keer.ticketmaster.event.dto.EventRequest;
import com.keer.ticketmaster.event.dto.EventResponse;
import com.keer.ticketmaster.event.dto.SectionRequest;
import com.keer.ticketmaster.event.service.EventService;
import com.keer.ticketmaster.booking.dto.BookingRequest;
import com.keer.ticketmaster.booking.dto.BookingResponse;
import com.keer.ticketmaster.booking.service.BookingService;
import com.keer.ticketmaster.venue.model.Venue;
import com.keer.ticketmaster.venue.repository.VenueRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.DeferredResult;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * V1 compatibility layer for the ticket-master Go load-test client.
 * Maps Go client's API contract to our domain API.
 */
@RestController
@Profile({"api", "default"})
@RequiredArgsConstructor
public class LoadTestCompatController {

    private final EventService eventService;
    private final BookingService bookingService;
    private final VenueRepository venueRepository;

    private final Map<String, EventInfo> eventCache = new ConcurrentHashMap<>();
    private final AtomicLong defaultVenueId = new AtomicLong(0);

    record EventInfo(Long eventId, List<AreaResponse> areas) {}
    record AreaResponse(String areaId, int rowCount, int colCount) {}
    record GoEventRequest(String eventName, String artist, List<GoArea> areas) {}
    record GoArea(String areaId, int rowCount, int colCount) {}
    record GoEventResponse(String eventName, String artist, List<AreaResponse> areas) {}
    record GoReservationRequest(String userId, String eventId, String areaId, int numOfSeats, String type) {}
    record GoSeat(int row, int col) {}
    record GoReservationResponse(String reservationId, String userId, String eventId, String areaId,
                                  int numOfSeats, String state, List<GoSeat> seats) {}

    @GetMapping("/v1/health_check")
    public ResponseEntity<String> healthCheck() {
        return ResponseEntity.ok("OK");
    }

    @PostMapping("/v1/event")
    public ResponseEntity<GoEventResponse> createEvent(@RequestBody GoEventRequest request) {
        List<SectionRequest> sections = request.areas().stream()
                .map(a -> new SectionRequest(a.areaId(), a.rowCount(), a.colCount()))
                .toList();

        int totalCapacity = request.areas().stream()
                .mapToInt(a -> a.rowCount() * a.colCount())
                .sum();

        EventRequest eventRequest = new EventRequest();
        eventRequest.setName(request.eventName());
        eventRequest.setDescription("Load test event");
        eventRequest.setEventStartTime(LocalDateTime.now().plusDays(1));
        eventRequest.setVenueId(ensureVenue(totalCapacity));
        eventRequest.setSections(sections);

        EventResponse response = eventService.createEvent(eventRequest);
        if (response == null) {
            return ResponseEntity.badRequest().build();
        }

        List<AreaResponse> areas = request.areas().stream()
                .map(a -> new AreaResponse(a.areaId(), a.rowCount(), a.colCount()))
                .toList();

        eventCache.put(request.eventName(), new EventInfo(response.getId(), areas));

        return ResponseEntity.ok(new GoEventResponse(request.eventName(), request.artist(), areas));
    }

    /**
     * V1 POST: blocks until Kafka Streams returns the result, then maps to Go client format.
     * Single round-trip — no separate GET needed.
     */
    @PostMapping("/v1/event/{eventName}/reservation")
    public DeferredResult<ResponseEntity<GoReservationResponse>> createReservation(
            @PathVariable String eventName,
            @RequestBody GoReservationRequest request) {

        DeferredResult<ResponseEntity<GoReservationResponse>> goDeferred = new DeferredResult<>(10_000L);

        EventInfo eventInfo = eventCache.get(eventName);
        if (eventInfo == null) {
            goDeferred.setResult(ResponseEntity.badRequest().build());
            return goDeferred;
        }

        BookingRequest bookingRequest = new BookingRequest();
        bookingRequest.setEventId(eventInfo.eventId());
        bookingRequest.setSection(request.areaId());
        bookingRequest.setSeatCount(request.numOfSeats());
        bookingRequest.setUserId(request.userId());

        // Delegate to the blocking POST which returns DeferredResult<BookingResponse>
        DeferredResult<ResponseEntity<BookingResponse>> internalDeferred =
                bookingService.createBooking(bookingRequest);

        internalDeferred.setResultHandler(result -> {
            if (result instanceof ResponseEntity<?> re && re.getBody() instanceof BookingResponse r) {
                goDeferred.setResult(ResponseEntity.ok(toGoResponse(r)));
            } else {
                goDeferred.setResult(ResponseEntity.accepted().build());
            }
        });

        goDeferred.onTimeout(() -> internalDeferred.setResult(ResponseEntity.accepted().build()));

        return goDeferred;
    }

    /**
     * V1 GET: synchronous read from Kafka Streams state store via Interactive Query.
     * Returns instantly — no long-polling.
     */
    @GetMapping("/v1/reservation/{bookingId}")
    public ResponseEntity<GoReservationResponse> getReservation(@PathVariable String bookingId) {
        BookingResponse response = bookingService.queryBooking(bookingId);
        if (response == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(toGoResponse(response));
    }

    private GoReservationResponse toGoResponse(BookingResponse r) {
        String state = "CONFIRMED".equals(r.getStatus()) ? "RESERVED" : r.getStatus();

        List<GoSeat> seats = List.of();
        if (r.getAllocatedSeats() != null) {
            seats = r.getAllocatedSeats().stream()
                    .map(s -> {
                        String[] parts = s.split("-");
                        int row = parts[0].charAt(0) - 'A';
                        int col = Integer.parseInt(parts[1]);
                        return new GoSeat(row, col);
                    })
                    .toList();
        }

        return new GoReservationResponse(
                r.getBookingId(),
                r.getUserId(),
                r.getEventId() != null ? r.getEventId().toString() : null,
                r.getSection(),
                r.getSeatCount(),
                state,
                seats
        );
    }

    private Long ensureVenue(int capacity) {
        long cached = defaultVenueId.get();
        if (cached > 0) return cached;

        synchronized (this) {
            cached = defaultVenueId.get();
            if (cached > 0) return cached;

            Venue venue = new Venue();
            venue.setName("Load Test Venue");
            venue.setLocation("localhost");
            venue.setSeatMap(null);
            Venue saved = venueRepository.save(venue);
            defaultVenueId.set(saved.getId());
            return saved.getId();
        }
    }
}
