import http from "k6/http";
import { check } from "k6";
import { Counter, Rate } from "k6/metrics";

// ---------------------------------------------------------------------------
// Configuration — round-robin across multiple instances
// ---------------------------------------------------------------------------
const ENDPOINTS = (__ENV.ENDPOINTS || "http://localhost:8080,http://localhost:8082,http://localhost:8083").split(",");
const EVENT_ID = __ENV.EVENT_ID;
if (!EVENT_ID) {
  throw new Error("EVENT_ID is required. Run: k6 run -e EVENT_ID=<id> scripts/load-test-fire-scaled.js");
}

const VUS = Number(__ENV.VUS || 500);
const ITERATIONS = Number(__ENV.ITERATIONS || 100000);

// ---------------------------------------------------------------------------
// k6 options
// ---------------------------------------------------------------------------
export const options = {
  scenarios: {
    rush: {
      executor: "shared-iterations",
      vus: VUS,
      iterations: ITERATIONS,
      maxDuration: "5m",
    },
  },
  thresholds: {
    http_req_duration: ["p(95)<5000"],
    "http_req_duration{name:POST_reservation}": ["p(95)<1000"],
  },
};

// ---------------------------------------------------------------------------
// Custom metrics
// ---------------------------------------------------------------------------
const acceptedCounter = new Counter("reservations_accepted");
const failedCounter = new Counter("reservations_failed");
const acceptedRate = new Rate("reservation_accepted_rate");

// ---------------------------------------------------------------------------
// Main VU function — direct round-robin across instances
// ---------------------------------------------------------------------------
export default function () {
  const userId = `user-${__VU}-${__ITER}`;
  const baseUrl = ENDPOINTS[__VU % ENDPOINTS.length];

  const res = http.post(
    `${baseUrl}/api/reservations`,
    JSON.stringify({
      eventId: Number(EVENT_ID),
      section: "A",
      seatCount: 2,
      userId: userId,
    }),
    {
      headers: { "Content-Type": "application/json" },
      tags: { name: "POST_reservation" },
    }
  );

  const ok = check(res, {
    "status is 202": (r) => r.status === 202,
  });

  if (ok) {
    acceptedCounter.add(1);
    acceptedRate.add(true);
  } else {
    failedCounter.add(1);
    acceptedRate.add(false);
  }
}

// ---------------------------------------------------------------------------
// Summary
// ---------------------------------------------------------------------------
export function handleSummary(data) {
  const accepted = data.metrics.reservations_accepted
    ? data.metrics.reservations_accepted.values.count
    : 0;
  const failed = data.metrics.reservations_failed
    ? data.metrics.reservations_failed.values.count
    : 0;

  const reqs = data.metrics.http_reqs ? data.metrics.http_reqs.values.count : 0;
  const rps = data.metrics.http_reqs ? data.metrics.http_reqs.values.rate : 0;

  const p50 = data.metrics.http_req_duration
    ? data.metrics.http_req_duration.values["p(50)"]
    : "N/A";
  const p95 = data.metrics.http_req_duration
    ? data.metrics.http_req_duration.values["p(95)"]
    : "N/A";
  const p99 = data.metrics.http_req_duration
    ? data.metrics.http_req_duration.values["p(99)"]
    : "N/A";

  let summary = "\n=== Scaled Fire & Forget Load Test ===\n";
  summary += `  Endpoints:   ${ENDPOINTS.join(", ")}\n`;
  summary += `  Iterations:  ${accepted + failed}\n`;
  summary += `  Accepted:    ${accepted} (HTTP 202)\n`;
  summary += `  Failed:      ${failed}\n`;
  summary += `\n  Throughput:\n`;
  summary += `    Total reqs: ${reqs}\n`;
  summary += `    RPS:        ${typeof rps === "number" ? rps.toFixed(1) : rps}\n`;
  summary += `\n  Latency (POST only):\n`;
  summary += `    P50: ${typeof p50 === "number" ? p50.toFixed(1) : p50}ms\n`;
  summary += `    P95: ${typeof p95 === "number" ? p95.toFixed(1) : p95}ms\n`;
  summary += `    P99: ${typeof p99 === "number" ? p99.toFixed(1) : p99}ms\n`;
  summary += `=====================================\n`;

  return { stdout: summary };
}
