// Spike Test Client — 瞬間爆量壓測
//
// 用法:
//   cd scripts/perf/go-client
//   go run main.go -host http://localhost:8080 -n 100000 -c 4 -seats 100
//
// Flags:
//   -host     Base URL (default: http://localhost:8080)
//   -n        Number of concurrent reservations (default: 100000)
//   -c        Number of HTTP client pool size (default: 4)
//   -seats    Number of tickets to create (default: 100)
//   -section  Section name (default: A)
//   -seatCount Seats per reservation (default: 2)
//   -sleep    Sleep between POST and GET in ms (default: 100)

package main

import (
	"bytes"
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"math"
	"net/http"
	"os"
	"sort"
	"sync"
	"time"

	"golang.org/x/net/http2"
)

// --- Data Structures ---

type VenueRequest struct {
	Name     string `json:"name"`
	Address  string `json:"address"`
	Capacity int    `json:"capacity"`
}

type VenueResponse struct {
	ID int64 `json:"id"`
}

type EventRequest struct {
	Name        string `json:"name"`
	Description string `json:"description"`
	EventDate   string `json:"eventDate"`
	VenueID     int64  `json:"venueId"`
}

type EventResponse struct {
	ID int64 `json:"id"`
}

type TicketRequest struct {
	EventID    int64  `json:"eventId"`
	SeatNumber string `json:"seatNumber"`
	Price      int    `json:"price"`
}

type ReservationRequest struct {
	EventID   int64  `json:"eventId"`
	Section   string `json:"section"`
	SeatCount int    `json:"seatCount"`
	UserID    string `json:"userId"`
}

type ReservationPostResponse struct {
	ReservationID string `json:"reservationId"`
}

type ReservationGetResponse struct {
	ReservationID  string   `json:"reservationId"`
	EventID        int64    `json:"eventId"`
	Status         string   `json:"status"`
	AllocatedSeats []string `json:"allocatedSeats"`
}

type Result struct {
	Status         string
	AllocatedSeats []string
	PostDuration   time.Duration
	GetDuration    time.Duration
	Error          string
}

// --- HTTP Client Pool ---

type ClientPool struct {
	clients []*http.Client
	idx     int
	mu      sync.Mutex
}

func NewClientPool(size int, useHTTP2 bool) *ClientPool {
	pool := &ClientPool{clients: make([]*http.Client, size)}
	for i := 0; i < size; i++ {
		transport := &http.Transport{
			MaxIdleConns:        10000,
			MaxIdleConnsPerHost: 10000,
			MaxConnsPerHost:     10000,
			IdleConnTimeout:     90 * time.Second,
		}
		if useHTTP2 {
			_ = http2.ConfigureTransport(transport)
		}
		pool.clients[i] = &http.Client{
			Transport: transport,
			Timeout:   60 * time.Second,
		}
	}
	return pool
}

func (p *ClientPool) Get() *http.Client {
	p.mu.Lock()
	defer p.mu.Unlock()
	c := p.clients[p.idx%len(p.clients)]
	p.idx++
	return c
}

// --- Setup ---

func setupTestData(host string, client *http.Client, numSeats int) int64 {
	// Create venue
	venueBody, _ := json.Marshal(VenueRequest{Name: "Spike Arena", Address: "Spike Blvd", Capacity: 50000})
	resp, err := client.Post(host+"/api/venues", "application/json", bytes.NewReader(venueBody))
	if err != nil || resp.StatusCode != 201 {
		log.Fatalf("Failed to create venue: %v (status: %d)", err, resp.StatusCode)
	}
	var venue VenueResponse
	json.NewDecoder(resp.Body).Decode(&venue)
	resp.Body.Close()

	// Create event
	eventBody, _ := json.Marshal(EventRequest{
		Name:        fmt.Sprintf("SpikeTest-%d", time.Now().UnixMilli()),
		Description: "Go spike test",
		EventDate:   "2026-12-31",
		VenueID:     venue.ID,
	})
	resp, err = client.Post(host+"/api/events", "application/json", bytes.NewReader(eventBody))
	if err != nil || resp.StatusCode != 201 {
		log.Fatalf("Failed to create event: %v", err)
	}
	var event EventResponse
	json.NewDecoder(resp.Body).Decode(&event)
	resp.Body.Close()

	// Create tickets concurrently
	var wg sync.WaitGroup
	created := 0
	var mu sync.Mutex
	sem := make(chan struct{}, 50) // concurrency limiter

	for i := 1; i <= numSeats; i++ {
		wg.Add(1)
		sem <- struct{}{}
		go func(idx int) {
			defer wg.Done()
			defer func() { <-sem }()

			seatNumber := fmt.Sprintf("A-%03d", idx)
			body, _ := json.Marshal(TicketRequest{EventID: event.ID, SeatNumber: seatNumber, Price: 2800})
			r, e := client.Post(host+"/api/tickets", "application/json", bytes.NewReader(body))
			if e == nil && r.StatusCode == 201 {
				mu.Lock()
				created++
				mu.Unlock()
				r.Body.Close()
			}
		}(i)
	}
	wg.Wait()

	fmt.Printf("Setup: venue=%d, event=%d, tickets=%d/%d\n", venue.ID, event.ID, created, numSeats)
	fmt.Println("Waiting 3s for Kafka Streams materialization...")
	time.Sleep(3 * time.Second)

	return event.ID
}

