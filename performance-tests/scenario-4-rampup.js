import http from 'k6/http';
import { check, group } from 'k6';

export const options = {
  stages: [
    { duration: '90s', target: 100 },   // Ramp-up: 0 → 100 users (→5K QPS)
    { duration: '90s', target: 200 },   // Ramp-up: 100 → 200 users (→10K QPS)
    { duration: '90s', target: 400 },   // Ramp-up: 200 → 400 users (→20K QPS)
    { duration: '90s', target: 700 },   // Ramp-up: 400 → 700 users (→35K QPS)
    { duration: '90s', target: 1400 },  // Ramp-up: 700 → 1400 users (→70K QPS)
    { duration: '60s', target: 0 },     // Ramp-down: 1400 → 0
  ],
  thresholds: {
    'http_req_duration': ['p(95)<1000', 'p(99)<3000'],  // P95 < 1s, P99 < 3s
    'http_req_failed': ['rate<0.05'],                    // Failure rate < 5%
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export default function () {
  const userId = `user_${__VU}_${__ITER}`;

  // Query available seats (fast path, to be cached)
  group('Query Available Seats', () => {
    const res = http.get(
      `${BASE_URL}/api/tickets/available?eventId=1`,
      {
        tags: { name: 'QuerySeats' },
      }
    );

    check(res, {
      'query status 200': (r) => r.status === 200,
      'query time < 100ms': (r) => r.timings.duration < 100,
    });
  });

  // Submit reservation (main path, no sleep — let k6 control rate)
  group('Submit Reservation', () => {
    const payload = JSON.stringify({
      userId: userId,
      eventId: 1,
      section: 'VIP',
      seatCount: 1,
    });

    const res = http.post(`${BASE_URL}/api/reservations`, payload, {
      headers: { 'Content-Type': 'application/json' },
      tags: { name: 'BookTicket' },
    });

    check(res, {
      'booking status 200-202': (r) => [200, 202].includes(r.status),
      'has reservationId': (r) => {
        try {
          return r.json('reservationId') !== undefined;
        } catch {
          return false;
        }
      },
    });
  });
}
