// Shared configuration for all k6 tests.
// Override via environment variables: k6 run -e HOST_PORT=api.example.com:8080

export const HOST_PORT = __ENV.HOST_PORT || 'localhost:8080';
export const BASE_URL = `http://${HOST_PORT}`;
export const NUM_SECTIONS = parseInt(__ENV.NUM_SECTIONS || '20');

export const VENUE_NAME = 'k6-perf-venue';
export const EVENT_NAME = 'k6-perf-event';
export const EVENT_DESCRIPTION = 'k6 performance test event';

export const ROWS_PER_SECTION = 20;
export const SEATS_PER_ROW = 20;
// Total seats per section = ROWS_PER_SECTION * SEATS_PER_ROW = 400

export const HEADERS = {
  headers: { 'Content-Type': 'application/json' },
};
