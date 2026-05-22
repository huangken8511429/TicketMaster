package com.keer.ticketmaster.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.keer.ticketmaster.repository.EventRepository;
import com.keer.ticketmaster.response.SectionAvailabilityResponse;
import com.keer.ticketmaster.service.SectionAvailabilityService;
import com.keer.ticketmaster.service.SectionStatusSseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice tests for SectionController via standalone MockMvc (no Spring context).
 *
 * Covers:
 * - happy path for {@code GET /api/events/{id}/sections}
 * - 404 when event missing
 * - happy path for SSE bridge endpoint
 */
class SectionControllerTest {

    private SectionAvailabilityService availabilityService;
    private SectionStatusSseService sseService;
    private EventRepository eventRepository;
    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        availabilityService = mock(SectionAvailabilityService.class);
        sseService = mock(SectionStatusSseService.class);
        eventRepository = mock(EventRepository.class);

        SectionController controller = new SectionController(availabilityService, sseService, eventRepository);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
    }

    @Test
    void getSections_happyPath_returns200WithDerivedStatus() throws Exception {
        when(eventRepository.existsById(42L)).thenReturn(true);
        when(availabilityService.getSectionsForEvent(42L)).thenReturn(List.of(
                SectionAvailabilityResponse.builder()
                        .eventId(42L).section("A").totalSeats(200).availableCount(180)
                        .status("ON_SALE_PLENTY").basePrice(1800L).build(),
                SectionAvailabilityResponse.builder()
                        .eventId(42L).section("B").totalSeats(600).availableCount(0)
                        .status("SOLD_OUT").basePrice(1200L).build()
        ));

        MvcResult result = mockMvc.perform(get("/api/events/{id}/sections", 42L)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        assertTrue(root.isArray());
        assertEquals(2, root.size());
        assertEquals("A", root.get(0).get("section").asText());
        assertEquals("ON_SALE_PLENTY", root.get(0).get("status").asText());
        assertEquals(180, root.get(0).get("availableCount").asInt());
        assertEquals("SOLD_OUT", root.get(1).get("status").asText());
        assertEquals(1200, root.get(1).get("basePrice").asInt());
    }

    @Test
    void getSections_returns404WhenEventMissing() throws Exception {
        when(eventRepository.existsById(99L)).thenReturn(false);
        mockMvc.perform(get("/api/events/{id}/sections", 99L))
                .andExpect(status().isNotFound());
    }

    @Test
    void streamSections_happyPath_setsBufferControlHeaders() throws Exception {
        when(eventRepository.existsById(42L)).thenReturn(true);
        SseEmitter emitter = new SseEmitter(0L);
        emitter.complete(); // avoid hanging async dispatch
        when(sseService.subscribe(anyLong())).thenReturn(emitter);

        MvcResult result = mockMvc.perform(get("/api/events/{id}/sections/stream", 42L)
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andReturn();

        // Headers are written synchronously by the controller before returning the emitter,
        // so they are visible on the initial response even before async dispatch completes.
        assertEquals("no", result.getResponse().getHeader("X-Accel-Buffering"));
        assertEquals("no-cache", result.getResponse().getHeader("Cache-Control"));
    }

    @Test
    void streamSections_returns404WhenEventMissing() throws Exception {
        when(eventRepository.existsById(99L)).thenReturn(false);
        MvcResult result = mockMvc.perform(get("/api/events/{id}/sections/stream", 99L)
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andReturn();
        assertEquals(404, result.getResponse().getStatus());
    }

    @Test
    void getSections_returns404Body_doesNotInvokeService() throws Exception {
        when(eventRepository.existsById(99L)).thenReturn(false);
        mockMvc.perform(get("/api/events/{id}/sections", 99L)).andExpect(status().isNotFound());
        org.mockito.Mockito.verify(availabilityService, org.mockito.Mockito.never()).getSectionsForEvent(anyLong());
    }
}
