import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

// Custom metrics
const bookingSuccess = new Counter('booking_success');
const bookingRejected = new Counter('booking_rejected');
const bookingTimeout = new Counter('booking_timeout');
const bookingError = new Counter('booking_error');
const successRate = new Rate('booking_success_rate');
const postDuration = new Trend('post_duration', true);
const pollDuration = new Trend('poll_duration', true);
const e2eDuration = new Trend('e2e_duration', true);

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const POLL_INTERVAL_MS = 100;  // ms between poll attempts
const MAX_POLL_ATTEMPTS = 100;  // 10s total timeout

// 1000 requests, 50 concurrent users
export const options = {
    scenarios: {
        booking_rush: {
            executor: 'shared-iterations',
            vus: 50,
            iterations: 1000,
            maxDuration: '5m',
        },
    },
    thresholds: {
        booking_success_rate: ['rate>0.3'],
    },
};

// Setup: Create venue + event + sections
export function setup() {
    console.log('=== Setting up test data ===');

    const venueRes = http.post(`${BASE_URL}/api/venues`, JSON.stringify({
        name: 'Load Test Arena',
        location: 'Test Location',
        seatMap: 'standard-arena',
    }), { headers: { 'Content-Type': 'application/json' } });

    check(venueRes, { 'venue created': (r) => r.status === 201 });
    const venue = venueRes.json();
    console.log(`Venue created: id=${venue.id}`);

    const performerRes = http.post(`${BASE_URL}/api/performers`, JSON.stringify({
        name: 'Load Test Band',
        description: 'Stress test performer',
    }), { headers: { 'Content-Type': 'application/json' } });

    let performerId = null;
    if (performerRes.status === 201) {
        performerId = performerRes.json().id;
        console.log(`Performer created: id=${performerId}`);
    }

    const sections = [];
    const sectionNames = ['A', 'B', 'C', 'D', 'E'];
    for (const name of sectionNames) {
        sections.push({ name: name, rows: 20, seatsPerRow: 25 });
    }

    const eventRes = http.post(`${BASE_URL}/api/events`, JSON.stringify({
        name: 'Stress Test Concert',
        description: 'Load test event for 1000 booking requests',
        eventStartTime: '2026-12-31T19:00:00',
        eventEndTime: '2026-12-31T22:00:00',
        venueId: venue.id,
        performerId: performerId,
        sections: sections,
    }), { headers: { 'Content-Type': 'application/json' } });

    check(eventRes, { 'event created': (r) => r.status === 201 });
    const event = eventRes.json();
    console.log(`Event created: id=${event.id}, sections=${sectionNames.length}, total seats=2500`);

    console.log('Waiting 20s for Kafka Streams section-init processing...');
    sleep(20);

    // Smoke test
    console.log('Smoke test: fire-and-forget POST...');
    const smokePost = http.post(`${BASE_URL}/api/bookings`, JSON.stringify({
        eventId: event.id, section: 'A', seatCount: 1, userId: 'smoke-test-user',
    }), { headers: { 'Content-Type': 'application/json' }, timeout: '5s' });
    console.log(`Smoke POST: status=${smokePost.status}, body=${smokePost.body}`);

    if (smokePost.status === 202) {
        const bookingId = smokePost.json().bookingId;
        console.log(`Smoke test bookingId: ${bookingId}, polling for result...`);

        let resolved = false;
        for (let i = 0; i < 50; i++) {
            sleep(0.2);
            const pollRes = http.get(`${BASE_URL}/api/bookings/${bookingId}`, { timeout: '5s' });
            if (pollRes.status === 200) {
                const body = pollRes.json();
                console.log(`Smoke poll result: status=${body.status}, seats=${JSON.stringify(body.allocatedSeats)}`);
                resolved = true;
                break;
            }
        }
        if (!resolved) {
            console.log('WARNING: Smoke test did not resolve within 10s. Waiting 15 more seconds...');
            sleep(15);
        }
    }

    return {
        eventId: event.id,
        sections: sectionNames,
    };
}

// Main test: fire-and-forget POST + poll GET
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

    // Step 1: Fire-and-forget POST
    const postStart = Date.now();
    const postRes = http.post(`${BASE_URL}/api/bookings`, payload, {
        headers: { 'Content-Type': 'application/json' },
        timeout: '5s',
    });
    postDuration.add(Date.now() - postStart);

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
    console.log('\n=== Load Test Complete ===');
    console.log(`Event ID: ${data.eventId}`);
    console.log(`Sections: ${data.sections.join(', ')}`);
    console.log(`Total seats: 2500 (5 sections x 500 seats)`);
    console.log(`Total requests: 1000`);
    console.log('Check post_duration (fire-and-forget) vs poll_duration (long-poll) vs e2e_duration');
}
