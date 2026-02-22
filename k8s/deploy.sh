#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
NAMESPACE="ticketmaster"
IMAGE_NAME="ticketmaster:latest"

echo "=== TicketMaster K8s Deploy ==="

# --- Build Docker image ---
echo "[1/4] Building Docker image..."
docker build -t "$IMAGE_NAME" "$PROJECT_DIR"

# If using minikube, load image into minikube's Docker daemon
if command -v minikube &> /dev/null && minikube status &> /dev/null; then
    echo "  Loading image into minikube..."
    minikube image load "$IMAGE_NAME"
fi

# --- Create namespace ---
echo "[2/4] Creating namespace..."
kubectl apply -f "$SCRIPT_DIR/namespace.yaml"

# --- Deploy infrastructure ---
echo "[3/4] Deploying infrastructure (Kafka, PostgreSQL, Redis, Schema Registry)..."
kubectl apply -f "$SCRIPT_DIR/infra/"

echo "  Waiting for infra pods to be ready..."
kubectl -n "$NAMESPACE" wait --for=condition=ready pod -l app=postgres --timeout=120s
kubectl -n "$NAMESPACE" wait --for=condition=ready pod -l app=redis --timeout=60s
kubectl -n "$NAMESPACE" wait --for=condition=ready pod -l app=kafka --timeout=120s
kubectl -n "$NAMESPACE" wait --for=condition=ready pod -l app=schema-registry --timeout=120s

# --- Deploy application ---
echo "[4/4] Deploying application (API x5, seat-processor x5, reservation-processor x5)..."
kubectl apply -f "$SCRIPT_DIR/app/"

echo "  Waiting for app pods to be ready..."
kubectl -n "$NAMESPACE" wait --for=condition=ready pod -l app=reservation-processor --timeout=180s
kubectl -n "$NAMESPACE" wait --for=condition=ready pod -l app=seat-processor --timeout=180s
kubectl -n "$NAMESPACE" wait --for=condition=ready pod -l app=api --timeout=180s

# --- Summary ---
echo ""
echo "=== Deploy Complete ==="
kubectl -n "$NAMESPACE" get pods -o wide
echo ""
echo "API endpoint:"
kubectl -n "$NAMESPACE" get svc api
echo ""
echo "=== Load Test Instructions ==="
echo ""
echo "Step 1: Create an event with multiple sections (spread load across partitions)"
echo "  API_URL=\$(minikube service api -n $NAMESPACE --url 2>/dev/null || echo 'http://localhost:8080')"
echo ""
echo "  # Create venue"
echo "  curl -s -X POST \$API_URL/api/venues \\"
echo "    -H 'Content-Type: application/json' \\"
echo "    -d '{\"name\":\"Taipei Arena\",\"address\":\"Taipei\",\"capacity\":100000}'"
echo ""
echo "  # Create event with 20 sections x 500 seats = 10,000 seats (uses 20 partitions)"
echo "  curl -s -X POST \$API_URL/api/events \\"
echo "    -H 'Content-Type: application/json' \\"
echo "    -d '{\"name\":\"Concert\",\"description\":\"Load Test\",\"eventDate\":\"2026-12-01\",\"venueId\":1,"
echo "         \"sections\":[{\"section\":\"A\",\"rows\":50,\"seatsPerRow\":10},"
echo "                      {\"section\":\"B\",\"rows\":50,\"seatsPerRow\":10},"
echo "                      {\"section\":\"C\",\"rows\":50,\"seatsPerRow\":10},"
echo "                      {\"section\":\"D\",\"rows\":50,\"seatsPerRow\":10},"
echo "                      {\"section\":\"E\",\"rows\":50,\"seatsPerRow\":10},"
echo "                      {\"section\":\"F\",\"rows\":50,\"seatsPerRow\":10},"
echo "                      {\"section\":\"G\",\"rows\":50,\"seatsPerRow\":10},"
echo "                      {\"section\":\"H\",\"rows\":50,\"seatsPerRow\":10},"
echo "                      {\"section\":\"I\",\"rows\":50,\"seatsPerRow\":10},"
echo "                      {\"section\":\"J\",\"rows\":50,\"seatsPerRow\":10},"
echo "                      {\"section\":\"K\",\"rows\":50,\"seatsPerRow\":10},"
echo "                      {\"section\":\"L\",\"rows\":50,\"seatsPerRow\":10},"
echo "                      {\"section\":\"M\",\"rows\":50,\"seatsPerRow\":10},"
echo "                      {\"section\":\"N\",\"rows\":50,\"seatsPerRow\":10},"
echo "                      {\"section\":\"O\",\"rows\":50,\"seatsPerRow\":10},"
echo "                      {\"section\":\"P\",\"rows\":50,\"seatsPerRow\":10},"
echo "                      {\"section\":\"Q\",\"rows\":50,\"seatsPerRow\":10},"
echo "                      {\"section\":\"R\",\"rows\":50,\"seatsPerRow\":10},"
echo "                      {\"section\":\"S\",\"rows\":50,\"seatsPerRow\":10},"
echo "                      {\"section\":\"T\",\"rows\":50,\"seatsPerRow\":10}]}'"
echo ""
echo "  sleep 5  # Wait for section-init to propagate"
echo ""
echo "Step 2: Run load test (distribute across sections for partition parallelism)"
echo "  # Install: brew install hey"
echo ""
echo "  # Single section (worst case — all requests hit 1 partition, 1 seat-processor)"
echo "  hey -n 1000000 -c 500 -m POST -H 'Content-Type: application/json' \\"
echo "    -d '{\"eventId\":1,\"section\":\"A\",\"seatCount\":1,\"userId\":\"user-1\"}' \\"
echo "    \$API_URL/api/reservations"
echo ""
echo "  # Multi-section (best case — spread across 20 sections → 20 partitions → 5 processors)"
echo "  # Use a script to randomize section per request:"
echo "  for i in \$(seq 1 1000000); do"
echo "    SECTION=\$(echo -e 'A\nB\nC\nD\nE\nF\nG\nH\nI\nJ\nK\nL\nM\nN\nO\nP\nQ\nR\nS\nT' | shuf -n1)"
echo "    curl -s -X POST \$API_URL/api/reservations \\"
echo "      -H 'Content-Type: application/json' \\"
echo "      -d \"{\\\"eventId\\\":1,\\\"section\\\":\\\"\$SECTION\\\",\\\"seatCount\\\":1,\\\"userId\\\":\\\"user-\$i\\\"}\" &"
echo "    [[ \$((i % 500)) -eq 0 ]] && wait"
echo "  done"
echo ""
echo "Step 3: Monitor"
echo "  watch kubectl -n $NAMESPACE top pods"
echo "  kubectl -n $NAMESPACE logs -l app=seat-processor --tail=100 -f"
