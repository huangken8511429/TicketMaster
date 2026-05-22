package com.keer.ticketmaster.service;

import com.keer.ticketmaster.po.Event;
import com.keer.ticketmaster.po.Section;
import com.keer.ticketmaster.po.Venue;
import com.keer.ticketmaster.repository.EventRepository;
import com.keer.ticketmaster.response.SectionAvailabilityResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SectionAvailabilityServiceTest {

    @Mock
    private EventRepository eventRepository;

    @Mock
    private SeatAvailabilityRedisService redisService;

    @InjectMocks
    private SectionAvailabilityService service;

    private Event event;

    @BeforeEach
    void setUp() {
        Venue venue = new Venue();
        venue.setId(1L);
        venue.setName("台北小巨蛋");
        venue.setLocation("Taipei");

        Section a = new Section();
        a.setName("A");
        a.setRows(10);
        a.setCols(20); // total 200
        a.setBasePrice(1800L);

        Section b = new Section();
        b.setName("B");
        b.setRows(20);
        b.setCols(30); // total 600
        b.setBasePrice(1200L);

        event = new Event();
        event.setId(42L);
        event.setName("五月天 2026");
        event.setEventStartTime(LocalDateTime.now().plusDays(30));
        event.setVenue(venue);
        List<Section> sections = new ArrayList<>();
        sections.add(a);
        sections.add(b);
        event.setSections(sections);
    }

    @Test
    void getSectionsForEvent_returnsEmptyListWhenEventMissing() {
        when(eventRepository.findById(99L)).thenReturn(Optional.empty());
        assertTrue(service.getSectionsForEvent(99L).isEmpty());
    }

    @Test
    void getSectionsForEvent_aggregatesAcrossSubPartitionsAndDerivesPlenty() {
        when(eventRepository.findById(42L)).thenReturn(Optional.of(event));
        // 2 sub-partitions, 100 + 100 = 200 available in A (100% → PLENTY)
        when(redisService.getSubPartitionCount(42L, "A")).thenReturn(2);
        when(redisService.getAvailableCount(42L, "A", 0)).thenReturn(100);
        when(redisService.getAvailableCount(42L, "A", 1)).thenReturn(100);
        // 4 sub-partitions for B, total 600, but only 60 available (10% → LIMITED)
        when(redisService.getSubPartitionCount(42L, "B")).thenReturn(4);
        when(redisService.getAvailableCount(42L, "B", 0)).thenReturn(20);
        when(redisService.getAvailableCount(42L, "B", 1)).thenReturn(20);
        when(redisService.getAvailableCount(42L, "B", 2)).thenReturn(10);
        when(redisService.getAvailableCount(42L, "B", 3)).thenReturn(10);

        List<SectionAvailabilityResponse> out = service.getSectionsForEvent(42L);
        assertEquals(2, out.size());

        SectionAvailabilityResponse a = out.get(0);
        assertEquals("A", a.getSection());
        assertEquals(200, a.getTotalSeats());
        assertEquals(200, a.getAvailableCount());
        assertEquals("ON_SALE_PLENTY", a.getStatus());
        assertEquals(Long.valueOf(1800L), a.getBasePrice());

        SectionAvailabilityResponse b = out.get(1);
        assertEquals("B", b.getSection());
        assertEquals(600, b.getTotalSeats());
        assertEquals(60, b.getAvailableCount());
        assertEquals("ON_SALE_LIMITED", b.getStatus());
    }

    @Test
    void getSectionsForEvent_returnsNotStartedWhenBeforeSalesStart() {
        event.setSalesStartAt(LocalDateTime.now().plusHours(1));
        when(eventRepository.findById(42L)).thenReturn(Optional.of(event));
        lenient().when(redisService.getSubPartitionCount(eq(42L), anyString())).thenReturn(1);
        lenient().when(redisService.getAvailableCount(eq(42L), anyString(), eq(0))).thenReturn(100);

        List<SectionAvailabilityResponse> out = service.getSectionsForEvent(42L);
        for (SectionAvailabilityResponse r : out) {
            assertEquals("NOT_STARTED", r.getStatus());
        }
    }

    @Test
    void getSectionsForEvent_fallsBackToTotalSeatsWhenNoSubPartitionMetadata() {
        when(eventRepository.findById(42L)).thenReturn(Optional.of(event));
        when(redisService.getSubPartitionCount(42L, "A")).thenReturn(0);
        when(redisService.getSubPartitionCount(42L, "B")).thenReturn(0);

        List<SectionAvailabilityResponse> out = service.getSectionsForEvent(42L);
        assertEquals(200, out.get(0).getAvailableCount());
        assertEquals(600, out.get(1).getAvailableCount());
        assertEquals("ON_SALE_PLENTY", out.get(0).getStatus());
    }

    @Test
    void deriveStatus_coversAllThresholds() {
        // total 1000
        assertEquals("SOLD_OUT", service.deriveStatus(false, 0, 1000));
        assertEquals("ON_SALE_FEW", service.deriveStatus(false, 50, 1000)); // 5%
        assertEquals("ON_SALE_FEW", service.deriveStatus(false, 1, 1000));
        assertEquals("ON_SALE_LIMITED", service.deriveStatus(false, 100, 1000)); // 10%
        assertEquals("ON_SALE_LIMITED", service.deriveStatus(false, 300, 1000)); // 30%
        assertEquals("ON_SALE_PLENTY", service.deriveStatus(false, 301, 1000));
        assertEquals("ON_SALE_PLENTY", service.deriveStatus(false, 1000, 1000));
        assertEquals("NOT_STARTED", service.deriveStatus(true, 1000, 1000));
    }

    @Test
    void isBeforeSalesStart_returnsFalseWhenNull() {
        event.setSalesStartAt(null);
        assertEquals(false, service.isBeforeSalesStart(event));
    }

    @Test
    void getSectionsForEvent_handlesNullSections() {
        event.setSections(null);
        when(eventRepository.findById(42L)).thenReturn(Optional.of(event));
        assertTrue(service.getSectionsForEvent(42L).isEmpty());
    }

    @Test
    void aggregateAvailableCount_neverReturnsNegative() {
        when(redisService.getSubPartitionCount(42L, "A")).thenReturn(2);
        when(redisService.getAvailableCount(42L, "A", 0)).thenReturn(0);
        when(redisService.getAvailableCount(42L, "A", 1)).thenReturn(0);
        assertEquals(0, service.aggregateAvailableCount(42L, "A", 200));
    }

    @Test
    void basePrice_defaultsToNullForLegacySections() {
        Section legacy = new Section();
        legacy.setName("C");
        legacy.setRows(5);
        legacy.setCols(10);
        event.getSections().add(legacy);

        when(eventRepository.findById(42L)).thenReturn(Optional.of(event));
        when(redisService.getSubPartitionCount(42L, "A")).thenReturn(0);
        when(redisService.getSubPartitionCount(42L, "B")).thenReturn(0);
        when(redisService.getSubPartitionCount(42L, "C")).thenReturn(0);

        List<SectionAvailabilityResponse> out = service.getSectionsForEvent(42L);
        assertNull(out.get(2).getBasePrice());
    }
}
