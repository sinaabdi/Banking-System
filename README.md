# Banking System

A double-entry bookkeeping banking API, built with Spring Boot as a learning project - Phase 1 of a
longer-term plan toward a polyglot microservices system (Go/Rust/Python, Redis, MQ, Kubernetes,
GitOps CI/CD). Phase 1 covers a single monolithic service: users, accounts, deposits/withdrawals/
transfers/reversals, JWT authentication, and role/ownership-based authorization. Phase 2 is underway -
Docker/Kubernetes deployment, and a first companion microservice in Go (see
[Notification service](#notification-service) below) that the main app talks to over HTTP.

For the design decisions behind how this is built - the ledger model, concurrency strategy, and
authentication/authorization - see [architucture.md](architucture.md).

## Tech stack

- Java 25, Spring Boot 4.1.0 (Web MVC, Data JPA, Security)
- PostgreSQL, via Flyway migrations
- JWT auth (`io.jsonwebtoken`), BCrypt password hashing
- springdoc-openapi (Swagger UI)
- Spring Boot Actuator (Kubernetes liveness/readiness probes)
- JUnit 5 + Mockito for unit tests, a `@SpringBootTest` integration test against a real Postgres
  instance for the concurrency guarantees Mockito alone can't prove
- Docker (multi-stage build, one per service) + Kubernetes manifests for deployment
- Go 1.25 - a companion notification service
- RabbitMQ - async transaction-posted events (`spring-boot-starter-amqp` / `amqp091-go`)

## Running it locally

**1. Start Postgres** (via the `docker-compose.yml` at the repo root):
```bash
docker compose up -d
```
This starts Postgres 18 on `localhost:5432` with database `core_banking`, user/password `postgres`/`postgres` - matching what `banking/src/main/resources/application.properties` already expects.

**2. Run the app** (Flyway applies all migrations automatically on startup):
```bash
cd banking
./gradlew bootRun
```
The API is now up at `http://localhost:8080`.

**3. Run the tests**:
```bash
./gradlew test
```
Postgres needs to be running for this too - one test (`TransactionServiceConcurrencyTest`) fires real concurrent requests against a real database to prove the pessimistic-locking strategy actually prevents an account from being overdrawn; that's not something a mocked repository can verify.

## Running it with Docker Compose

Alternatively, run the whole stack - app, notification service, Postgres, and RabbitMQ - in
containers, no local JDK/Gradle/Go needed:
```bash
docker compose up --build
```
Each service builds from its own `Dockerfile` (`banking/Dockerfile`, `notification-service/Dockerfile`
- a multi-stage build per service: compile with the full JDK/Go toolchain, run with just a JRE/a bare
Alpine image). Compose starts Postgres and RabbitMQ first, waits for both to actually be ready to
accept connections (not just for their containers to start), then starts `core` and `notification`.
The API is up at `http://localhost:8080`, same as the local workflow; RabbitMQ's management UI is at
`http://localhost:15672` (`guest`/`guest`).

The containerized services get their settings from the `.env` file at the repo root
(`DB_URL=postgres`, `RABBITMQ_HOST=rabbitmq`, etc. - Compose's internal DNS resolves these to the
right containers) instead of each service's own `localhost` defaults, which stay in place for
`./gradlew bootRun`/`go run main.go`.

## Running it on Kubernetes

The manifests in [k8s/](k8s/) deploy the same app + Postgres onto any cluster - developed and tested
against a local [minikube](https://minikube.sigs.k8s.io/) cluster (`docker` driver).

**1. Build both app images and load them into the cluster** (minikube doesn't see your local Docker
images by default - `minikube image load` copies them in):
```bash
docker build -t banking-core:local ./banking
docker build -t banking-notification:local ./notification-service
minikube image load banking-core:local
minikube image load banking-notification:local
```
Rebuilding after a code change isn't enough on its own - see the note on `imagePullPolicy: Never`
below the verification steps.

**2. Create `k8s/secret.yaml`** - it's gitignored on purpose (base64 isn't encryption; see
[architucture.md](architucture.md#kubernetes-deployment)), so it isn't in the repo. Create it
yourself with a `DB_USERNAME`, `DB_PASSWORD`, and `JWT_SECRET` key - matching the shape of
`k8s/config.yaml` alongside it.

**3. Apply everything and check it's healthy**:
```bash
kubectl apply -f k8s/
kubectl get pods,svc -n banking
```
Both `core` pods should reach `1/1 Running` - that specifically means their readiness probe
(`/actuator/health/readiness`) already succeeded, which only happens once the app has confirmed it
can reach Postgres.

**4. Reach it from outside the cluster.** On the `docker` driver, a `NodePort` isn't directly
reachable from the host - you need an active tunnel:
```bash
minikube service core -n banking --url
```
Leave that running, and use the URL it prints from a **second terminal of the same kind** (both
native Windows, or both WSL - mixing the two can silently fail to connect).

**Two gotchas worth knowing about, both encountered and fixed for real while building this:**
- `imagePullPolicy: Never` means a stale image is never automatically replaced. Rebuilding
  `banking-core:local` and re-running `minikube image load` isn't always enough by itself - if pods
  are still running on the old image, the node may not actually swap it in. Safe sequence:
  `kubectl scale deployment/<name> -n banking --replicas=0`, remove the old image on each node
  (`minikube ssh -n <node> -- docker rmi <image>:local`), reload, then scale back up.
- On a **completely fresh** RabbitMQ (a brand-new PVC, nothing ever declared), the very first event
  can be silently dropped: the exchange is only created lazily on `core`'s first publish, and
  `notification`'s queue only gets bound to it during its own startup - if that first publish happens
  before the binding has succeeded, the message has nowhere to go and a fanout exchange doesn't buffer
  for queues that don't exist yet. This can't recur once both exist (they're durable), so it's only
  ever a one-time concern on a truly fresh environment.

## Notification service

[notification-service/](notification-service/) is a small standalone Go service that reacts whenever a
transaction posts (deposit/withdraw/transfer/reversal) - via **RabbitMQ**, not a direct HTTP call.
Run it on its own (RabbitMQ must already be up - `docker compose up -d rabbitmq`):
```bash
cd notification-service
go run main.go
```
On startup it connects to RabbitMQ, declares its own durable queue, and binds it to the shared
`banking.transaction-events` fanout exchange the banking app publishes to - then consumes from it in
its own goroutine, manually acking each message only once it's actually stored. `GET /notifications`
(`:9090`) lists everything received so far - the easiest way to verify the flow end to end. (There's
no longer a `POST /notifications` - messages arrive via the queue now, not an HTTP push.)

This is deliberately **fire-and-forget and durable**: the banking app publishes an event only after
its own database transaction commits, and any publish failure (broker unreachable) is caught and
logged rather than propagated - a deposit/withdrawal/transfer always succeeds or fails on its own
merits. Unlike the direct-HTTP-call design this replaced, a message published while the notification
service happens to be *down* now waits safely in the queue instead of being lost - proven directly by
stopping the service, depositing, and watching it get picked up once the service comes back. See
[architucture.md](architucture.md#notification-service) for the full design reasoning, including why
it's a fanout exchange specifically (built to support more than one independent consumer later - a
planned fraud/risk-scoring service, for instance).

## Configuration

`application.properties` ships with a working local JWT secret (`jwt.secret`) and a 1-hour expiry (`jwt.expiration-ms`) so the app runs out of the box. **The committed secret is for local development only** - in any shared or deployed environment, this should come from an environment variable or a secrets manager instead, never from a file checked into version control. (This is exactly what the Kubernetes deployment above does - `JWT_SECRET` comes from `k8s/secret.yaml`, overriding the committed default.)

## Authentication quick start

Every endpoint except registration, login, and the API docs requires a valid JWT (see [architucture.md](architucture.md#authentication--authorization) for the full flow).

**Register a user:**
```bash
curl -X POST http://localhost:8080/api/users \
  -H "Content-Type: application/json" \
  -d '{"firstName":"Ada","lastName":"Lovelace","username":"ada","password":"changeme123","email":"ada@example.com"}'
```

**Log in to get a token:**
```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"ada","password":"changeme123"}'
# => {"token": "eyJhbGciOi..."}
```

**Use the token on a protected endpoint:**
```bash
curl http://localhost:8080/api/users/1 \
  -H "Authorization: Bearer eyJhbGciOi..."
```

A user can only act on their own resources (their own profile, their own accounts); an `ADMIN` can act on anyone's, and can grant/revoke the `ADMIN` role on other users via `POST /api/users/{id}/promote` and `/demote`.

## API docs

Interactive Swagger UI: `http://localhost:8080/swagger-ui/index.html`
Raw OpenAPI spec: `http://localhost:8080/v3/api-docs`

## Project structure

```
banking/src/main/java/com/sina/banking/
  controllers/      REST endpoints
  services/         business logic, validation, transactions
  models/           JPA entities and enums
  repositories/      Spring Data repositories
  security/         JWT issuing/validation, UserDetails adapter
  configuration/    Spring Security configuration
  DTOs/             request/response records
  errors/           centralized exception -> HTTP status mapping
  events/           transaction-posted event + the RabbitMQ publisher that reacts to it
banking/src/main/resources/db/migration/   Flyway migrations
banking/src/test/java/...                  unit tests (Mockito) + one concurrency integration test
banking/Dockerfile                         multi-stage build for this service

notification-service/
  main.go     Go notification service - RabbitMQ consumer, in-memory store, GET /notifications
  go.mod/go.sum  first real third-party Go dependency (github.com/rabbitmq/amqp091-go)
  Dockerfile     multi-stage build for this service

k8s/   Kubernetes manifests for every service above, including rabbitmq-*.yaml
```
