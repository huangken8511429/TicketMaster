// 100K request load test: ~100K total requests over ~3 minutes.
//
// Usage:
//   k6 run scripts/perf/k6/100k.js
//   k6 run scripts/perf/k6/100k.js -e PEAK_RPS=2000 -e NUM_SECTIONS=50
//
// Default plan (~100K requests):
//   Stage 1: warm-up     200 RPS × 20s  =   4,000
//   Stage 2: ramp-up     200→1000 × 30s =  18,000
//   Stage 3: sustain    1000 RPS × 60s  =  60,000
//   Stage 4: spike      1000→1500 × 15s =  18,750
//   Stage 5: cool-down  1500→0 × 15s    =  11,250
//   Total ≈ 112,000 requests

import { Trend, Counter } from 'k6/metrics';
import { createTestData } from './lib/setup.js';
import { reserveSeats } from './lib/reserve.js';

const reservationTime = new Trend('reservation_time', true);
const reservationCounter = new Counter('reservation_completed');

const PEAK_RPS = parseInt(__ENV.PEAK_RPS || '1500');
const MID_RPS = Math.floor(PEAK_RPS * 0.67);
const WARM_RPS = Math.floor(PEAK_RPS * 0.13);

export const options = {
  discardResponseBodies: false,
  scenarios: {
    load100k: {
      executor: 'ramping-arrival-rate',
      startRate: WARM_RPS,
      timeUnit: '1s',
      preAllocatedVUs: Math.min(PEAK_RPS * 3, 10000),
      maxVUs: Math.min(PEAK_RPS * 5, 15000),
      stages: [
        { target: WARM_RPS, duration: '20s' },   // warm-up
        { target: MID_RPS,  duration: '30s' },    // ramp to 67%
        { target: MID_RPS,  duration: '1m' },     // sustain 67%
        { target: PEAK_RPS, duration: '15s' },    // spike to 100%
        { target: 0,        duration: '15s' },    // cool-down
      ],
      gracefulStop: '60s',
    },
  },
  thresholds: {
    'http_req_duration{method:POST}': ['p(95)<500'],
    'http_req_duration{method:GET}':  ['p(95)<10000'],
    'checks': ['rate>0.95'],
  },
};

export function setup() {
  return createTestData();
}

export default function (data) {
  reserveSeats(data.eventId, data.sections, reservationTime, reservationCounter);
}
