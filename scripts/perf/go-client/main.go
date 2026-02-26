package main

import (
	"bytes"
	"cmp"
	"context"
	"crypto/tls"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"math"
	"math/rand"
	"net"
	"net/http"
	"os"
	"runtime"
	"runtime/pprof"
	"runtime/trace"
	"slices"
	"strconv"
	"sync"
	"time"

	"github.com/google/uuid"
	"github.com/hashicorp/go-retryablehttp"
	"github.com/tcnksm/go-httpstat"
	"github.com/urfave/cli/v3"
	"go.uber.org/zap"
	"golang.org/x/net/http2"
)

// ---------------------------------------------------------------------------
// Logger adapter for retryablehttp
// ---------------------------------------------------------------------------

type SugaredLeveledLogger struct {
	logger *zap.SugaredLogger
}

func (s SugaredLeveledLogger) Error(msg string, keysAndValues ...interface{}) {
	s.logger.Errorw(msg, keysAndValues...)
}
func (s SugaredLeveledLogger) Info(msg string, keysAndValues ...interface{}) {
	s.logger.Infow(msg, keysAndValues...)
}
func (s SugaredLeveledLogger) Debug(msg string, keysAndValues ...interface{}) {
	s.logger.Debugw(msg, keysAndValues...)
}
func (s SugaredLeveledLogger) Warn(msg string, keysAndValues ...interface{}) {
	s.logger.Warnw(msg, keysAndValues...)
}

// ---------------------------------------------------------------------------
// Data types — adapted for /api/* endpoints
// ---------------------------------------------------------------------------

// POST /api/venues
type VenueRequest struct {
	Name     string `json:"name"`
	Address  string `json:"address"`
	Capacity int    `json:"capacity"`
}

type VenueResponse struct {
	ID       int64  `json:"id"`
	Name     string `json:"name"`
	Address  string `json:"address"`
	Capacity int    `json:"capacity"`
}

// POST /api/events
type SectionRequest struct {
	Section     string `json:"section"`
	Rows        int    `json:"rows"`
	SeatsPerRow int    `json:"seatsPerRow"`
}

type EventRequest struct {
	Name        string           `json:"name"`
	Description string           `json:"description"`
	EventDate   string           `json:"eventDate"`
	VenueID     int64            `json:"venueId"`
	Sections    []SectionRequest `json:"sections"`
}

type EventResponse struct {
	ID         int64  `json:"id"`
	Name       string `json:"name"`
	TotalSeats int    `json:"totalSeats"`
}

// POST /api/reservations
type ReservationRequest struct {
	EventID   int64  `json:"eventId"`
	Section   string `json:"section"`
	SeatCount int    `json:"seatCount"`
	UserID    string `json:"userId"`
}

// POST response: { "reservationId": "...", "status": "..." }
type CreateReservationResponse struct {
	ReservationID string `json:"reservationId"`
	Status        string `json:"status"`
}

// GET /api/reservations/{id}
type ReservationResponse struct {
	ReservationID  string   `json:"reservationId"`
	EventID        int64    `json:"eventId"`
	Section        string   `json:"section"`
	SeatCount      int      `json:"seatCount"`
	UserID         string   `json:"userId"`
	Status         string   `json:"status"`
	AllocatedSeats []string `json:"allocatedSeats"`
}

// ---------------------------------------------------------------------------
// Globals
// ---------------------------------------------------------------------------

var clients []*http.Client
var logger *zap.SugaredLogger

func getClient() *http.Client {
	return clients[rand.Int()%len(clients)]
}

// ---------------------------------------------------------------------------
// API helpers
// ---------------------------------------------------------------------------

