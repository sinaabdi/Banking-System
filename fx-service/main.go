package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"math/rand"
	"net/http"
	"os"
	"strconv"
	"sync"
	"time"

	uuid "github.com/google/uuid"
	redis "github.com/redis/go-redis/v9"
)

var initialRates = map[string]Rates{
	"USD": {
		Currency:    "USD",
		Description: "U.S. Dollar",
		Rate:        1.0,
	},
	"EUR": {
		Currency:    "EUR",
		Description: "Euro",
		Rate:        0.92,
	},
	"GBP": {
		Currency:    "GBP",
		Description: "British Pound Sterling",
		Rate:        0.79,
	},
	"JPY": {
		Currency:    "JPY",
		Description: "Japanese Yen",
		Rate:        149.0,
	},
	"CAD": {
		Currency:    "CAD",
		Description: "Canadian Dollar",
		Rate:        1.36,
	},
	"AUD": {
		Currency:    "AUD",
		Description: "Australian Dollar",
		Rate:        1.52,
	},
	"CHF": {
		Currency:    "CHF",
		Description: "Swiss Franc",
		Rate:        0.88,
	},
}

var (
	currencyCodes []string
	mu            sync.Mutex
	rdsClient     *redis.Client
	rdsRateKey    string = "fx:rates"
	rdsLeaderKey  string = "fx:drift-leader"
	instanceID    uuid.UUID
	rdsScript     = redis.NewScript("if redis.call(\"get\", KEYS[1]) == ARGV[1] then return redis.call(\"del\", KEYS[1]) else return 0 end")
)

type Rates struct {
	Currency    string  `json:"currency"`
	Description string  `json:"description"`
	Rate        float64 `json:"rate"`
}

type Response struct {
	From string  `json:"from"`
	To   string  `json:"to"`
	Rate float64 `json:"rate"`
}

func init() {
	rdsClient = redis.NewClient(&redis.Options{Addr: getRedisAddr(), Password: ""})
	for k, v := range initialRates {
		if k == "USD" {continue}
		currencyCodes = append(currencyCodes, v.Currency)
	}
	
	instanceID = uuid.New()
	createRateTable()
}

func main() {

	go func() {
		for {
			time.Sleep(time.Second * 2)
			driftTick()
		}
	}()

	mux := http.NewServeMux()

	mux.HandleFunc("GET /rate", handleRateRequest)
	mux.HandleFunc("GET /rates", handleAllRates)

	log.Println("FX server listening on port 9092...")
	if err := http.ListenAndServe(":9092", mux); err != nil {
		log.Fatalf("FX server failed: %v", err)
	}
}

func handleRateRequest(w http.ResponseWriter, r *http.Request) {
	params := r.URL.Query()

	from := params.Get("from")
	to := params.Get("to")

	if from == "" || to == "" {
		log.Printf("Error: 'to' and 'from' are required.")
		w.WriteHeader(http.StatusBadRequest)
		w.Write([]byte("'to' and 'from' parameters are required"))

		return
	}

	fromRate, err := getRate(from)
	if err != nil {
		if errors.Is(err, redis.Nil) {
			log.Printf("Error: the %s currency is not supported.", from)
			w.WriteHeader(http.StatusBadRequest)
			w.Write([]byte("unsupported currency: " + from))
		} else {
			log.Printf("Error: failed to fetch the rate for %s currency.", from)
			w.WriteHeader(http.StatusInternalServerError)
			w.Write([]byte("failed to get rate of " + from))
		}

		return
	}

	toRate, err := getRate(to)
	if err != nil {
		if errors.Is(err, redis.Nil) {
			log.Printf("Error: the %s currency is not supported.", to)
			w.WriteHeader(http.StatusBadRequest)
			w.Write([]byte("unsupported currency: " + to))
		} else {
			log.Printf("Error: failed to fetch the rate for %s currency.", to)
			w.WriteHeader(http.StatusInternalServerError)
			w.Write([]byte("failed to get rate of " + to))
		}

		return
	}

	if toRate <= 0.0 || fromRate <= 0 {
		log.Printf("Error: one of currencies has invalid value. from: %f, to: %f", fromRate, toRate)
		w.WriteHeader(http.StatusBadRequest)
		w.Write([]byte(fmt.Sprintf("Error: one of currencies has invalid value. from: %f, to: %f", fromRate, toRate)))

		return
	}

	rate := triangulate(fromRate, toRate)

	response := Response{From: from, To: to, Rate: rate}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	if err := json.NewEncoder(w).Encode(response); err != nil {
		log.Printf("Failed to convert rate response to json for %+v data: %v", response, err)
		return
	}

}

func handleAllRates(w http.ResponseWriter, r *http.Request) {

	rates, err := getAllRates()
	if err != nil {
		log.Printf("failed to fetch all rates: %v", err)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	if err := json.NewEncoder(w).Encode(rates); err != nil {
		log.Printf("Failed to convert all rates to json: %v", err)
	}
}

func createRateTable() {
	for _, val := range initialRates {
		rdsClient.HSetNX(context.Background(), rdsRateKey, val.Currency, strconv.FormatFloat(val.Rate, 'f', -1, 64))
	}
}

func getRate(currency string) (float64, error) {
	res, err := rdsClient.HGet(context.Background(), rdsRateKey, currency).Result()
	if err != nil {
		return 0.0, err
	}
	rate, err := strconv.ParseFloat(res, 64)
	if err != nil {
		return 0.0, err
	}

	return rate, nil
}

func getAllRates() (map[string]Rates, error) {
	res, err := rdsClient.HGetAll(context.Background(), rdsRateKey).Result()
	if err != nil {
		return nil, err
	}

	rates := make(map[string]Rates)
	for key, val := range res {
		rateFloat, err := strconv.ParseFloat(val, 64)
		if err != nil {
			return nil, err
		}
		rates[key] = Rates{Currency: key, Rate: rateFloat, Description: ""}
	}

	return rates, nil

}

func triangulate(fromRate, toRate float64) float64 {
	return toRate / fromRate
}

func applyDrift(rate float64) float64 {
	drift := (rand.Float64()*2 - 1) * 0.005
	return rate * (1 + drift)
}

func getRedisAddr() string {
	host := os.Getenv("REDIS_HOST")
	if host == "" {
		host = "localhost"
	}

	port := os.Getenv("REDIS_PORT")
	if port == "" {
		port = "6379"
	}

	return fmt.Sprintf("%s:%s", host, port)
}

func driftTick() {
	mu.Lock()
	defer mu.Unlock()

	// Lock the Redis
	keyIsMine, err := rdsClient.SetNX(context.Background(), rdsLeaderKey, instanceID, time.Second*2).Result()
	if err != nil {
		log.Printf("failed to set redis distributed key: %v", err)
		return
	}

	if !keyIsMine {
		return
	}

	for _, code := range currencyCodes {

		rate, err := getRate(code)
		if err != nil {
			log.Printf("failed to get %s rate for drift tick: %v", code, err)
			continue
		}

		drift := applyDrift(rate)
		rdsClient.HSet(context.Background(), rdsRateKey, map[string]string{code: strconv.FormatFloat(drift, 'f', -1, 64)})
	}

	// Release Redis Lock
	if _, err = rdsScript.Run(context.Background(), rdsClient, []string{rdsLeaderKey}, instanceID).Result(); err != nil {
		log.Printf("failed to release the lock: %v", err)
	}
}