// --- Spike Test ---

func runSpikeTest(host string, pool *ClientPool, eventId int64, numRequests int, section string, seatCount int, sleepMs int) []Result {
	results := make([]Result, numRequests)
	var wg sync.WaitGroup

	fmt.Printf("\nLaunching %d concurrent goroutines...\n", numRequests)
	start := time.Now()

	for i := 0; i < numRequests; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()

			client := pool.Get()
			userId := fmt.Sprintf("spike-%d", idx)

			// POST reservation
			reqBody, _ := json.Marshal(ReservationRequest{
				EventID:   eventId,
				Section:   section,
				SeatCount: seatCount,
				UserID:    userId,
			})

			postStart := time.Now()
			resp, err := client.Post(host+"/api/reservations", "application/json", bytes.NewReader(reqBody))
			postDuration := time.Since(postStart)

			if err != nil {
				results[idx] = Result{Status: "ERROR", Error: err.Error(), PostDuration: postDuration}
				return
			}

			if resp.StatusCode != 202 {
				resp.Body.Close()
				results[idx] = Result{Status: "POST_FAILED", PostDuration: postDuration}
				return
			}

			var postResp ReservationPostResponse
			json.NewDecoder(resp.Body).Decode(&postResp)
			resp.Body.Close()

			// Short sleep before GET (let Kafka Streams process)
			if sleepMs > 0 {
				time.Sleep(time.Duration(sleepMs) * time.Millisecond)
			}

			// GET reservation (DeferredResult long-polling — server blocks until ready)
			getStart := time.Now()
			getResp, err := client.Get(host + "/api/reservations/" + postResp.ReservationID)
			getDuration := time.Since(getStart)

			if err != nil {
				results[idx] = Result{Status: "GET_ERROR", Error: err.Error(), PostDuration: postDuration, GetDuration: getDuration}
				return
			}
			defer getResp.Body.Close()

			if getResp.StatusCode == 200 {
				var reservation ReservationGetResponse
				json.NewDecoder(getResp.Body).Decode(&reservation)
				results[idx] = Result{
					Status:         reservation.Status,
					AllocatedSeats: reservation.AllocatedSeats,
					PostDuration:   postDuration,
					GetDuration:    getDuration,
				}
			} else {
				results[idx] = Result{Status: "TIMEOUT", PostDuration: postDuration, GetDuration: getDuration}
			}
		}(i)
	}

	wg.Wait()
	elapsed := time.Since(start)
	fmt.Printf("All %d requests completed in %.2fs\n", numRequests, elapsed.Seconds())

	return results
}

// --- Report ---

