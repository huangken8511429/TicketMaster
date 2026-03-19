import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const postDuration = new Trend('post_duration', true);
const postSuccess = new Rate('post_success_rate');

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// Pure POST blast: measure fire-and-forget throughput
export const options = {
    scenarios: {
        post_blast: {
            executor: 'constant-vus',
            vus: parseInt(__ENV.VUS || '200'),
            duration: __ENV.DURATION || '15s',
        },
    },
};

// Reuse existing event (id=2 from previous test)
const EVENT_ID = parseInt(__ENV.EVENT_ID || '2');
const SECTIONS = ['A', 'B', 'C', 'D', 'E'];

export default function () {
    const section = SECTIONS[Math.floor(Math.random() * SECTIONS.length)];
    const payload = JSON.stringify({
        eventId: EVENT_ID,
        section: section,
        seatCount: 1,
        userId: `blast-${__VU}-${__ITER}`,
    });

    const start = Date.now();
    const res = http.post(`${BASE_URL}/api/bookings`, payload, {
        headers: { 'Content-Type': 'application/json' },
        timeout: '5s',
    });
    postDuration.add(Date.now() - start);
    postSuccess.add(res.status === 202);
}
