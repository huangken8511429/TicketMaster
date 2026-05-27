package com.keer.ticketmaster.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Redis mirror of section-status counters, consumed by the frontend
 * availability cards via {@link SectionAvailabilityService}.
 *
 * <p>Booking-related Redis usage (pre-filter, booking-result cache,
 * incrementBack) was removed in Phase B — the booking hot path now goes
 * directly through Kafka Streams and RocksDB, matching ticket-master.
 */
@Service
@Profile({"api", "default"})
@RequiredArgsConstructor
@Slf4j
public class SeatAvailabilityRedisService {

    private final StringRedisTemplate redisTemplate;

    private static final String SEAT_AVAIL_PREFIX = "seat-avail:";
    private static final String SUB_PARTS_PREFIX = "section-subparts:";

    public void setAvailableCount(long eventId, String section, int subPartition, int count) {
        String key = seatAvailKey(eventId, section, subPartition);
        redisTemplate.opsForValue().set(key, String.valueOf(count));
    }

    public int getAvailableCount(long eventId, String section, int subPartition) {
        String key = seatAvailKey(eventId, section, subPartition);
        String val = redisTemplate.opsForValue().get(key);
        if (val == null) return 0;
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public void setSubPartitionCount(long eventId, String section, int count) {
        String key = SUB_PARTS_PREFIX + eventId + "-" + section;
        redisTemplate.opsForValue().set(key, String.valueOf(count));
    }

    public int getSubPartitionCount(long eventId, String section) {
        String key = SUB_PARTS_PREFIX + eventId + "-" + section;
        String val = redisTemplate.opsForValue().get(key);
        return val != null ? Integer.parseInt(val) : 0;
    }

    private String seatAvailKey(long eventId, String section, int subPartition) {
        return SEAT_AVAIL_PREFIX + eventId + "-" + section + "-" + subPartition;
    }
}
