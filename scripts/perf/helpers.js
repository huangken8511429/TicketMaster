import http from "k6/http";
import { check, sleep } from "k6";

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";
const TICKET_COUNT = Number(__ENV.TICKET_COUNT || 100);
const KAFKA_WAIT_SEC = Number(__ENV.KAFKA_WAIT || 3);

/**
 * Create venue + event + tickets in k6 setup().
 * Returns { eventId, ticketCount } for use in default().
 */
export function setupTestData() {
  // 1. Create venue
  const venueRes = http.post(
    `${BASE_URL}/api/venues`,
    JSON.stringify({
      name: "Perf Test Arena",
      address: "Load Test Blvd",
      capacity: 50000,
    }),
    { headers: { "Content-Type": "application/json" } }
  );
  check(venueRes, { "venue created": (r) => r.status === 201 });
  const venueId = JSON.parse(venueRes.body).id;

  // 2. Create event
  const eventRes = http.post(
    `${BASE_URL}/api/events`,
    JSON.stringify({
      name: `PerfTest-${Date.now()}`,
      description: "Auto-created by k6 perf test",
      eventDate: "2026-12-31",
      venueId: venueId,
    }),
    { headers: { "Content-Type": "application/json" } }
  );
  check(eventRes, { "event created": (r) => r.status === 201 });
  const eventId = JSON.parse(eventRes.body).id;

  // 3. Create tickets (A-001 ~ A-N)
  const batch = [];
  for (let i = 1; i <= TICKET_COUNT; i++) {
    const seatNumber = `A-${String(i).padStart(3, "0")}`;
    batch.push([
      "POST",
      `${BASE_URL}/api/tickets`,
      JSON.stringify({ eventId, seatNumber, price: 2800 }),
      { headers: { "Content-Type": "application/json" } },
    ]);
  }

  // Send in batches of 50 to avoid overwhelming
  const BATCH_SIZE = 50;
  let created = 0;
  for (let i = 0; i < batch.length; i += BATCH_SIZE) {
    const chunk = batch.slice(i, i + BATCH_SIZE);
    const responses = http.batch(chunk);
    for (const r of responses) {
      if (r.status === 201) created++;
    }
  }

  console.log(`Setup: venue=${venueId}, event=${eventId}, tickets=${created}/${TICKET_COUNT}`);

  // 4. Wait for Kafka Streams materialization
  sleep(KAFKA_WAIT_SEC);

  return { eventId, ticketCount: created };
}

/**
 * POST reservation + single long-polling GET.
 * Returns { status, allocatedSeats, postDuration, getDuration }
 */
export function reserveAndWait(eventId, section, seatCount, userId) {
  const postRes = http.post(
    `${BASE_URL}/api/reservations`,
    JSON.stringify({ eventId, section, seatCount, userId }),
    {
      headers: { "Content-Type": "application/json" },
      tags: { name: "POST_reservation" },
    }
  );

  if (postRes.status !== 202) {
    return { status: "POST_FAILED", allocatedSeats: [], postDuration: postRes.timings.duration, getDuration: 0 };
  }

  const reservationId = JSON.parse(postRes.body).reservationId;

  // Single GET — DeferredResult blocks until result is ready (up to 30s)
  const getRes = http.get(`${BASE_URL}/api/reservations/${reservationId}`, {
    tags: { name: "GET_reservation" },
    timeout: "35s",
  });

  if (getRes.status === 200) {
    const body = JSON.parse(getRes.body);
    return {
      status: body.status,
      allocatedSeats: body.allocatedSeats || [],
      postDuration: postRes.timings.duration,
      getDuration: getRes.timings.duration,
    };
  }

  // 202 means timeout (DeferredResult expired without result)
  return { status: "TIMEOUT", allocatedSeats: [], postDuration: postRes.timings.duration, getDuration: getRes.timings.duration };
}
