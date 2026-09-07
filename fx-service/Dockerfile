# stage 1: build the fx service
FROM golang:1.27-alpine AS builder

WORKDIR /app

COPY go.mod go.sum ./

RUN go mod download

COPY main.go ./

RUN GO_ENABLED=0 GOOS=linux go build -o fx .

# Stage 2: run the fx service
FROM alpine:3.24

WORKDIR /app

COPY --from=builder /app/fx ./

EXPOSE 9092

ENTRYPOINT ["./fx"]