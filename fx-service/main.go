package main

import (
	"encoding/json"
	"fmt"
	"log"
	"math/rand"
	"net/http"
	"sync"
	"time"
)

var (
	rateTable = createRateTable()
	mu        sync.Mutex
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

func main() {

	go func() {
		for {
			time.Sleep(time.Second * 2)
			mu.Lock()
			for key, val := range rateTable {
				if val.Currency == "USD" {
					continue
				}

				val.Rate = applyDrift(val.Rate)

				rateTable[key] = val
			}
			mu.Unlock()
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

	mu.Lock()
	defer mu.Unlock()

	fromCurrency, ok := rateTable[from]
	if !ok {
		log.Printf("Error: the %s currency is not supported.", from)
		w.WriteHeader(http.StatusBadRequest)
		w.Write([]byte("unsupported currency: " + from))

		return
	}

	toCurrency, ok := rateTable[to]
	if !ok {
		log.Printf("Error: the %s currency is not supported.", to)
		w.WriteHeader(http.StatusBadRequest)
		w.Write([]byte("unsupported currency: " + to))

		return
	}

	if toCurrency.Rate <= 0.0 || fromCurrency.Rate <= 0 {
		log.Printf("Error: one of currencies has invalid value. from: %f, to: %f", fromCurrency.Rate, toCurrency.Rate)
		w.WriteHeader(http.StatusBadRequest)
		w.Write([]byte(fmt.Sprintf("Error: one of currencies has invalid value. from: %f, to: %f", fromCurrency.Rate, toCurrency.Rate)))

		return
	}

	rate := triangulate(fromCurrency.Rate, toCurrency.Rate)

	response := Response{From: fromCurrency.Currency, To: toCurrency.Currency, Rate: rate}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	if err := json.NewEncoder(w).Encode(response); err != nil {
		log.Printf("Failed to convert rate response to json for %+v data: %v", response, err)
		return
	}

}

func handleAllRates(w http.ResponseWriter, r *http.Request) {
	mu.Lock()
	defer mu.Unlock()

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	if err := json.NewEncoder(w).Encode(rateTable); err != nil {
		log.Printf("Failed to convert all rates to json: %v", err)
	}
}

func createRateTable() map[string]Rates {
	return map[string]Rates{
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
}

func triangulate(fromRate, toRate float64) float64 {
	return toRate / fromRate
}

func applyDrift(rate float64) float64 {
	drift := (rand.Float64()*2 - 1) * 0.005
	return  rate * (1 + drift)
}
