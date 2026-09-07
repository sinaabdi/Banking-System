package main

import (
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"os"
	"strconv"
	"sync"
	"time"

	amqp "github.com/rabbitmq/amqp091-go"
)

var (
	transactionQueue      = []Transaction{}
	riskFlags             []RiskFlag
	mu                    sync.Mutex
	largeAmountThreshold  int
	velocityWindowSeconds int
	velocityMaxCount      int
	velocity              = VelocityTracker{Tracker: map[int][]time.Time{}}
)

const (
	LARGE_AMOUNT  = "large_amount"
	HIGH_VELOCITY = "high_velocity"
)

type Transaction struct {
	ID                    int       `json:"id"`
	TransactionID         int       `json:"transaction_id"`
	Type                  string    `json:"type"`
	Status                string    `json:"status"`
	Amount                int64     `json:"amount"`
	Currency              string    `json:"currency"`
	AccountID             int       `json:"account_id"`
	UserID                int       `json:"user_id"`
	CounterpartyAccountID *int      `json:"counterparty_account_id"`
	CounterpartyUserID    *int      `json:"counterparty_user_id"`
	ReceivedAt            time.Time `json:"received_at"`
}

type RiskFlag struct {
	ID            int       `json:"id"`
	TransactionID int       `json:"transaction_id"`
	AccountID     int       `json:"account_id"`
	UserID        int       `json:"user_id"`
	Reasons       []string  `json:"reasons"`
	FlaggedAt     time.Time `json:"flagged_at"`
}

type VelocityTracker struct {
	sync.Mutex
	Tracker map[int][]time.Time
}

func (v *VelocityTracker) record(userID int, window time.Duration) int {
	v.Mutex.Lock()
	defer v.Mutex.Unlock()

	var w []time.Time

	val, _ := v.Tracker[userID]
	for _, t := range val {
		if time.Since(t) <= window {
			w = append(w, t)
		}
	}

	w = append(w, time.Now())

	v.Tracker[userID] = w
	return len(w)

}

func main() {

	log.Println("Setting threshold parametes...")
	setFraudParametersThreshold()
	log.Println("all threshhold parameters has been set.")

	log.Println("Connecting to the RabbitMQ...")
	messages := connectToRabbitMQ()
	log.Println("connected to the RabbitMQ successfully.")
	// Runs concurrently with http.ListenAndServe below (which blocks forever) - this goroutine is
	// what actually drains the queue, for as long as the process lives.
	go func() {
		for msg := range messages {
			var t Transaction
			log.Printf("Received a message: %s", msg.Body)

			if err := json.Unmarshal(msg.Body, &t); err != nil {
				log.Printf("failed to decode the message: %v", err)
				continue
			}
			if t.TransactionID <= 0 || t.Type == "" || t.Status == "" {
				log.Printf("Error: all notification fields are require: %+v", t)
				continue
			}

			mu.Lock()
			t.ID = len(transactionQueue) + 1
			t.ReceivedAt = time.Now()
			transactionQueue = append(transactionQueue, t)

			log.Printf("scoring transaction id=%d", t.ID)
			reasons := scoreTransaction(t)
			if reasons != nil {
				rf := RiskFlag{
					ID:            len(riskFlags) + 1,
					TransactionID: t.TransactionID,
					AccountID:     t.AccountID,
					UserID:        t.UserID,
					Reasons:       reasons,
					FlaggedAt:     time.Now(),
				}
				riskFlags = append(riskFlags, rf)
			}

			mu.Unlock()

			if err := msg.Ack(false); err != nil {
				log.Printf("failed to set Ack: %v", err)
				continue
			}
			log.Printf("Acked message for transactionId=%d", t.TransactionID)
		}
	}()

	mux := http.NewServeMux()
	mux.HandleFunc("GET /flags", listFlagsHandler)

	if err := http.ListenAndServe(":9091", mux); err != nil {
		log.Fatalf("fraud server failed: %v", err)
	}

}

