// Smoke test: verifies the system is alive and the basic reservation flow works.
// Usage: k6 run scripts/perf/k6/smoke.js

import { Trend, Counter } from 'k6/metrics';
import { createTestData } from './lib/setup.js';
import { reserveSeats } from './lib/reserve.js';

const reservationTime = new Trend('reservation_time', true);
const reservationCounter = new Counter('reservation_completed');

export const options = {
  iterations: 20,
  vus: 1,
  thresholds: {
    checks: ['rate>0.9'],
  },
};

export function setup() {
  return createTestData();
}

export default function (data) {
  reserveSeats(data.eventId, data.sections, reservationTime, reservationCounter);
}
