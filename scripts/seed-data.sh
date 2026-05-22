#!/usr/bin/env bash
set -euo pipefail

API="${API:-http://localhost:8080/api}"

wait_for_api() {
  echo "→ Waiting for backend at $API ..."
  for i in {1..60}; do
    if curl -sf "$API/events" -o /dev/null 2>&1; then
      echo "  ✓ Backend up"
      return 0
    fi
    sleep 2
  done
  echo "  ✗ Backend did not respond in 120s" >&2
  exit 1
}

post() {
  local path="$1" body="$2"
  curl -sf -X POST "$API$path" \
    -H 'Content-Type: application/json' \
    -d "$body"
}

post_id() {
  post "$@" | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])'
}

wait_for_api

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Load seatMap JSON files and escape into a single-line JSON string for embedding.
load_seatmap() {
  python3 -c 'import json,sys; print(json.dumps(json.dumps(json.load(open(sys.argv[1])))))' "$1"
}

SEATMAP_V1=$(load_seatmap "$SCRIPT_DIR/seed/venues/taipei-arena.json")
SEATMAP_V2=$(load_seatmap "$SCRIPT_DIR/seed/venues/kaohsiung-dome.json")
SEATMAP_V3=$(load_seatmap "$SCRIPT_DIR/seed/venues/taipei-music-center.json")

echo "→ Creating venues"
V1=$(post_id /venues "$(printf '{"name":"台北小巨蛋","location":"台北市松山區","seatMap":%s}' "$SEATMAP_V1")")
V2=$(post_id /venues "$(printf '{"name":"高雄巨蛋","location":"高雄市左營區","seatMap":%s}' "$SEATMAP_V2")")
V3=$(post_id /venues "$(printf '{"name":"台北流行音樂中心","location":"台北市南港區","seatMap":%s}' "$SEATMAP_V3")")
echo "  venues: $V1 $V2 $V3"

echo "→ Creating performers"
P1=$(post_id /performers '{"name":"五月天","description":"Mayday — 台灣搖滾天團"}')
P2=$(post_id /performers '{"name":"周杰倫","description":"Jay Chou — 華語流行音樂教父"}')
P3=$(post_id /performers '{"name":"Taylor Swift","description":"The Eras Tour 世界巡迴"}')
P4=$(post_id /performers '{"name":"Coldplay","description":"Music of the Spheres World Tour"}')
echo "  performers: $P1 $P2 $P3 $P4"

# 售票時間：past = 已開賣（今天前一天），future_soon = 5 分鐘後（用來看倒數）
NOW_PAST=$(date -u -v-1d '+%Y-%m-%dT%H:%M:%S' 2>/dev/null || date -u -d '-1 day' '+%Y-%m-%dT%H:%M:%S')
NOW_SOON=$(date -u -v+5M '+%Y-%m-%dT%H:%M:%S' 2>/dev/null || date -u -d '+5 minutes' '+%Y-%m-%dT%H:%M:%S')
SHOW_1=$(date -u -v+30d '+%Y-%m-%dT19:30:00' 2>/dev/null || date -u -d '+30 days' '+%Y-%m-%dT19:30:00')
SHOW_2=$(date -u -v+45d '+%Y-%m-%dT20:00:00' 2>/dev/null || date -u -d '+45 days' '+%Y-%m-%dT20:00:00')
SHOW_3=$(date -u -v+60d '+%Y-%m-%dT19:00:00' 2>/dev/null || date -u -d '+60 days' '+%Y-%m-%dT19:00:00')
SHOW_4=$(date -u -v+90d '+%Y-%m-%dT19:00:00' 2>/dev/null || date -u -d '+90 days' '+%Y-%m-%dT19:00:00')
SHOW_5=$(date -u -v+120d '+%Y-%m-%dT19:30:00' 2>/dev/null || date -u -d '+120 days' '+%Y-%m-%dT19:30:00')

echo "→ Creating events"

