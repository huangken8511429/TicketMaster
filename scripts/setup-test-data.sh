#!/usr/bin/env bash
# setup-test-data.sh — Create venue + event in a running TicketMaster instance.
# Usage: BASE_URL=http://api.example.com:8080 NUM_SECTIONS=20 ./scripts/setup-test-data.sh

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
NUM_SECTIONS="${NUM_SECTIONS:-20}"
ROWS=20
SEATS_PER_ROW=20

echo "=== TicketMaster Test Data Setup ==="
echo "  BASE_URL:     $BASE_URL"
echo "  NUM_SECTIONS: $NUM_SECTIONS"
echo ""

# 1. Create venue
CAPACITY=$(( NUM_SECTIONS * ROWS * SEATS_PER_ROW ))
VENUE_RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/venues" \
  -H "Content-Type: application/json" \
  -d "{
    \"name\": \"perf-venue-$(date +%s)\",
    \"address\": \"Setup Script\",
    \"capacity\": $CAPACITY
  }")

VENUE_HTTP_CODE=$(echo "$VENUE_RESPONSE" | tail -1)
VENUE_BODY=$(echo "$VENUE_RESPONSE" | sed '$d')

if [ "$VENUE_HTTP_CODE" != "201" ]; then
  echo "ERROR: Failed to create venue (HTTP $VENUE_HTTP_CODE)"
  echo "$VENUE_BODY"
  exit 1
fi

VENUE_ID=$(echo "$VENUE_BODY" | grep -o '"id":[0-9]*' | head -1 | cut -d: -f2)
echo "Venue created: id=$VENUE_ID"

# 2. Build sections JSON
SECTIONS="["
for (( i=0; i<NUM_SECTIONS; i++ )); do
  if [ "$i" -gt 0 ]; then SECTIONS+=","; fi
  SECTIONS+="{\"section\":\"S${i}\",\"rows\":${ROWS},\"seatsPerRow\":${SEATS_PER_ROW}}"
done
SECTIONS+="]"

# 3. Create event
TOMORROW=$(date -v+1d +%Y-%m-%d 2>/dev/null || date -d "+1 day" +%Y-%m-%d)
EVENT_RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/events" \
  -H "Content-Type: application/json" \
  -d "{
    \"name\": \"perf-event-$(date +%s)\",
    \"description\": \"Performance test event\",
    \"eventDate\": \"$TOMORROW\",
    \"venueId\": $VENUE_ID,
    \"sections\": $SECTIONS
  }")

EVENT_HTTP_CODE=$(echo "$EVENT_RESPONSE" | tail -1)
EVENT_BODY=$(echo "$EVENT_RESPONSE" | sed '$d')

if [ "$EVENT_HTTP_CODE" != "201" ]; then
  echo "ERROR: Failed to create event (HTTP $EVENT_HTTP_CODE)"
  echo "$EVENT_BODY"
  exit 1
fi

EVENT_ID=$(echo "$EVENT_BODY" | grep -o '"id":[0-9]*' | head -1 | cut -d: -f2)
echo "Event created: id=$EVENT_ID"

# 4. Wait for Kafka Streams to initialize
echo "Waiting 3 seconds for Kafka Streams initialization..."
sleep 3

echo ""
echo "=== Setup Complete ==="
echo "  EVENT_ID=$EVENT_ID"
echo ""
echo "Run k6 tests:"
echo "  k6 run -e HOST_PORT=${BASE_URL#http://} scripts/perf/k6/smoke.js"
echo ""
echo "Run Go spike test:"
echo "  cd scripts/perf/go-client && go run . --host ${BASE_URL#http://} -n 1000 -a $NUM_SECTIONS"
