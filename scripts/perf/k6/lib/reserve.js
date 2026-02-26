// Reservation flow: POST to create, GET to poll result (DeferredResult, up to 30s).

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

    // 1. POST reservation (expect 202 Accepted)
    const postRes = http.post(`${BASE_URL}/api/reservations`, payload, HEADERS);
    const postOk = check(postRes, {
      'POST status is 202': (r) => r.status === 202,
    });

    if (!postOk) {
      return;
    }

    const body = postRes.json();
    const reservationId = body.reservationId;

    // 2. GET reservation result (DeferredResult long-poll, up to 30s)
    const getRes = http.get(`${BASE_URL}/api/reservations/${reservationId}`, {
      ...HEADERS,
      timeout: '35s',
    });
    const getOk = check(getRes, {
      'GET status is 200': (r) => r.status === 200,
    });

    if (getOk) {
      const reservation = getRes.json();
      check(reservation, {
        'reservation resolved': (r) => r.status === 'CONFIRMED' || r.status === 'FAILED',
      });

      const totalDuration = postRes.timings.duration + getRes.timings.duration;
      reservationTime.add(totalDuration);
      reservationCounter.add(1);
    }
  });
}