func createVenue(host string, numOfSections int) (*VenueResponse, error) {
	url := fmt.Sprintf("http://%s/api/venues", host)
	venue := VenueRequest{
		Name:     "spike-venue-" + uuid.NewString()[:8],
		Address:  "Go Spike Test",
		Capacity: numOfSections * 20 * 20,
	}

	jsonData, err := json.Marshal(venue)
	if err != nil {
		return nil, err
	}

	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()

	req, err := http.NewRequestWithContext(ctx, "POST", url, bytes.NewBuffer(jsonData))
	if err != nil {
		return nil, fmt.Errorf("error creating request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")

	resp, err := getClient().Do(req)
	if err != nil {
		return nil, fmt.Errorf("error sending request: %w", err)
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, fmt.Errorf("error reading response: %w", err)
	}

	if resp.StatusCode != http.StatusCreated {
		return nil, fmt.Errorf("venue creation failed: %d %s", resp.StatusCode, string(body))
	}

	var created VenueResponse
	if err := json.Unmarshal(body, &created); err != nil {
		return nil, fmt.Errorf("error parsing venue response: %w", err)
	}
	return &created, nil
}

func createEvent(host string, venueID int64, numOfSections int) (*EventResponse, []string, error) {
	url := fmt.Sprintf("http://%s/api/events", host)

	tomorrow := time.Now().AddDate(0, 0, 1).Format("2006-01-02")
	sections := make([]SectionRequest, numOfSections)
	sectionNames := make([]string, numOfSections)
	for i := 0; i < numOfSections; i++ {
		name := "S" + strconv.Itoa(i)
		sections[i] = SectionRequest{Section: name, Rows: 20, SeatsPerRow: 20}
		sectionNames[i] = name
	}

	event := EventRequest{
		Name:        "spike-event-" + uuid.NewString()[:8],
		Description: "Go spike test",
		EventDate:   tomorrow,
		VenueID:     venueID,
		Sections:    sections,
	}

	jsonData, err := json.Marshal(event)
	if err != nil {
		return nil, nil, err
	}

	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()

	req, err := http.NewRequestWithContext(ctx, "POST", url, bytes.NewBuffer(jsonData))
	if err != nil {
		return nil, nil, fmt.Errorf("error creating request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")

	resp, err := getClient().Do(req)
	if err != nil {
		return nil, nil, fmt.Errorf("error sending request: %w", err)
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, nil, fmt.Errorf("error reading response: %w", err)
	}

	if resp.StatusCode != http.StatusCreated {
		return nil, nil, fmt.Errorf("event creation failed: %d %s", resp.StatusCode, string(body))
	}

	var created EventResponse
	if err := json.Unmarshal(body, &created); err != nil {
		return nil, nil, fmt.Errorf("error parsing event response: %w", err)
	}
	return &created, sectionNames, nil
}

func createReservation(host string, eventID int64, section string) (string, *httpstat.Result, error) {
	url := fmt.Sprintf("http://%s/api/reservations", host)

	payload := ReservationRequest{
		EventID:   eventID,
		Section:   section,
		SeatCount: rand.Intn(4) + 1,
		UserID:    "spike-" + uuid.NewString()[:8],
	}

	jsonData, err := json.Marshal(payload)
	if err != nil {
		return "", nil, fmt.Errorf("error marshaling JSON: %w", err)
	}

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	var result httpstat.Result
	ctx = httpstat.WithHTTPStat(ctx, &result)

	req, err := http.NewRequestWithContext(ctx, "POST", url, bytes.NewBuffer(jsonData))
	if err != nil {
		return "", nil, fmt.Errorf("error creating request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")

	resp, err := getClient().Do(req)
	if err != nil {
		return "", &result, fmt.Errorf("error sending request: %w", err)
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return "", &result, fmt.Errorf("error reading response: %w", err)
	}
	result.End(time.Now())

	if resp.StatusCode != http.StatusAccepted {
		return "", &result, fmt.Errorf("POST reservation failed: %d %s", resp.StatusCode, string(body))
	}

	var createResp CreateReservationResponse
	if err := json.Unmarshal(body, &createResp); err != nil {
		return "", &result, fmt.Errorf("error parsing POST response: %w", err)
	}
	return createResp.ReservationID, &result, nil
}

func getReservation(host string, reservationID string) (*ReservationResponse, *httpstat.Result, error) {
	url := fmt.Sprintf("http://%s/api/reservations/%s", host, reservationID)

	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	var result httpstat.Result
	ctx = httpstat.WithHTTPStat(ctx, &result)

	req, err := http.NewRequestWithContext(ctx, "GET", url, nil)
	if err != nil {
		return nil, nil, err
	}

	resp, err := getClient().Do(req)
	if err != nil {
		return nil, nil, fmt.Errorf("error sending request: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return nil, &result, fmt.Errorf("GET reservation %d %s", resp.StatusCode, http.StatusText(resp.StatusCode))
	}

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, &result, fmt.Errorf("error reading response: %w", err)
	}
	result.End(time.Now())

	var reservation ReservationResponse
	if err := json.Unmarshal(body, &reservation); err != nil {
		return nil, &result, fmt.Errorf("error parsing reservation: %w", err)
	}
	return &reservation, &result, nil
}

// ---------------------------------------------------------------------------
// Health check & HTTP client init
// ---------------------------------------------------------------------------

func healthCheck(client *http.Client, host string) error {
	// Use actuator health endpoint (Spring Boot default)
	url := fmt.Sprintf("http://%s/actuator/health", host)
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	req, err := http.NewRequestWithContext(ctx, "GET", url, nil)
	if err != nil {
		return err
	}

	resp, err := client.Do(req)
	if err != nil {
		return fmt.Errorf("error sending request: %w", err)
	}
	defer resp.Body.Close()
	io.Copy(io.Discard, resp.Body)
	return nil
}

func initHTTPClients(enableHTTP2 bool, numOfClients int, host string) {
	for i := 0; i < numOfClients; i++ {
		retryClient := retryablehttp.NewClient()
		retryClient.RetryMax = 20
		retryClient.Logger = SugaredLeveledLogger{logger: logger}

		if enableHTTP2 {
			httpClient := &http.Client{
				Transport: &http2.Transport{
					AllowHTTP: true,
					DialTLS: func(network, addr string, cfg *tls.Config) (net.Conn, error) {
						return net.Dial(network, addr)
					},
				},
			}
			retryClient.HTTPClient = httpClient
		}

		client := retryClient.StandardClient()
		err := healthCheck(client, host)
		if err != nil {
			logger.Warnf("health check to %s failed (continuing anyway): %v", host, err)
		}

		clients = append(clients, client)
	}
}

// ---------------------------------------------------------------------------
// Concurrent test runner
// ---------------------------------------------------------------------------

type Result struct {
	reservation *ReservationResponse
	err         error
	postStats   *httpstat.Result
	getStats    *httpstat.Result
	latency     time.Duration
}

func createConcurrentRequests(host string, eventID int64, sections []string, numOfRequests int, resultChan chan<- Result, timeOfSleep time.Duration) {
	var wg sync.WaitGroup

	testStartTime := time.Now()
	for i := 0; i < numOfRequests; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()

			section := sections[rand.Intn(len(sections))]
			startTime := time.Now()

			reservationID, postStats, err := createReservation(host, eventID, section)
			if err != nil {
				resultChan <- Result{err: err, postStats: postStats, latency: time.Since(startTime)}
				return
			}

			time.Sleep(timeOfSleep)

			reservation, getStats, err := getReservation(host, reservationID)
			if err != nil {
				resultChan <- Result{err: err, postStats: postStats, getStats: getStats, latency: time.Since(startTime)}
				return
			}

			resultChan <- Result{
				reservation: reservation,
				postStats:   postStats,
				getStats:    getStats,
				latency:     time.Since(startTime),
			}
		}()
	}
	wg.Wait()
	logger.Infof("Test completed in %.2f seconds", time.Since(testStartTime).Seconds())
}

// ---------------------------------------------------------------------------
// Results reporting with duplicate seat detection
// ---------------------------------------------------------------------------

func reportResults(numOfRequests int, resultChan <-chan Result) {
	// section -> set of allocated seat strings
	reservedSeats := make(map[string]map[string]bool)
	successReservations := 0
	failedReservations := 0
	errResults := 0
	duplicateSeats := 0

	var reservationStats []Result
	var reservationLatency []time.Duration

	for i := 0; i < numOfRequests; i++ {
		result := <-resultChan
		if result.err != nil {
			errResults++
			logger.Error(result.err)
			continue
		}
		reservationStats = append(reservationStats, result)
		reservationLatency = append(reservationLatency, result.latency)

		reservation := result.reservation
		section := reservation.Section

		if reservedSeats[section] == nil {
			reservedSeats[section] = make(map[string]bool)
		}

		if reservation.Status == "CONFIRMED" {
			successReservations++
			for _, seat := range reservation.AllocatedSeats {
				key := section + ":" + seat
				if reservedSeats[section][key] {
					duplicateSeats++
					logger.Errorf("DUPLICATE SEAT DETECTED: section=%s seat=%s", section, seat)
				}
				reservedSeats[section][key] = true
			}
		} else {
			failedReservations++
		}
	}

	fmt.Println("\n========== SPIKE TEST RESULTS ==========")
	reportResponseTimeStats(reservationStats, reservationLatency)
	fmt.Printf("Successful reservations: %d\n", successReservations)
	fmt.Printf("Failed reservations:     %d\n", failedReservations)
	fmt.Printf("Error results:           %d\n", errResults)
	fmt.Printf("Duplicate seats:         %d\n", duplicateSeats)

	if duplicateSeats > 0 {
		fmt.Println("\n*** OVERBOOKING DETECTED — DATA INTEGRITY VIOLATION ***")
	} else {
		fmt.Println("\nZero duplicate seats — data integrity verified.")
	}

	totalReservedSeats := 0
	for section, seats := range reservedSeats {
		fmt.Printf("  Section %-5s: %d seats reserved\n", section, len(seats))
		totalReservedSeats += len(seats)
	}
	fmt.Printf("Total reserved seats: %d\n", totalReservedSeats)
	fmt.Println("=========================================")
}

func reportResponseTimeStats(reservationStats []Result, reservationLatency []time.Duration) {
	if len(reservationStats) == 0 {
		fmt.Println("No successful responses to report.")
		return
	}

	slices.SortFunc(reservationStats, func(r1, r2 Result) int {
		return cmp.Compare(getResponseTime(r1), getResponseTime(r2))
	})
	slices.SortFunc(reservationLatency, func(t1, t2 time.Duration) int {
		return cmp.Compare(t1.Milliseconds(), t2.Milliseconds())
	})

	fmt.Println(getPercentileResult(reservationStats, reservationLatency, 50))
	fmt.Println(getPercentileResult(reservationStats, reservationLatency, 95))
	fmt.Println(getPercentileResult(reservationStats, reservationLatency, 99))
}

func getResponseTime(result Result) int64 {
	var t time.Duration
	if result.postStats != nil {
		t += result.postStats.ServerProcessing
	}
	if result.getStats != nil {
		t += result.getStats.ServerProcessing
	}
	return t.Milliseconds()
}

func getPercentileResult(reservationStats []Result, reservationLatency []time.Duration, percentileInt int) string {
	if len(reservationStats) == 0 {
		return "No data for percentile calculation"
	}

	percentile := float64(percentileInt) / 100.0
	n := len(reservationStats)
	idx := float64(n-1) * percentile

	lowIdx := int(math.Floor(idx))
	highIdx := int(math.Ceil(idx))

	if lowIdx == highIdx {
		pt := float64(getResponseTime(reservationStats[lowIdx]))
		lat := float64(reservationLatency[lowIdx].Milliseconds())
		return fmt.Sprintf("P%d: Processing Time %.1f ms, Latency %.1f ms", percentileInt, pt, lat)
	}

	pt1 := float64(getResponseTime(reservationStats[lowIdx]))
	pt2 := float64(getResponseTime(reservationStats[highIdx]))
	lat1 := float64(reservationLatency[lowIdx].Milliseconds())
	lat2 := float64(reservationLatency[highIdx].Milliseconds())

	pt := pt1*(float64(highIdx)-idx) + pt2*(idx-float64(lowIdx))
	lat := lat1*(float64(highIdx)-idx) + lat2*(idx-float64(lowIdx))

	return fmt.Sprintf("P%d: Processing Time %.1f ms, Latency %.1f ms", percentileInt, pt, lat)
}

// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------

func main() {
	cmd := &cli.Command{
		Name:                   "spike-test",
		Usage:                  "TicketMaster spike/burst load test client",
		UseShortOptionHandling: true,
		Flags: []cli.Flag{
			&cli.StringFlag{
				Name:  "host",
				Value: "localhost:8080",
				Usage: "target host:port",
			},
			&cli.IntFlag{
				Name:    "reqs",
				Value:   20,
				Usage:   "number of concurrent requests",
				Aliases: []string{"n"},
			},
			&cli.IntFlag{
				Name:    "numOfSections",
				Value:   20,
				Usage:   "number of sections to create",
				Aliases: []string{"a"},
			},
			&cli.IntFlag{
				Name:    "numOfClients",
				Value:   1,
				Usage:   "number of HTTP clients (reduces lock contention)",
				Aliases: []string{"c"},
			},
			&cli.StringFlag{
				Name:    "sleep",
				Value:   "0s",
				Usage:   "sleep between POST and GET",
				Aliases: []string{"t"},
				Action: func(ctx context.Context, cmd *cli.Command, v string) error {
					_, err := time.ParseDuration(v)
					return err
				},
			},
			&cli.StringFlag{
				Name:    "env",
				Value:   "dev",
				Usage:   "environment (dev|prod)",
				Aliases: []string{"e"},
				Action: func(ctx context.Context, cmd *cli.Command, v string) error {
					if v != "dev" && v != "prod" {
						return errors.New(fmt.Sprintf("env should be 'dev' or 'prod', got %s", v))
					}
					return nil
				},
			},
			&cli.BoolFlag{
				Name:  "http2",
				Value: false,
				Usage: "enable HTTP/2 cleartext (h2c)",
			},
			&cli.StringFlag{Name: "cpuprofile", Aliases: []string{"cpu"}, Usage: "write CPU profile to file"},
			&cli.StringFlag{Name: "memprofile", Aliases: []string{"mem"}, Usage: "write memory profile to file"},
			&cli.StringFlag{Name: "blockprofile", Aliases: []string{"block"}, Usage: "write block profile to file"},
			&cli.StringFlag{Name: "lockprofile", Aliases: []string{"lock"}, Usage: "write lock profile to file"},
			&cli.StringFlag{Name: "traceprofile", Aliases: []string{"trace"}, Usage: "write trace to file"},
		},
		Action: func(ctx context.Context, cmd *cli.Command) error {
			host := cmd.String("host")
			numOfRequests := int(cmd.Int("reqs"))
			enableHTTP2 := cmd.Bool("http2")
			timeOfSleep, _ := time.ParseDuration(cmd.String("sleep"))
			numOfSections := int(cmd.Int("numOfSections"))
			numOfClients := int(cmd.Int("numOfClients"))
			env := cmd.String("env")
			cpuprofile := cmd.String("cpuprofile")
			memprofile := cmd.String("memprofile")
			blockprofile := cmd.String("blockprofile")
			lockprofile := cmd.String("lockprofile")
			traceprofile := cmd.String("traceprofile")

			// Profiling setup
			if blockprofile != "" {
				runtime.SetBlockProfileRate(1)
			}
			if lockprofile != "" {
				runtime.SetMutexProfileFraction(1)
			}
			if cpuprofile != "" {
				f, err := os.Create(cpuprofile)
				if err != nil {
					log.Fatal("could not create CPU profile: ", err)
				}
				defer f.Close()
				if err := pprof.StartCPUProfile(f); err != nil {
					log.Fatal("could not start CPU profile: ", err)
				}
				defer pprof.StopCPUProfile()
			}
			if traceprofile != "" {
				f, err := os.Create(traceprofile)
				if err != nil {
					log.Fatal("could not create trace profile: ", err)
				}
				defer f.Close()
				if err := trace.Start(f); err != nil {
					log.Fatalf("could not start trace: %v", err)
				}
				defer trace.Stop()
			}

			// Logger
			var zapLogger *zap.Logger
			if env == "dev" {
				zapLogger, _ = zap.NewDevelopment()
			} else {
				zapLogger, _ = zap.NewProduction()
			}
			defer zapLogger.Sync()
			logger = zapLogger.Sugar()

			// Init HTTP clients
			initHTTPClients(enableHTTP2, numOfClients, host)
			resultChan := make(chan Result, numOfRequests)

			// Create venue
			logger.Infof("Creating venue with capacity for %d sections...", numOfSections)
			venue, err := createVenue(host, numOfSections)
			if err != nil {
				log.Fatal(err)
			}
			logger.Infof("Venue created: id=%d", venue.ID)

			// Create event with sections
			logger.Infof("Creating event with %d sections (20x20 each)...", numOfSections)
			event, sectionNames, err := createEvent(host, venue.ID, numOfSections)
			if err != nil {
				log.Fatal(err)
			}
			logger.Infof("Event created: id=%d, totalSeats=%d", event.ID, event.TotalSeats)

			// Wait for Kafka Streams initialization
			logger.Infof("Waiting 3 seconds for Kafka Streams to initialize...")
			time.Sleep(3 * time.Second)
			logger.Infoln("Ready — starting spike test")

			// Run concurrent requests
			createConcurrentRequests(host, event.ID, sectionNames, numOfRequests, resultChan, timeOfSleep)
			reportResults(numOfRequests, resultChan)

			// Write profiles
			if memprofile != "" {
				f, err := os.Create(memprofile)
				if err != nil {
					log.Fatal("could not create memory profile: ", err)
				}
				defer f.Close()
				runtime.GC()
				if err := pprof.Lookup("allocs").WriteTo(f, 0); err != nil {
					log.Fatal("could not write memory profile: ", err)
				}
			}
			if blockprofile != "" {
				f, err := os.Create(blockprofile)
				if err != nil {
					log.Fatal("could not create block profile: ", err)
				}
				defer f.Close()
				runtime.GC()
				if err := pprof.Lookup("block").WriteTo(f, 0); err != nil {
					log.Fatal("could not write block profile: ", err)
				}
			}
			if lockprofile != "" {
				f, err := os.Create(lockprofile)
				if err != nil {
					log.Fatal("could not create lock profile: ", err)
				}
				defer f.Close()
				runtime.GC()
				if err := pprof.Lookup("mutex").WriteTo(f, 0); err != nil {
					log.Fatal("could not write lock profile: ", err)
				}
			}
			return nil
		},
	}

	if err := cmd.Run(context.Background(), os.Args); err != nil {
		log.Fatal(err)
	}
}