func listFlagsHandler(w http.ResponseWriter, r *http.Request) {
	mu.Lock()
	defer mu.Unlock()

	log.Printf("Listing %d flag(s)", len(riskFlags))
	w.Header().Set("Content-Type", "application/json")
	if err := json.NewEncoder(w).Encode(riskFlags); err != nil {
		log.Printf("Error: failed to encode risk flags queue and list flags: %v", err.Error())
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
}

func connectToRabbitMQ() <-chan amqp.Delivery {
	rabbitURL := getRabbitMQURL()

	fraudQueueName := os.Getenv("FRAUD_QUEUE_NAME")
	if fraudQueueName == "" {
		fraudQueueName = "fraud.transaction-events"
	}

	bankingExchangeName := os.Getenv("FANOUT_EXCHANGE_NAME")
	if bankingExchangeName == "" {
		bankingExchangeName = "banking.transaction-events"
	}

	// Create a connection to RabbitMQ
	conn, err := amqp.Dial(rabbitURL)
	if err != nil {
		log.Fatalf("failed to connect to RabbitMQ: %v", err)
	}
	log.Printf("Connected to RabbitMQ at %s", rabbitURL)

	// Open a channel
	ch, err := conn.Channel()
	if err != nil {
		log.Fatalf("failed to open a channel: %v", err)
	}

	// Declare our own queue, durable so it and anything in it survives a RabbitMQ restart - this is
	// what actually holds messages; the exchange below only ever routes them.
	q, err := ch.QueueDeclare(
		fraudQueueName, // name
		true,           // durability
		false,          // delete when unused
		false,          // exclusive
		false,          // no-wait
		nil,
	)
	if err != nil {
		log.Fatalf("failed to declare a queue: %v", err)
	}
	log.Printf("Declared queue %s", q.Name)

	// Bind our queue to the shared fanout exchange the Java app publishes to. Routing key is "" -
	// a fanout exchange ignores it and forwards every message to every bound queue regardless, which
	// is exactly what lets another service (e.g. a future fraud-scoring one) bind its own separate
	// queue to the same exchange later and get its own full copy, with no change needed here.
	err = ch.QueueBind(
		fraudQueueName,      // name
		"",                  // key
		bankingExchangeName, // exchange
		false,               // no-wait
		nil,
	)
	if err != nil {
		log.Fatalf("failed to bind to %s: %v", bankingExchangeName, err)
	}
	log.Printf("Bound queue %s to exchange %s", q.Name, bankingExchangeName)

	// auto-ack=false: a message is only considered handled once we explicitly Ack it below, after
	// it's actually stored - if this process crashes mid-handling, RabbitMQ redelivers it instead of
	// losing it. (auto-ack=true here would also be a protocol error: acking an already-auto-acked
	// delivery gets the channel force-closed by the broker.)
	messages, err := ch.Consume(
		q.Name, // queue
		"",     // consumer
		false,  // auto-ack
		false,  // exclusive
		false,  // no-local
		false,  // no-wait
		nil,    // args
	)
	if err != nil {
		log.Fatalf("failed to register a consumer: %v", err)
	}
	log.Printf("Consumer registered on queue %s", q.Name)
	return messages
}

func getRabbitMQURL() string {
	host := os.Getenv("RABBITMQ_HOST")
	if host == "" {
		host = "localhost"
	}

	port := os.Getenv("RABBITMQ_PORT")
	if port == "" {
		port = "5672"
	}

	username := os.Getenv("RABBITMQ_USERNAME")
	if username == "" {
		username = "guest"
	}

	password := os.Getenv("RABBITMQ_PASSWORD")
	if password == "" {
		password = "guest"
	}

	return fmt.Sprintf("amqp://%s:%s@%s:%s", username, password, host, port) // amqp://guest:guest@localhost:5672/
}

func setFraudParametersThreshold() {
	largeAmountThreshold = getEnvInt("LARGE_AMOUNT_THRESHOLD", 1000000)
	velocityWindowSeconds = getEnvInt("VELOCITY_WINDOW_SECONDS", 60)
	velocityMaxCount = getEnvInt("VELOCITY_MAX_COUNT", 5)
}

func getEnvInt(key string, fallback int) int {
	valstr := os.Getenv(key)
	if valstr == "" {
		log.Printf("no value is setted for %s, use the fallback: %v", key, fallback)
		return fallback
	}

	valint, err := strconv.Atoi(valstr)
	if err != nil {
		log.Printf("failed to convert %s to integer, return the fallback: %v. error: %v.", valstr, fallback, err)
		return fallback
	}

	return valint

}

func scoreTransaction(t Transaction) []string {
	var reasons []string

	if t.Amount >= int64(largeAmountThreshold) {
		reasons = append(reasons, LARGE_AMOUNT)
	}

	velocityWindow := time.Duration(velocityWindowSeconds) * time.Second

	if velocity.record(t.UserID, velocityWindow) >= velocityMaxCount {
		reasons = append(reasons, HIGH_VELOCITY)
	}

	return reasons
}
