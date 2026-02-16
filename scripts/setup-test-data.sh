#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
TICKET_COUNT="${TICKET_COUNT:-100}"
PRICE="${PRICE:-2800}"
KAFKA_WAIT="${KAFKA_WAIT:-3}"

echo "=== TicketMaster Load Test Data Setup ==="
echo "Base URL: ${BASE_URL}"
echo "Tickets:  ${TICKET_COUNT}"
echo ""

# Check dependencies
if ! command -v jq &>/dev/null; then
  echo "ERROR: jq is required. Install with: brew install jq"
  exit 1
fi

if ! curl -sf "${BASE_URL}/api/venues" -o /dev/null 2>/dev/null; then
  echo "ERROR: Application not reachable at ${BASE_URL}"
  echo "Start it first: ./gradlew bootRun"
  exit 1
fi

# 1. Create venue
echo "[1/4] Creating venue..."
VENUE_RESPONSE=$(curl -sf -X POST "${BASE_URL}/api/venues" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Taipei Arena",
    "address": "No. 2, Nanjing East Road, Taipei",
    "capacity": 15000
  }')

VENUE_ID=$(echo "${VENUE_RESPONSE}" | jq -r '.id')
echo "  Venue created: id=${VENUE_ID}"

# 2. Create event
echo "[2/4] Creating event..."
EVENT_RESPONSE=$(curl -sf -X POST "${BASE_URL}/api/events" \
  -H "Content-Type: application/json" \
  -d "{
    \"name\": \"Load Test Concert\",
    \"description\": \"High concurrency load test event\",
    \"eventDate\": \"2026-06-15\",
    \"venueId\": ${VENUE_ID}
  }")

EVENT_ID=$(echo "${EVENT_RESPONSE}" | jq -r '.id')
echo "  Event created: id=${EVENT_ID}"

# 3. Create tickets (A-001 ~ A-100)
echo "[3/4] Creating ${TICKET_COUNT} tickets..."
FAIL_COUNT=0
for i in $(seq 1 "${TICKET_COUNT}"); do
  SEAT_NUM=$(printf "A-%03d" "${i}")
  HTTP_CODE=$(curl -sf -o /dev/null -w "%{http_code}" -X POST "${BASE_URL}/api/tickets" \
    -H "Content-Type: application/json" \
    -d "{
      \"eventId\": ${EVENT_ID},
      \"seatNumber\": \"${SEAT_NUM}\",
      \"price\": ${PRICE}
    }")

  if [ "${HTTP_CODE}" != "201" ]; then
    FAIL_COUNT=$((FAIL_COUNT + 1))
  fi

  # Progress indicator every 10 tickets
  if [ $((i % 10)) -eq 0 ]; then
    echo "  Created ${i}/${TICKET_COUNT} tickets..."
  fi
done

if [ "${FAIL_COUNT}" -gt 0 ]; then
  echo "  WARNING: ${FAIL_COUNT}/${TICKET_COUNT} tickets failed to create"
  if [ "${FAIL_COUNT}" -gt $((TICKET_COUNT / 5)) ]; then
    echo "ERROR: Too many failures (>${TICKET_COUNT}/5). Aborting."
    exit 1
  fi
fi

# 4. Wait for Kafka Streams to materialize seat states
echo "[4/4] Waiting ${KAFKA_WAIT}s for Kafka Streams materialization..."
sleep "${KAFKA_WAIT}"

# Verify available tickets
AVAILABLE=$(curl -sf "${BASE_URL}/api/tickets/available?eventId=${EVENT_ID}" | jq 'length')
echo ""
echo "=== Setup Complete ==="
echo "  Event ID:         ${EVENT_ID}"
echo "  Available tickets: ${AVAILABLE}/${TICKET_COUNT}"
echo ""
echo "Run the load test with:"
echo "  k6 run -e EVENT_ID=${EVENT_ID} scripts/load-test.js"
