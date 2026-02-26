// Test data setup: creates a venue + event with N sections.
// Returns { eventId, sections } for use by test scenarios.

import http from 'k6/http';
import { check, sleep } from 'k6';
import {
  BASE_URL,
  NUM_SECTIONS,
  VENUE_NAME,
  EVENT_NAME,
  EVENT_DESCRIPTION,
  ROWS_PER_SECTION,
  SEATS_PER_ROW,
  HEADERS,
} from './config.js';

export function createTestData() {
  // 1. Create venue
  const venuePayload = JSON.stringify({
    name: VENUE_NAME,
    address: '123 Performance Street',
    capacity: NUM_SECTIONS * ROWS_PER_SECTION * SEATS_PER_ROW,
  });

  const venueRes = http.post(`${BASE_URL}/api/venues`, venuePayload, HEADERS);
  const venueOk = check(venueRes, {
    'venue created (201)': (r) => r.status === 201,
  });
  if (!venueOk) {
    throw new Error(`Failed to create venue: ${venueRes.status} ${venueRes.body}`);
  }
  const venue = venueRes.json();
  const venueId = venue.id;

  // 2. Build sections array
  const sections = [];
  for (let i = 0; i < NUM_SECTIONS; i++) {
    sections.push({
      section: `S${i}`,
      rows: ROWS_PER_SECTION,
      seatsPerRow: SEATS_PER_ROW,
    });
  }

  // 3. Create event with sections
  const tomorrow = new Date();
  tomorrow.setDate(tomorrow.getDate() + 1);
  const eventDate = tomorrow.toISOString().split('T')[0]; // YYYY-MM-DD

  const eventPayload = JSON.stringify({
    name: EVENT_NAME,
    description: EVENT_DESCRIPTION,
    eventDate: eventDate,
    venueId: venueId,
    sections: sections,
  });

  const eventRes = http.post(`${BASE_URL}/api/events`, eventPayload, HEADERS);
  const eventOk = check(eventRes, {
    'event created (201)': (r) => r.status === 201,
  });
  if (!eventOk) {
    throw new Error(`Failed to create event: ${eventRes.status} ${eventRes.body}`);
  }
  const event = eventRes.json();

  // 4. Wait for Kafka Streams to initialize section-status
  sleep(3);

  return {
    eventId: event.id,
    sections: sections.map((s) => s.section),
  };
}
