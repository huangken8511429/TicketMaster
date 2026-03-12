// Million-request load test: ramped stages from warm-up to peak.
// Uses ramping-arrival-rate to control exact RPS.
//
// Usage:
//   k6 run scripts/perf/k6/million.js
//   k6 run scripts/perf/k6/million.js -e PEAK_RPS=1500 -e NUM_SECTIONS=100
//
// Default plan (~1M total requests over ~12 minutes):
//   Stage 1: warm-up     200 RPS × 30s  =   6,000
//   Stage 2: ramp-up     200→1000 × 60s =  36,000
//   Stage 3: sustain    1000 RPS × 8m   = 480,000
//   Stage 4: spike      1000→1500 × 30s =  37,500
//   Stage 5: sustain    1500 RPS × 5m   = 450,000
//   Stage 6: cool-down  1500→0 × 30s    =  22,500
//   Total ≈ 1,032,000 requests

import { Trend, Counter } from 'k6/metrics';
import { createTestData } from './lib/setup.js';
import { reserveSeats } from './lib/reserve.js';

const reservationTime = new Trend('reservation_time', true);
const reservationCounter = new Counter('reservation_completed');

const PEAK_RPS = parseInt(__ENV.PEAK_RPS || '1500');
const MID_RPS = Math.floor(PEAK_RPS * 0.67);   // ~1000
const WARM_RPS = Math.floor(PEAK_RPS * 0.13);   // ~200

export const options = {
  discardResponseBodies: false,
  scenarios: {
    million: {
      executor: 'ramping-arrival-rate',
      startRate: WARM_RPS,
      timeUnit: '1s',
      preAllocatedVUs: Math.min(PEAK_RPS * 3, 10000),
      maxVUs: Math.min(PEAK_RPS * 5, 20000),
      stages: [
        { target: WARM_RPS, duration: '30s' },   // Stage 1: warm-up
        { target: MID_RPS,  duration: '1m' },     // Stage 2: ramp to 67%
        { target: MID_RPS,  duration: '8m' },     // Stage 3: sustain 67%
        { target: PEAK_RPS, duration: '30s' },    // Stage 4: spike to 100%
        { target: PEAK_RPS, duration: '5m' },     // Stage 5: sustain 100%
        { target: 0,        duration: '30s' },    // Stage 6: cool-down
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
