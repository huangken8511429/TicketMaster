// Stress test: sustained high-throughput using ramping-arrival-rate.
// Usage: k6 run scripts/perf/k6/stress.js -e PEAK_RPS=3000

import { Trend, Counter } from 'k6/metrics';
import { createTestData } from './lib/setup.js';
import { reserveSeats } from './lib/reserve.js';

const reservationTime = new Trend('reservation_time', true);
const reservationCounter = new Counter('reservation_completed');

const PEAK_RPS = parseInt(__ENV.PEAK_RPS || '3000');
const HALF_RPS = Math.floor(PEAK_RPS / 2);

export const options = {
  discardResponseBodies: false,
  scenarios: {
    stress: {
      executor: 'ramping-arrival-rate',
      startRate: HALF_RPS,
      timeUnit: '1s',
      preAllocatedVUs: Math.min(PEAK_RPS * 2, 10000),
      stages: [
        { target: HALF_RPS, duration: '30s' },  // warm-up at 50%
        { target: PEAK_RPS, duration: '30s' },   // ramp to 100%
        { target: PEAK_RPS, duration: '2m' },    // sustain at 100%
        { target: HALF_RPS, duration: '30s' },   // cool-down to 50%
      ],
      gracefulStop: '30s',
    },
  },
  thresholds: {
    'http_req_duration{method:POST}': ['p(95)<500'],
    'http_req_duration{method:GET}': ['p(95)<5000'],
    checks: ['rate>0.99'],
  },
};

export function setup() {
  return createTestData();
}

export default function (data) {
  reserveSeats(data.eventId, data.sections, reservationTime, reservationCounter);
}
