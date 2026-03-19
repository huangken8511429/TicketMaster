import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

// Custom metrics
const bookingSuccess = new Counter('booking_success');
const bookingRejected = new Counter('booking_rejected');
const bookingTimeout = new Counter('booking_timeout');
const bookingError = new Counter('booking_error');
const apiRejected = new Counter('api_rejected');  // Redis pre-filter rejection
const successRate = new Rate('booking_success_rate');
const postDuration = new Trend('post_duration', true);
const pollDuration = new Trend('poll_duration', true);
const e2eDuration = new Trend('e2e_duration', true);

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const VUS = parseInt(__ENV.VUS || '50');
const ITERATIONS = parseInt(__ENV.ITERATIONS || '1000');
const SUB_PARTITIONS = parseInt(__ENV.SUB_PARTITIONS || '1');

export const options = {
    scenarios: {
        booking_rush: {
            executor: 'shared-iterations',
            vus: VUS,
            iterations: ITERATIONS,
            maxDuration: '15m',
        },
    },
    thresholds: {
        booking_success_rate: ['rate>0.3'],
    },
};

// Setup: Create venue + event + init sections via admin API
export function setup() {
    console.log('=== Setting up test data ===');

    const venueRes = http.post(`${BASE_URL}/api/venues`, JSON.stringify({
        name: 'Stress Test Arena',
        location: 'Test Location',
        seatMap: 'standard-arena',
    }), { headers: { 'Content-Type': 'application/json' } });

    check(venueRes, { 'venue created': (r) => r.status === 201 });
    const venue = venueRes.json();
    console.log(`Venue created: id=${venue.id}`);

    const eventRes = http.post(`${BASE_URL}/api/events`, JSON.stringify({
        name: 'Stress Test Concert',
        description: 'Load test event',
        eventStartTime: '2026-12-31T19:00:00',
        eventEndTime: '2026-12-31T22:00:00',
        venueId: venue.id,
    }), { headers: { 'Content-Type': 'application/json' } });

    check(eventRes, { 'event created': (r) => r.status === 201 });
    const event = eventRes.json();
    console.log(`Event created: id=${event.id}`);

    // Init sections via admin API (Kafka Streams state store)
    const sectionNames = (__ENV.SECTIONS || 'A,B,C,D,E').split(',');
    const rows = parseInt(__ENV.ROWS || '20');
    const seatsPerRow = parseInt(__ENV.SEATS_PER_ROW || '25');
    const totalSeatsPerSection = rows * seatsPerRow;

    for (const name of sectionNames) {
        const initRes = http.post(
            `${BASE_URL}/admin/sections/init?eventId=${event.id}&section=${name}&rows=${rows}&seatsPerRow=${seatsPerRow}&subPartitions=${SUB_PARTITIONS}`,
            null, { timeout: '10s' }
        );
        check(initRes, { [`section ${name} init`]: (r) => r.status === 200 });
    }

    const totalSeats = sectionNames.length * totalSeatsPerSection;
    console.log(`Sections initialized: ${sectionNames.length} x ${totalSeatsPerSection} = ${totalSeats} seats (subPartitions=${SUB_PARTITIONS})`);

    console.log('Waiting 10s for Kafka Streams to process section-init...');
    sleep(10);

    // Smoke test
    console.log('Running smoke test...');
    const smokeRes = http.post(`${BASE_URL}/api/bookings`, JSON.stringify({
        eventId: event.id, section: 'A', seatCount: 1, userId: 'smoke-user',
    }), { headers: { 'Content-Type': 'application/json' }, timeout: '5s' });

    if (smokeRes.status === 202) {
        const bid = smokeRes.json().bookingId;
        sleep(5);
        const pollRes = http.get(`${BASE_URL}/api/bookings/${bid}`, { timeout: '10s' });
        if (pollRes.status === 200) {
            const body = pollRes.json();
            console.log(`Smoke test: ${body.status}, seats=${JSON.stringify(body.allocatedSeats)}`);
        } else {
            console.log(`WARNING: Smoke test poll failed: status=${pollRes.status}`);
        }
    } else {
        console.log(`WARNING: Smoke test POST returned ${smokeRes.status}: ${smokeRes.body}`);
    }

    return {
        eventId: event.id,
        sections: sectionNames,
        totalSeats: totalSeats,
    };
}

// Main test: POST booking + poll GET
export default function (data) {
    const section = data.sections[Math.floor(Math.random() * data.sections.length)];
    const seatCount = Math.random() < 0.7 ? 2 : 1;
    const userId = `user-${__VU}-${__ITER}`;

    const payload = JSON.stringify({
        eventId: data.eventId,
        section: section,
        seatCount: seatCount,
        userId: userId,
    });

    const e2eStart = Date.now();

    // Step 1: POST booking
    const postStart = Date.now();
    const postRes = http.post(`${BASE_URL}/api/bookings`, payload, {
        headers: { 'Content-Type': 'application/json' },
        timeout: '5s',
    });
    postDuration.add(Date.now() - postStart);

    // Handle Redis pre-filter rejection (422)
    if (postRes.status === 422) {
        apiRejected.add(1);
        bookingRejected.add(1);
        successRate.add(true);  // Pre-filter rejection is expected behavior
        e2eDuration.add(Date.now() - e2eStart);
        return;
    }

    if (postRes.status !== 202) {
        bookingError.add(1);
        successRate.add(false);
        return;
    }

    const bookingId = postRes.json().bookingId;

    // Step 2: Long-poll GET for result
    const pollStart = Date.now();
    const pollRes = http.get(`${BASE_URL}/api/bookings/${bookingId}`, {
        timeout: '12s',
    });
    pollDuration.add(Date.now() - pollStart);
    e2eDuration.add(Date.now() - e2eStart);

    if (pollRes.status === 200) {
        const body = pollRes.json();
        if (body.status === 'CONFIRMED') {
            bookingSuccess.add(1);
            successRate.add(true);
        } else if (body.status === 'REJECTED') {
            bookingRejected.add(1);
            successRate.add(true);
        } else {
            bookingTimeout.add(1);
            successRate.add(false);
        }
    } else {
        bookingTimeout.add(1);
        successRate.add(false);
    }
}

export function teardown(data) {
    console.log('\n=== Stress Test Complete ===');
    console.log(`Event ID: ${data.eventId}`);
    console.log(`Total seats: ${data.totalSeats}`);
    console.log(`Total requests: ${ITERATIONS}`);
    console.log('Metrics: booking_success (CONFIRMED) + booking_rejected (REJECTED/API pre-filter) + booking_timeout');
    console.log('api_rejected = bookings rejected at API layer by Redis pre-filter (no Kafka roundtrip)');
}
