/**
 * Stress Test — 穩定 RPS 壓測 (ramping-arrival-rate)
 *
 * 用法:
 *   k6 run scripts/perf/stress-test.js
 *   k6 run -e TICKET_COUNT=500 -e PEAK_RPS=5000 scripts/perf/stress-test.js
 *
 * 使用 ramping-arrival-rate: 固定 RPS 遞增，不受回應速度影響
 * 每次預訂: POST (fire) + GET (DeferredResult long-polling)
 */
import http from "k6/http";
import { check } from "k6";
import { Counter, Rate, Trend } from "k6/metrics";
import { setupTestData, reserveAndWait } from "./helpers.js";

const PEAK_RPS = Number(__ENV.PEAK_RPS || 3000);
const PRE_ALLOCATED_VUS = Number(__ENV.VUS || 5000);

export const options = {
  scenarios: {
    stress: {
      executor: "ramping-arrival-rate",
      startRate: Math.floor(PEAK_RPS / 5),
      timeUnit: "1s",
      preAllocatedVUs: PRE_ALLOCATED_VUS,
      stages: [
        { target: Math.floor(PEAK_RPS / 2), duration: "5s" },  // warm up
        { target: PEAK_RPS, duration: "10s" },                   // ramp to peak
        { target: PEAK_RPS, duration: "30s" },                   // sustain peak
        { target: Math.floor(PEAK_RPS / 2), duration: "5s" },   // cool down
      ],
      gracefulStop: "30s",
    },
  },
  thresholds: {
    "http_req_duration{name:POST_reservation}": ["p(95)<500"],
    "http_req_duration{name:GET_reservation}": ["p(95)<5000"],
    checks: ["rate>0.99"],
  },
  discardResponseBodies: false,
};

// Custom metrics
const confirmedCounter = new Counter("confirmed");
const rejectedCounter = new Counter("rejected");
const timeoutCounter = new Counter("timeout");
const confirmedRate = new Rate("confirmed_rate");
const e2eLatency = new Trend("e2e_latency", true);
const postLatency = new Trend("post_latency", true);
const getLatency = new Trend("get_latency", true);

// Seat tracking for duplicate detection
const seatMap = {};

export function setup() {
  return setupTestData();
}

export default function (data) {
  const userId = `stress-${__VU}-${__ITER}`;
  const result = reserveAndWait(data.eventId, "A", 2, userId);

  check(result, {
    "got final status": (r) =>
      r.status === "CONFIRMED" || r.status === "REJECTED",
  });

  postLatency.add(result.postDuration);
  getLatency.add(result.getDuration);
  e2eLatency.add(result.postDuration + result.getDuration);

  if (result.status === "CONFIRMED") {
    confirmedCounter.add(1);
    confirmedRate.add(true);
  } else if (result.status === "REJECTED") {
    rejectedCounter.add(1);
    confirmedRate.add(false);
  } else {
    timeoutCounter.add(1);
    confirmedRate.add(false);
  }
}

export function handleSummary(data) {
  const confirmed = data.metrics.confirmed ? data.metrics.confirmed.values.count : 0;
  const rejected = data.metrics.rejected ? data.metrics.rejected.values.count : 0;
  const timeouts = data.metrics.timeout ? data.metrics.timeout.values.count : 0;
  const total = confirmed + rejected + timeouts;

  const postP50 = data.metrics.post_latency ? data.metrics.post_latency.values["p(50)"] : "N/A";
  const postP95 = data.metrics.post_latency ? data.metrics.post_latency.values["p(95)"] : "N/A";
  const postP99 = data.metrics.post_latency ? data.metrics.post_latency.values["p(99)"] : "N/A";
  const getP50 = data.metrics.get_latency ? data.metrics.get_latency.values["p(50)"] : "N/A";
  const getP95 = data.metrics.get_latency ? data.metrics.get_latency.values["p(95)"] : "N/A";
  const getP99 = data.metrics.get_latency ? data.metrics.get_latency.values["p(99)"] : "N/A";
  const e2eP50 = data.metrics.e2e_latency ? data.metrics.e2e_latency.values["p(50)"] : "N/A";
  const e2eP95 = data.metrics.e2e_latency ? data.metrics.e2e_latency.values["p(95)"] : "N/A";
  const e2eP99 = data.metrics.e2e_latency ? data.metrics.e2e_latency.values["p(99)"] : "N/A";

  const rps = data.metrics.http_reqs ? data.metrics.http_reqs.values.rate : 0;
  const totalReqs = data.metrics.http_reqs ? data.metrics.http_reqs.values.count : 0;

  const fmt = (v) => (typeof v === "number" ? v.toFixed(1) : v);

  let s = "\n=== Stress Test (ramping-arrival-rate) ===\n";
  s += `  Peak target RPS: ${PEAK_RPS}\n`;
  s += `\n  Reservations:\n`;
  s += `    CONFIRMED: ${confirmed}\n`;
  s += `    REJECTED:  ${rejected}\n`;
  s += `    TIMEOUT:   ${timeouts}\n`;
  s += `    Total:     ${total}\n`;
  s += `\n  HTTP:\n`;
  s += `    Total requests: ${totalReqs} (POST + GET)\n`;
  s += `    Actual RPS:     ${fmt(rps)}\n`;
  s += `\n  POST latency:\n`;
  s += `    P50: ${fmt(postP50)}ms  P95: ${fmt(postP95)}ms  P99: ${fmt(postP99)}ms\n`;
  s += `\n  GET latency (long-polling):\n`;
  s += `    P50: ${fmt(getP50)}ms  P95: ${fmt(getP95)}ms  P99: ${fmt(getP99)}ms\n`;
  s += `\n  E2E latency (POST + GET):\n`;
  s += `    P50: ${fmt(e2eP50)}ms  P95: ${fmt(e2eP95)}ms  P99: ${fmt(e2eP99)}ms\n`;
  s += `==========================================\n`;

  return { stdout: s };
}