# Event 1: 五月天 @ 小巨蛋（已開賣）
post /events "$(cat <<EOF
{
  "name": "五月天《回到那一天》25 週年巡迴 — 台北場",
  "description": "五月天 25 週年世界巡迴台北站，重溫經典與全新製作。",
  "eventStartTime": "$SHOW_1",
  "eventEndTime": "${SHOW_1%T*}T22:30:00",
  "venueId": $V1,
  "performerId": $P1,
  "salesStartAt": "$NOW_PAST",
  "sections": [
    {"name":"VIP","rows":10,"seatsPerRow":20},
    {"name":"搖滾A","rows":15,"seatsPerRow":30},
    {"name":"搖滾B","rows":15,"seatsPerRow":30},
    {"name":"看台紅","rows":20,"seatsPerRow":40},
    {"name":"看台藍","rows":20,"seatsPerRow":40},
    {"name":"看台黃","rows":25,"seatsPerRow":50}
  ]
}
EOF
)" > /dev/null && echo "  ✓ Event 1: 五月天"

# Event 2: 周杰倫 @ 高雄巨蛋（已開賣）
post /events "$(cat <<EOF
{
  "name": "周杰倫《嘉年華》世界巡迴 — 高雄站",
  "description": "Jay Chou Carnival World Tour 高雄站。",
  "eventStartTime": "$SHOW_2",
  "eventEndTime": "${SHOW_2%T*}T23:00:00",
  "venueId": $V2,
  "performerId": $P2,
  "salesStartAt": "$NOW_PAST",
  "sections": [
    {"name":"VIP","rows":8,"seatsPerRow":25},
    {"name":"A區","rows":20,"seatsPerRow":30},
    {"name":"B區","rows":20,"seatsPerRow":30},
    {"name":"C區","rows":25,"seatsPerRow":40}
  ]
}
EOF
)" > /dev/null && echo "  ✓ Event 2: 周杰倫"

# Event 3: Taylor Swift @ 小巨蛋（5 分鐘後開賣，可以看倒數）
post /events "$(cat <<EOF
{
  "name": "Taylor Swift The Eras Tour — Taipei Night",
  "description": "Taylor Swift 萬眾矚目的 Eras Tour 首次登台。",
  "eventStartTime": "$SHOW_3",
  "eventEndTime": "${SHOW_3%T*}T23:30:00",
  "venueId": $V1,
  "performerId": $P3,
  "salesStartAt": "$NOW_SOON",
  "sections": [
    {"name":"VIP Diamond","rows":5,"seatsPerRow":20},
    {"name":"VIP Gold","rows":10,"seatsPerRow":25},
    {"name":"Floor A","rows":15,"seatsPerRow":30},
    {"name":"Floor B","rows":15,"seatsPerRow":30},
    {"name":"看台 100","rows":20,"seatsPerRow":50},
    {"name":"看台 200","rows":25,"seatsPerRow":50}
  ]
}
EOF
)" > /dev/null && echo "  ✓ Event 3: Taylor Swift (5min countdown)"

# Event 4: Coldplay @ 流行音樂中心（已開賣）
post /events "$(cat <<EOF
{
  "name": "Coldplay Music of the Spheres — Taipei",
  "description": "Coldplay 太空音樂世界巡迴。",
  "eventStartTime": "$SHOW_4",
  "eventEndTime": "${SHOW_4%T*}T22:30:00",
  "venueId": $V3,
  "performerId": $P4,
  "salesStartAt": "$NOW_PAST",
  "sections": [
    {"name":"VIP","rows":8,"seatsPerRow":20},
    {"name":"Standing","rows":10,"seatsPerRow":40},
    {"name":"Seated A","rows":18,"seatsPerRow":30},
    {"name":"Seated B","rows":22,"seatsPerRow":35}
  ]
}
EOF
)" > /dev/null && echo "  ✓ Event 4: Coldplay"

# Event 5: 五月天加場（已開賣）
post /events "$(cat <<EOF
{
  "name": "五月天《回到那一天》25 週年巡迴 — 高雄加場",
  "description": "高雄場萬人催票，加開一場！",
  "eventStartTime": "$SHOW_5",
  "eventEndTime": "${SHOW_5%T*}T22:30:00",
  "venueId": $V2,
  "performerId": $P1,
  "salesStartAt": "$NOW_PAST",
  "sections": [
    {"name":"VIP","rows":10,"seatsPerRow":20},
    {"name":"搖滾","rows":20,"seatsPerRow":30},
    {"name":"看台 A","rows":22,"seatsPerRow":40},
    {"name":"看台 B","rows":22,"seatsPerRow":40}
  ]
}
EOF
)" > /dev/null && echo "  ✓ Event 5: 五月天高雄加場"

echo
echo "✅ Seed complete. Try: curl -s $API/events | python3 -m json.tool"
