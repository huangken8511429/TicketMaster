package com.keer.ticketmaster.service;

import com.keer.ticketmaster.avro.SectionStatusEvent;
import com.keer.ticketmaster.po.Event;
import com.keer.ticketmaster.po.Section;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SectionStatusSseServiceTest {

    private SectionAvailabilityService availabilityService;
    private SectionStatusSseService service;

    @BeforeEach
    void setUp() {
        availabilityService = mock(SectionAvailabilityService.class);
        service = new SectionStatusSseService(availabilityService);
        ReflectionTestUtils.setField(service, "heartbeatIntervalSeconds", 999L);
        ReflectionTestUtils.setField(service, "emitterTimeoutMinutes", 30L);
        service.startHeartbeat();
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    @Test
    void subscribe_registersEmitterAndSendsInitialConnectedEvent() {
        SseEmitter emitter = service.subscribe(42L);
        assertEquals(1, service.emitterCount(42L));
        // emitter should still be open after the initial event
        assertTrue(emitter.getTimeout() > 0);
    }

    @Test
    void handleEvent_aggregatesAcrossSubPartitionsAndBroadcasts() throws Exception {
        Event event = sampleEvent();
        when(availabilityService.findEventForBridge(42L)).thenReturn(event);
        when(availabilityService.isBeforeSalesStart(event)).thenReturn(false);
        when(availabilityService.deriveStatus(false, 150, 200)).thenReturn("ON_SALE_PLENTY");

        SseEmitter spy = mock(SseEmitter.class);
        registerEmitter(42L, spy);

        // sub-partition 0 reports 100
        service.publishForTest(buildStatus(42L, "A", 0, 2, 100));
        // sub-partition 1 reports 50, total 150 across A
        service.publishForTest(buildStatus(42L, "A", 1, 2, 50));

        verify(spy, atLeastOnce()).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void handleEvent_doesNothingWhenNoSubscribers() {
        when(availabilityService.findEventForBridge(42L)).thenReturn(sampleEvent());
        when(availabilityService.isBeforeSalesStart(any())).thenReturn(false);
        when(availabilityService.deriveStatus(false, 100, 200)).thenReturn("ON_SALE_PLENTY");

        service.publishForTest(buildStatus(42L, "A", 0, 2, 100));
        assertEquals(0, service.emitterCount(42L));
    }

    @Test
    void handleEvent_removesEmitterOnSendFailure() throws Exception {
        Event event = sampleEvent();
        when(availabilityService.findEventForBridge(42L)).thenReturn(event);
        when(availabilityService.isBeforeSalesStart(event)).thenReturn(false);
        when(availabilityService.deriveStatus(false, 100, 200)).thenReturn("ON_SALE_PLENTY");

        SseEmitter failing = mock(SseEmitter.class);
        org.mockito.Mockito.doThrow(new java.io.IOException("client gone"))
                .when(failing).send(any(SseEmitter.SseEventBuilder.class));
        registerEmitter(42L, failing);

        service.publishForTest(buildStatus(42L, "A", 0, 2, 100));
        assertEquals(0, service.emitterCount(42L));
    }

    @SuppressWarnings("unchecked")
    private void registerEmitter(Long eventId, SseEmitter emitter) {
        ConcurrentHashMap<Long, CopyOnWriteArrayList<SseEmitter>> map =
                (ConcurrentHashMap<Long, CopyOnWriteArrayList<SseEmitter>>)
                        ReflectionTestUtils.getField(service, "emitters");
        map.computeIfAbsent(eventId, k -> new CopyOnWriteArrayList<>()).add(emitter);
    }

    private Event sampleEvent() {
        Event e = new Event();
        e.setId(42L);
        e.setName("E");
        Section a = new Section();
        a.setName("A");
        a.setRows(10);
        a.setCols(20); // total 200
        List<Section> sections = new ArrayList<>();
        sections.add(a);
        e.setSections(sections);
        return e;
    }

    private SectionStatusEvent buildStatus(long eventId, String section, int sp, int total, int avail) {
        return SectionStatusEvent.newBuilder()
                .setEventId(eventId)
                .setSection(section)
                .setSubPartition(sp)
                .setTotalSubPartitions(total)
                .setAvailableCount(avail)
                .setTimestamp(System.currentTimeMillis())
                .build();
    }
}
