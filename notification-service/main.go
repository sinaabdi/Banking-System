package main

import (
	"encoding/json"
	"log"
	"net/http"
	"sync"
	"time"
)

type Notification struct {
	ID            int       `json:"id"`
	TransactionID int       `json:"transaction_id"`
	Type          string    `json:"type"`
	Status        string    `json:"status"`
	ReceivedAt    time.Time `json:"received_at"`
}

var (
	notificationQueue = []Notification{}
	mu                sync.Mutex
)

func main() {
	mux := http.NewServeMux()

	mux.HandleFunc("POST /notifications", createHandler)
	mux.HandleFunc("GET /notifications", listHandler)

	log.Println("Notification server listening on port 9090...")
	if err := http.ListenAndServe(":9090", mux); err != nil {
		log.Fatalf("notification server failed: %v", err)
	}
}

func createHandler(writer http.ResponseWriter, request *http.Request) {
	var n Notification

	if err := json.NewDecoder(request.Body).Decode(&n); err != nil {
		log.Printf("Error: failed to decode the request: %v", err.Error())
		http.Error(writer, err.Error(), http.StatusBadRequest)
		return
	}

	if n.TransactionID <= 0 || n.Type == "" || n.Status == "" {
		log.Printf("Error: all notification fields are require: %+v", n)
		http.Error(writer, "all fields require", http.StatusBadRequest)
		return
	}

	mu.Lock()
	defer mu.Unlock()

	n.ID = len(notificationQueue) + 1
	n.ReceivedAt = time.Now()
	notificationQueue = append(notificationQueue, n)

	log.Printf("sending notification id=%v", n.ID)
	writer.Header().Set("Content-Type", "application/json")
	writer.WriteHeader(http.StatusCreated)
	if err := json.NewEncoder(writer).Encode(&n); err != nil {
		log.Printf("Error: failed to encode the data and send the notification: %v", err.Error())
		http.Error(writer, err.Error(), http.StatusInternalServerError)
		return
	}
}

func listHandler(writer http.ResponseWriter, request *http.Request) {
	mu.Lock()
	defer mu.Unlock()

	writer.Header().Set("Content-Type", "application/json")
	if err := json.NewEncoder(writer).Encode(notificationQueue); err != nil {
		log.Printf("Error: failed to encode notification queue and list notifications: %v", err.Error())
		http.Error(writer, err.Error(), http.StatusInternalServerError)
		return
	}
}
