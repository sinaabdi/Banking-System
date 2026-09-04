# stage 1: build the notification service
FROM golang:1.27-alpine AS builder

WORKDIR /app

COPY go.mod go.sum ./

RUN go mod download

COPY main.go ./

RUN GO_ENABLED=0 GOOS=linux go build -o notification .

# Stage 2: run the notification service
FROM alpine:3.24

WORKDIR /app

COPY --from=builder /app/notification ./

EXPOSE 9090

ENTRYPOINT ["./notification"]