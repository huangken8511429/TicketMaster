/**
 * Smoke Test — 驗證基本流程正確性
 *
 * 用法: k6 run scripts/perf/smoke-test.js
 *
 * 1 VU, 20 iterations, 驗證 POST+GET long-polling 流程
 */
import { Counter, Rate, Trend } from "k6/metrics";
import { check } from "k6";
import { setupTestData, reserveAndWait } from "./helpers.js";

export const options = {
  scenarios: {
    smoke: {
      executor: "shared-iterations",
      vus: 1,
      iterations: 20,
      maxDuration: "2m",
    },
  },
  thresholds: {
    checks: ["rate==1"],
  },
};

const confirmedCounter = new Counter("confirmed");
const rejectedCounter = new Counter("rejected");
const e2eLatency = new Trend("e2e_latency", true);

export function setup() {
  return setupTestData();
}

export default function (data) {
  const userId = `smoke-${__VU}-${__ITER}`;
  const result = reserveAndWait(data.eventId, "A", 2, userId);

  check(result, {
    "got final status": (r) => r.status === "CONFIRMED" || r.status === "REJECTED",
  });

  e2eLatency.add(result.postDuration + result.getDuration);

  if (result.status === "CONFIRMED") {
    confirmedCounter.add(1);
    check(result, {
      "has allocated seats": (r) => r.allocatedSeats.length === 2,
    });
  } else if (result.status === "REJECTED") {
    rejectedCounter.add(1);
  }
}

export function handleSummary(data) {
  const confirmed = data.metrics.confirmed ? data.metrics.confirmed.values.count : 0;
  const rejected = data.metrics.rejected ? data.metrics.rejected.values.count : 0;
  const p50 = data.metrics.e2e_latency ? data.metrics.e2e_latency.values["p(50)"] : "N/A";
  const p95 = data.metrics.e2e_latency ? data.metrics.e2e_latency.values["p(95)"] : "N/A";
  const checks = data.metrics.checks ? data.metrics.checks.values.passes : 0;
  const checkFails = data.metrics.checks ? data.metrics.checks.values.fails : 0;

  let s = "\n=== Smoke Test ===\n";
  s += `  CONFIRMED: ${confirmed}\n`;
  s += `  REJECTED:  ${rejected}\n`;
  s += `  Checks:    ${checks} passed, ${checkFails} failed\n`;
  s += `  E2E P50:   ${typeof p50 === "number" ? p50.toFixed(1) : p50}ms\n`;
  s += `  E2E P95:   ${typeof p95 === "number" ? p95.toFixed(1) : p95}ms\n`;
  s += `  Result:    ${checkFails === 0 ? "PASS" : "FAIL"}\n`;
  s += `====================\n`;
  return { stdout: s };
}
