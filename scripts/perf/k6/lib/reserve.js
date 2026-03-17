// Reservation flow: single POST that blocks until Kafka Streams returns the result.
// No separate GET polling needed — result comes back in the same HTTP response.

import http from 'k6/http';
import { check, group } from 'k6';
import { randomIntBetween } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';
import { BASE_URL, HEADERS } from './config.js';

export function reserveSeats(eventId, sections, reservationTime, reservationCounter) {
  group('reserve seats', function () {
    const sectionIdx = Math.floor(Math.random() * sections.length);
    const section = sections[sectionIdx];
    const seatCount = randomIntBetween(1, 4);

    const payload = JSON.stringify({
      eventId: eventId,
      section: section,
      seatCount: seatCount,
      userId: `k6-user-${__VU}-${__ITER}`,
    });

    // Single POST: blocks until Kafka Streams processes the reservation
    const res = http.post(`${BASE_URL}/api/bookings`, payload, {
      ...HEADERS,
      timeout: '15s',
    });

    const ok = check(res, {
      'POST status is 200': (r) => r.status === 200,
    });

    if (ok) {
      const reservation = res.json();
      check(reservation, {
        'reservation resolved': (r) => r.status === 'CONFIRMED' || r.status === 'REJECTED',
      });

      reservationTime.add(res.timings.duration);
      reservationCounter.add(1);
    }
  });
}
