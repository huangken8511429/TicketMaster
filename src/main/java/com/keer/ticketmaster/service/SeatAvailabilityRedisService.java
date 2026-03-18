package com.keer.ticketmaster.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keer.ticketmaster.response.BookingResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Redis-based seat availability management for horizontal scaling.
 *
 * Provides:
 * 1. Atomic seat counter per sub-partition (pre-filter at API layer)
 * 2. Booking result cache (replaces Interactive Query cross-pod forwarding)
 * 3. Sub-partition metadata tracking
 */
@Service
@Profile({"api", "default"})
@RequiredArgsConstructor
@Slf4j
public class SeatAvailabilityRedisService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String SEAT_AVAIL_PREFIX = "seat-avail:";
    private static final String BOOKING_PREFIX = "booking:";
    private static final String SUB_PARTS_PREFIX = "section-subparts:";

    private static final Duration BOOKING_CACHE_TTL = Duration.ofMinutes(5);

    private static final RedisScript<Long> FIND_AND_DECREMENT =
            new DefaultRedisScript<>("""
                    local count = #KEYS
                    local needed = tonumber(ARGV[1])
                    local start = tonumber(ARGV[2])
                    for i = 0, count - 1 do
                        local idx = ((start + i) % count) + 1
                        local current = tonumber(redis.call('GET', KEYS[idx]) or '0')
                        if current >= needed then
                            redis.call('DECRBY', KEYS[idx], needed)
                            return idx - 1
                        end
                    end
                    return -1
                    """, Long.class);

    // --- Seat availability counters ---

    public void setAvailableCount(long eventId, String section, int subPartition, int count) {
        String key = seatAvailKey(eventId, section, subPartition);
        redisTemplate.opsForValue().set(key, String.valueOf(count));
    }

    /**
     * Find a sub-partition with enough seats and atomically decrement.
     * Uses a single Lua script for one Redis round-trip.
     *
     * @return the sub-partition index, or -1 if no sub-partition has enough seats
     */
    public int findAndDecrement(long eventId, String section, int seatCount) {
        int subPartitions = getSubPartitionCount(eventId, section);
        if (subPartitions <= 0) return -1;

        List<String> keys = new ArrayList<>(subPartitions);
        for (int i = 0; i < subPartitions; i++) {
            keys.add(seatAvailKey(eventId, section, i));
        }

        int randomStart = ThreadLocalRandom.current().nextInt(subPartitions);

        Long result = redisTemplate.execute(
                FIND_AND_DECREMENT,
                keys,
                String.valueOf(seatCount),
                String.valueOf(randomStart)
        );

        return result != null ? result.intValue() : -1;
    }

    public void incrementBack(long eventId, String section, int subPartition, int seatCount) {
        String key = seatAvailKey(eventId, section, subPartition);
        redisTemplate.opsForValue().increment(key, seatCount);
    }

    // --- Sub-partition metadata ---

    public void setSubPartitionCount(long eventId, String section, int count) {
        String key = SUB_PARTS_PREFIX + eventId + "-" + section;
        redisTemplate.opsForValue().set(key, String.valueOf(count));
    }

    public int getSubPartitionCount(long eventId, String section) {
        String key = SUB_PARTS_PREFIX + eventId + "-" + section;
        String val = redisTemplate.opsForValue().get(key);
        return val != null ? Integer.parseInt(val) : 0;
    }

    // --- Booking result cache ---

    public void cacheBookingResult(BookingResponse response) {
        try {
            String key = BOOKING_PREFIX + response.getBookingId();
            String json = objectMapper.writeValueAsString(response);
            redisTemplate.opsForValue().set(key, json, BOOKING_CACHE_TTL);
        } catch (Exception e) {
            log.debug("Failed to cache booking result: {}", e.getMessage());
        }
    }

    public BookingResponse getCachedBookingResult(String bookingId) {
        try {
            String key = BOOKING_PREFIX + bookingId;
            String json = redisTemplate.opsForValue().get(key);
            if (json != null) {
                return objectMapper.readValue(json, BookingResponse.class);
            }
        } catch (Exception e) {
            log.debug("Failed to read cached booking result: {}", e.getMessage());
        }
        return null;
    }

    private String seatAvailKey(long eventId, String section, int subPartition) {
        return SEAT_AVAIL_PREFIX + eventId + "-" + section + "-" + subPartition;
    }
}