func reportResults(results []Result, numSeats int) {
	confirmed := 0
	rejected := 0
	timeouts := 0
	errors := 0
	var postDurations []float64
	var getDurations []float64
	var e2eDurations []float64

	// Seat duplicate detection
	seatSet := make(map[string]bool)
	duplicates := 0

	for _, r := range results {
		switch r.Status {
		case "CONFIRMED":
			confirmed++
			for _, seat := range r.AllocatedSeats {
				if seatSet[seat] {
					duplicates++
					fmt.Fprintf(os.Stderr, "DUPLICATE SEAT: %s\n", seat)
				}
				seatSet[seat] = true
			}
		case "REJECTED":
			rejected++
		case "TIMEOUT":
			timeouts++
		default:
			errors++
		}

		if r.PostDuration > 0 {
			postDurations = append(postDurations, float64(r.PostDuration.Milliseconds()))
		}
		if r.GetDuration > 0 {
			getDurations = append(getDurations, float64(r.GetDuration.Milliseconds()))
		}
		if r.PostDuration > 0 && r.GetDuration > 0 {
			e2eDurations = append(e2eDurations, float64((r.PostDuration + r.GetDuration).Milliseconds()))
		}
	}

	total := confirmed + rejected + timeouts + errors
	expectedConfirmed := numSeats / 2

	fmt.Println("\n=== Spike Test Results ===")
	fmt.Printf("  Reservations:\n")
	fmt.Printf("    CONFIRMED: %d (expected: %d)\n", confirmed, expectedConfirmed)
	fmt.Printf("    REJECTED:  %d\n", rejected)
	fmt.Printf("    TIMEOUT:   %d\n", timeouts)
	fmt.Printf("    ERRORS:    %d\n", errors)
	fmt.Printf("    Total:     %d\n", total)
	fmt.Println()

	fmt.Printf("  POST latency:\n")
	printPercentiles(postDurations)
	fmt.Printf("  GET latency (long-polling):\n")
	printPercentiles(getDurations)
	fmt.Printf("  E2E latency (POST + GET):\n")
	printPercentiles(e2eDurations)

	fmt.Printf("\n  Seats allocated: %d / %d\n", len(seatSet), numSeats)
	fmt.Printf("  Duplicate seats: %d", duplicates)
	if duplicates == 0 {
		fmt.Println(" (PASS)")
	} else {
		fmt.Println(" (FAIL)")
	}
	fmt.Println("==========================")

	if duplicates > 0 {
		log.Fatalf("FATAL: %d duplicate seats detected!", duplicates)
	}
}

func printPercentiles(durations []float64) {
	if len(durations) == 0 {
		fmt.Println("    No data")
		return
	}
	sort.Float64s(durations)
	fmt.Printf("    P50: %.1fms  P95: %.1fms  P99: %.1fms  Max: %.1fms\n",
		percentile(durations, 50),
		percentile(durations, 95),
		percentile(durations, 99),
		durations[len(durations)-1],
	)
}

func percentile(sorted []float64, p float64) float64 {
	if len(sorted) == 0 {
		return 0
	}
	rank := (p / 100) * float64(len(sorted)-1)
	lower := int(math.Floor(rank))
	upper := int(math.Ceil(rank))
	if lower == upper {
		return sorted[lower]
	}
	frac := rank - float64(lower)
	return sorted[lower]*(1-frac) + sorted[upper]*frac
}

// --- Main ---

func main() {
	host := flag.String("host", "http://localhost:8080", "Base URL")
	numRequests := flag.Int("n", 100000, "Number of concurrent reservations")
	poolSize := flag.Int("c", 4, "HTTP client pool size")
	numSeats := flag.Int("seats", 100, "Number of tickets to create")
	section := flag.String("section", "A", "Section name")
	seatCount := flag.Int("seatCount", 2, "Seats per reservation")
	sleepMs := flag.Int("sleep", 100, "Sleep between POST and GET (ms)")
	skipSetup := flag.Bool("skip-setup", false, "Skip test data setup (use existing event)")
	eventId := flag.Int64("event-id", 0, "Event ID (only with -skip-setup)")
	flag.Parse()

	fmt.Println("=== Spike Test Client ===")
	fmt.Printf("  Host:       %s\n", *host)
	fmt.Printf("  Requests:   %d\n", *numRequests)
	fmt.Printf("  Pool size:  %d\n", *poolSize)
	fmt.Printf("  Seats:      %d\n", *numSeats)
	fmt.Printf("  Section:    %s\n", *section)
	fmt.Printf("  Seat/req:   %d\n", *seatCount)
	fmt.Printf("  Sleep:      %dms\n", *sleepMs)
	fmt.Println()

	pool := NewClientPool(*poolSize, true)

	var eid int64
	if *skipSetup && *eventId > 0 {
		eid = *eventId
		fmt.Printf("Using existing event: %d\n", eid)
	} else {
		eid = setupTestData(*host, pool.Get(), *numSeats)
	}

	results := runSpikeTest(*host, pool, eid, *numRequests, *section, *seatCount, *sleepMs)
	reportResults(results, *numSeats)
}
