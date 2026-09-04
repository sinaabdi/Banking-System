package main

import (
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"os"
	"sync"
	"time"

	amqp "github.com/rabbitmq/amqp091-go"
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
	// Guards notificationQueue - the HTTP handler and the RabbitMQ consumer goroutine both run
	// concurrently and both touch this slice, so every read and write needs the lock.
	mu sync.Mutex
)

func main() {

	rabbitURL := getRabbitMQURL()

	notificationQueueName := os.Getenv("NOTIFICATION_QUEUE_NAME")
	if notificationQueueName == "" {
		notificationQueueName = "notification.transaction-events"
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
	defer conn.Close()
	log.Printf("Connected to RabbitMQ at %s", rabbitURL)

	// Open a channel
	ch, err := conn.Channel()
	if err != nil {
		log.Fatalf("failed to open a channel: %v", err)
	}
	defer ch.Close()

	// Declare our own queue, durable so it and anything in it survives a RabbitMQ restart - this is
	// what actually holds messages; the exchange below only ever routes them.
	q, err := ch.QueueDeclare(
		notificationQueueName, // name
		true,                  // durability
		false,                 // delete when unused
		false,                 // exclusive
		false,                 // no-wait
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
		notificationQueueName, // name
		"",                    // key
		bankingExchangeName,   // exchange
		false,                 // no-wait
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

	// Runs concurrently with http.ListenAndServe below (which blocks forever) - this goroutine is
	// what actually drains the queue, for as long as the process lives.
	go func() {
		for msg := range messages {
			var n Notification
			log.Printf("Received a message: %s", msg.Body)

			if err := json.Unmarshal(msg.Body, &n); err != nil {
				log.Printf("failed to decode the message: %v", err)
				continue
			}
			if n.TransactionID <= 0 || n.Type == "" || n.Status == "" {
				log.Printf("Error: all notification fields are require: %+v", n)
				continue
			}

			mu.Lock()
			n.ID = len(notificationQueue) + 1
			n.ReceivedAt = time.Now()
			notificationQueue = append(notificationQueue, n)
			mu.Unlock()

			log.Printf("sending notification id=%v", n.ID)

			if err = msg.Ack(false); err != nil {
				log.Printf("failed to set Ack: %v", err)
				continue
			}
			log.Printf("Acked message for transactionId=%d", n.TransactionID)
		}
	}()

	mux := http.NewServeMux()
	mux.HandleFunc("GET /notifications", listHandler)

	log.Println("Notification server listening on port 9090...")
	if err := http.ListenAndServe(":9090", mux); err != nil {
		log.Fatalf("notification server failed: %v", err)
	}
}

func listHandler(writer http.ResponseWriter, request *http.Request) {
	mu.Lock()
	defer mu.Unlock()

	log.Printf("Listing %d notification(s)", len(notificationQueue))
	writer.Header().Set("Content-Type", "application/json")
	if err := json.NewEncoder(writer).Encode(notificationQueue); err != nil {
		log.Printf("Error: failed to encode notification queue and list notifications: %v", err.Error())
		http.Error(writer, err.Error(), http.StatusInternalServerError)
		return
	}
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
