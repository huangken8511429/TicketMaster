import http from "k6/http";
import { check, sleep } from "k6";
import { Counter, Rate, Trend } from "k6/metrics";

// ---------------------------------------------------------------------------
// Configuration
// ---------------------------------------------------------------------------
const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";
const EVENT_ID = __ENV.EVENT_ID;
if (!EVENT_ID) {
  throw new Error("EVENT_ID is required. Run: k6 run -e EVENT_ID=<id> scripts/load-test.js");
}

const SEAT_COUNT_PER_REQUEST = 2;
const POLL_INTERVAL_MS = 500;
const POLL_MAX_ATTEMPTS = 20; // 10s max wait

// ---------------------------------------------------------------------------
// k6 options
// ---------------------------------------------------------------------------
export const options = {
  scenarios: {
    rush: {
      executor: "shared-iterations",
      vus: 50,
      iterations: 50,
      maxDuration: "60s",
    },
  },
  thresholds: {
    http_req_duration: ["p(95)<5000", "p(99)<10000"],
    reservation_confirmed_rate: ["rate>0"],
    duplicate_seats: ["count==0"],
  },
};

// ---------------------------------------------------------------------------
// Custom metrics
// ---------------------------------------------------------------------------
const confirmedCounter = new Counter("reservations_confirmed");
const rejectedCounter = new Counter("reservations_rejected");
const timeoutCounter = new Counter("reservations_timeout");
const confirmedRate = new Rate("reservation_confirmed_rate");
const pollDuration = new Trend("poll_until_final_ms");
const duplicateSeats = new Counter("duplicate_seats");

// ---------------------------------------------------------------------------
// Main VU function
// ---------------------------------------------------------------------------
export default function () {
  const vuId = __VU;
  const userId = `user-${vuId}-${__ITER}`;

  // --- 1. Submit reservation (async, returns 202) ---
  const reservePayload = JSON.stringify({
    eventId: Number(EVENT_ID),
    section: "A",
    seatCount: SEAT_COUNT_PER_REQUEST,
    userId: userId,
  });

  const reserveRes = http.post(`${BASE_URL}/api/reservations`, reservePayload, {
    headers: { "Content-Type": "application/json" },
    tags: { name: "POST /api/reservations" },
  });

  const accepted = check(reserveRes, {
    "reserve: status is 202": (r) => r.status === 202,
    "reserve: has reservationId": (r) => {
      try {
        return JSON.parse(r.body).reservationId !== undefined;
      } catch (_) {
        return false;
      }
    },
  });

  if (!accepted) {
    console.error(`VU ${vuId}: reservation request failed — ${reserveRes.status} ${reserveRes.body}`);
    return;
  }

  const reservationId = JSON.parse(reserveRes.body).reservationId;

  // --- 2. Poll until final status (CONFIRMED / REJECTED) ---
  const pollStart = Date.now();
  let finalStatus = null;
  let allocatedSeats = [];

  for (let attempt = 0; attempt < POLL_MAX_ATTEMPTS; attempt++) {
    sleep(POLL_INTERVAL_MS / 1000);

    const pollRes = http.get(`${BASE_URL}/api/reservations/${reservationId}`, {
      tags: { name: "GET /api/reservations/{id}" },
    });

    if (pollRes.status !== 200) {
      continue;
    }

    const body = JSON.parse(pollRes.body);
    const status = body.status;

    if (status === "CONFIRMED" || status === "REJECTED") {
      finalStatus = status;
      allocatedSeats = body.allocatedSeats || [];
      break;
    }
  }

  const pollElapsed = Date.now() - pollStart;
  pollDuration.add(pollElapsed);

  check(finalStatus, {
    "poll: reached final status": (s) => s !== null,
  });

  if (finalStatus === "CONFIRMED") {
    confirmedCounter.add(1);
    confirmedRate.add(true);
    console.log(`VU ${vuId}: CONFIRMED — seats: ${allocatedSeats.join(", ")} (${pollElapsed}ms)`);
  } else if (finalStatus === "REJECTED") {
    rejectedCounter.add(1);
    confirmedRate.add(false);
    console.log(`VU ${vuId}: REJECTED (${pollElapsed}ms)`);
  } else {
    timeoutCounter.add(1);
    confirmedRate.add(false);
    console.warn(`VU ${vuId}: TIMEOUT — no final status after polling`);
  }
}

// ---------------------------------------------------------------------------
// Post-test summary: check for duplicate seat assignments
// ---------------------------------------------------------------------------
export function handleSummary(data) {
  // Fetch all tickets and check for duplicates via API
  const ticketsRes = http.get(`${BASE_URL}/api/tickets?eventId=${EVENT_ID}`);
  let duplicateCount = 0;
  let confirmedCount = 0;
  let summary = "\n=== Load Test Summary ===\n";

  if (ticketsRes && ticketsRes.status === 200) {
    const tickets = JSON.parse(ticketsRes.body);
    const reserved = tickets.filter((t) => t.status === "RESERVED" || t.status === "SOLD");
    const seatSet = new Set();

    for (const ticket of reserved) {
      if (seatSet.has(ticket.seatNumber)) {
        duplicateCount++;
        summary += `  DUPLICATE SEAT: ${ticket.seatNumber}\n`;
      }
      seatSet.add(ticket.seatNumber);
    }

    summary += `  Total tickets:    ${tickets.length}\n`;
    summary += `  Reserved/Sold:    ${reserved.length}\n`;
    summary += `  Still available:  ${tickets.length - reserved.length}\n`;
    summary += `  Duplicate seats:  ${duplicateCount}\n`;
  } else {
    summary += `  ERROR: Could not fetch ticket states (HTTP ${ticketsRes ? ticketsRes.status : "N/A"})\n`;
    summary += `  Unable to verify duplicate seats\n`;
  }

  // Extract custom metrics
  const confirmed = data.metrics.reservations_confirmed
    ? data.metrics.reservations_confirmed.values.count
    : 0;
  const rejected = data.metrics.reservations_rejected
    ? data.metrics.reservations_rejected.values.count
    : 0;
  const timeouts = data.metrics.reservations_timeout
    ? data.metrics.reservations_timeout.values.count
    : 0;
  const p95 = data.metrics.poll_until_final_ms
    ? data.metrics.poll_until_final_ms.values["p(95)"]
    : "N/A";
  const p99 = data.metrics.poll_until_final_ms
    ? data.metrics.poll_until_final_ms.values["p(99)"]
    : "N/A";

  summary += `\n  Reservations:\n`;
  summary += `    CONFIRMED: ${confirmed}\n`;
  summary += `    REJECTED:  ${rejected}\n`;
  summary += `    TIMEOUT:   ${timeouts}\n`;
  summary += `    Total:     ${confirmed + rejected + timeouts}\n`;
  summary += `\n  Poll latency:\n`;
  summary += `    P95: ${typeof p95 === "number" ? p95.toFixed(0) : p95}ms\n`;
  summary += `    P99: ${typeof p99 === "number" ? p99.toFixed(0) : p99}ms\n`;
  summary += `\n  Duplicate seats: ${duplicateCount} ${duplicateCount === 0 ? "(PASS)" : "(FAIL)"}\n`;
  summary += `=========================\n`;

  // Report duplicate count to threshold
  if (duplicateCount > 0) {
    duplicateSeats.add(duplicateCount);
  }

  return {
    stdout: summary,
  };
}
