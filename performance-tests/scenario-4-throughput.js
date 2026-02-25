import http from 'k6/http';
import { check, group } from 'k6';

export const options = {
  stages: [
    { duration: '60s', target: 1400 },  // Ramp-up to 1400 users (→70K QPS)
    { duration: '120s', target: 1400 }, // Sustain 1400 users for 2 minutes
    { duration: '30s', target: 0 },     // Ramp-down
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
    });
  });

  // Submit reservation (main path, no sleep)
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
    });
  });
}
