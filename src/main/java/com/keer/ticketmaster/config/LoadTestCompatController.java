package com.keer.ticketmaster.config;

import com.keer.ticketmaster.event.dto.EventRequest;
import com.keer.ticketmaster.event.dto.EventResponse;
import com.keer.ticketmaster.event.dto.SectionRequest;
import com.keer.ticketmaster.event.service.EventService;
import com.keer.ticketmaster.reservation.dto.ReservationRequest;
import com.keer.ticketmaster.reservation.dto.ReservationResponse;
import com.keer.ticketmaster.reservation.service.ReservationService;
import com.keer.ticketmaster.venue.model.Venue;
import com.keer.ticketmaster.venue.repository.VenueRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.DeferredResult;

import java.time.LocalDate;
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
    private final ReservationService reservationService;
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
        eventRequest.setEventDate(LocalDate.now());
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

    @PostMapping("/v1/event/{eventName}/reservation")
    public ResponseEntity<String> createReservation(
            @PathVariable String eventName,
            @RequestBody GoReservationRequest request) {

        EventInfo eventInfo = eventCache.get(eventName);
        if (eventInfo == null) {
            return ResponseEntity.badRequest().body("Event not found: " + eventName);
        }

        ReservationRequest reservationRequest = new ReservationRequest();
        reservationRequest.setEventId(eventInfo.eventId());
        reservationRequest.setSection(request.areaId());
        reservationRequest.setSeatCount(request.numOfSeats());
        reservationRequest.setUserId(request.userId());

        ReservationService.CreateResult result = reservationService.createReservation(reservationRequest);
        return ResponseEntity.ok(result.reservationId());
    }

    /**
     * Long-poll endpoint that wraps the internal DeferredResult and maps the response
     * to Go client format (state instead of status, Seat{row,col} instead of "A-1").
     */
    @GetMapping("/v1/reservation/{reservationId}")
    public DeferredResult<ResponseEntity<GoReservationResponse>> getReservation(
            @PathVariable String reservationId) {

        DeferredResult<ResponseEntity<GoReservationResponse>> goDeferred = new DeferredResult<>(10_000L);

        // Get the internal DeferredResult from the reservation service
        DeferredResult<ResponseEntity<ReservationResponse>> internalDeferred =
                reservationService.getReservationAsync(reservationId);

        // Poll: when the internal deferred is set, map and resolve the Go deferred
        // We use setResultHandler on the internal deferred
        internalDeferred.setResultHandler(result -> {
            if (result instanceof ResponseEntity<?> re && re.getBody() instanceof ReservationResponse r) {
                goDeferred.setResult(ResponseEntity.ok(toGoResponse(r)));
            } else {
                goDeferred.setResult(ResponseEntity.accepted().build());
            }
        });

        goDeferred.onTimeout(() -> {
            // If Go deferred times out, ensure internal one is also cleaned up
            internalDeferred.setResult(ResponseEntity.accepted().build());
        });

        return goDeferred;
    }

    private GoReservationResponse toGoResponse(ReservationResponse r) {
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
                r.getReservationId(),
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
            venue.setAddress("localhost");
            venue.setCapacity(capacity);
            Venue saved = venueRepository.save(venue);
            defaultVenueId.set(saved.getId());
            return saved.getId();
        }
    }
}
