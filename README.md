# Banking System

A double-entry bookkeeping banking API, built with Spring Boot as a learning project - Phase 1 of a
longer-term plan toward a polyglot microservices system (Go/Rust/Python, Redis, MQ, Kubernetes,
GitOps CI/CD). Phase 1 covers a single monolithic service: users, accounts, deposits/withdrawals/
transfers/reversals, JWT authentication, and role/ownership-based authorization. Phase 2 is underway -
Docker/Kubernetes deployment, RabbitMQ, three companion Go microservices: two that react to every
posted transaction independently, [notification service](#notification-service) and
[fraud-scoring service](#fraud-scoring-service), and one `core` calls synchronously for cross-currency
transfers, the [FX-rate service](#fx-rate-service) - and a full [CI/CD pipeline](#cicd-pipeline) that
automatically tests, builds, and deploys every change: GitHub Actions builds and pushes images to GHCR,
and [ArgoCD](#gitops-deployment-argocd) syncs the cluster to match, with no manual `kubectl` involved.

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
- Go 1.25 - three companion services: notification, fraud-scoring, and FX-rate
- RabbitMQ - async transaction-posted events, fanned out to the notification and fraud-scoring
  services independently (`spring-boot-starter-amqp` / `amqp091-go`)
- Synchronous HTTP (`RestClient`) from `core` to the FX-rate service, for cross-currency transfers -
  the one place a Go service isn't a RabbitMQ consumer, since a rate lookup is a data dependency
  `core` needs an answer to before it can proceed, not a side-effect notification
- GitHub Actions - tests every push, builds and pushes images to GHCR on `main`/a `deploy` tag
- GHCR (GitHub Container Registry) - all 4 images, tagged by git SHA
- ArgoCD - in-cluster GitOps controller, auto-syncs the cluster to match `k8s/` on every deploy

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
Postgres needs to be running for this too - one test (`TransactionServiceConcurrencyTest`) fires real concurrent requests against a real database to prove the pessimistic-locking strategy actually prevents an account from being overdrawn; that's not something a mocked repository can verify. RabbitMQ needs to be running as well, for the tests that load the full Spring context.

**4. Run fx-service** (only needed for cross-currency transfers - same-currency transfers work without it):
```bash
cd fx-service
go run main.go
```
Listens on `:9092`. See [FX-Rate Service](#fx-rate-service) below.

## Running it with Docker Compose

Alternatively, run the whole stack - app, notification service, fraud-scoring service, FX-rate service,
Postgres, and RabbitMQ - in containers, no local JDK/Gradle/Go needed:
```bash
docker compose up --build
```
Each service builds from its own `Dockerfile` (`banking/Dockerfile`, `notification-service/Dockerfile`,
`fraud-service/Dockerfile`, `fx-service/Dockerfile` - a multi-stage build per service: compile with the
full JDK/Go toolchain, run with just a JRE/a bare Alpine image). Compose starts Postgres and RabbitMQ
first, waits for both to actually be ready to accept connections (not just for their containers to
start), then starts `core`, `notification`, `fraud`, and `fx` - the three Go services start
independently of each other and of `core`; `notification`/`fraud` each only wait on RabbitMQ, and `fx`
waits on nothing at all (see [architucture.md](architucture.md#containerization) for why that's the
only real startup dependency here). The API is up at `http://localhost:8080`, same as the local
workflow; RabbitMQ's management UI is at `http://localhost:15672` (`guest`/`guest`).

The containerized services get their settings from the `.env` file at the repo root
(`DB_URL=postgres`, `RABBITMQ_HOST=rabbitmq`, etc. - Compose's internal DNS resolves these to the
right containers) instead of each service's own `localhost` defaults, which stay in place for
`./gradlew bootRun`/`go run main.go`.

## Running it on Kubernetes

The manifests in [k8s/](k8s/) deploy the whole stack (app + all three Go services + Postgres +
RabbitMQ) onto any cluster - developed and tested against a local
[minikube](https://minikube.sigs.k8s.io/) cluster (3 nodes, `docker` driver). **The real deployment
path is now automated** - see [CI/CD Pipeline](#cicd-pipeline) below: push to `main` (or a `deploy`
tag) and GitHub Actions + ArgoCD get the new code running with no `kubectl` involved at all. What
follows here is the manual path - still fully working, useful for a first-time cluster bootstrap or
quick local testing outside the pipeline.

**1. Build the 4 app images and load them into the cluster** (minikube doesn't see your local Docker
images by default - `minikube image load` copies them in):
```bash
docker build -t banking-core:local ./banking
docker build -t banking-notification:local ./notification-service
docker build -t banking-fraud:local ./fraud-service
docker build -t fx-service:local ./fx-service
minikube image load banking-core:local
minikube image load banking-notification:local
minikube image load banking-fraud:local
minikube image load fx-service:local
```
Rebuilding after a code change isn't enough on its own - see the note on `imagePullPolicy: Never`
below the verification steps. (This only applies to these locally-tagged `:local` images built by
hand; images the pipeline builds and pushes to GHCR are tagged by git SHA and pulled normally - see
[CI/CD Pipeline](#cicd-pipeline).)

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

**Gotchas worth knowing about, encountered and fixed for real while building this:**
- `imagePullPolicy: Never` means a stale image is never automatically replaced. Rebuilding
  `banking-core:local` and re-running `minikube image load` isn't always enough by itself - if pods
  are still running on the old image, the node may not actually swap it in. Safe sequence:
  `kubectl scale deployment/<name> -n banking --replicas=0`, remove the old image on each node
  (`minikube ssh -n <node> -- docker rmi <image>:local`), reload, then scale back up. (This whole
  dance is exactly what the CI/CD pipeline below exists to eliminate for the real deploy path.)
- After editing `k8s/config.yaml`, `kubectl apply -f k8s/config.yaml` has to actually be re-run - a
  pod referencing a `ConfigMap` key that doesn't exist yet in the cluster fails with
  `CreateContainerConfigError`, even though the key is right there in the file on disk. Editing the
  manifest and applying it are two separate steps; only the second one changes what the cluster sees.
- `core` used to be able to drop the very first event ever published on a completely fresh RabbitMQ
  (the exchange was only ever declared lazily, on `core`'s first publish) - this is fixed now, not
  just documented as a one-time risk: `RabbitMQConfig` declares the exchange eagerly at startup, before
  the app can accept any request at all. See
  [architucture.md](architucture.md#notification-service) for how.

## CI/CD pipeline

[.github/workflows/ci.yaml](.github/workflows/ci.yaml) tests every push (any branch), and on `main`
or a `deploy*` tag, builds and pushes all 4 images to GHCR, then commits the new tags into `k8s/`
itself - which **ArgoCD**, running inside the cluster, picks up and syncs automatically. Nobody runs
`kubectl apply` for a real deploy anymore.

The whole chain, in order: push -> `java-test`/`go-test` (real Postgres/RabbitMQ containers for Java,
a build+test matrix across the three Go services) -> `build-and-push` (GHCR, tagged by git SHA) ->
`deployment` (bumps `k8s/*-deployment.yaml`'s image tags, commits with `[skip ci]` so it doesn't
retrigger itself) -> ArgoCD notices the new commit on its own and rolls out new pods. See
[architucture.md](architucture.md#cicd-pipeline-github-actions) for the full design, including several
real bugs caught and fixed while building it (a GitHub Actions networking gotcha, an `options:`
quoting bug, a matrix/hardcoded-step mixup, a wrong `Dockerfile` path resolution, a missing registry
prefix on an image tag, and a two-layer permissions issue).

## GitOps deployment (ArgoCD)

[argocd/argocd-application.yaml](argocd/argocd-application.yaml) defines the `Application` ArgoCD
syncs from - this repo's `k8s/` directory on `main`, into the cluster's `banking` namespace,
auto-syncing with self-heal (a manual out-of-band change gets reverted back to match git) and pruning
(deleting a manifest deletes the resource too). It's a Custom Resource living in its own `argocd`
namespace (installed separately, `kubectl create namespace argocd` + ArgoCD's official install
manifest) - not inside `k8s/` itself, so it stays under direct manual control rather than being synced
by the very automation it defines. See
[architucture.md](architucture.md#gitops-deployment-argocd) for why an in-cluster, poll-outward
controller is actually a better fit here than a push-based approach, given this cluster is local and
only sometimes running.

Access the UI (not exposed externally by default):
```bash
kubectl port-forward svc/argocd-server -n argocd 8080:443
kubectl get secret argocd-initial-admin-secret -n argocd -o jsonpath='{.data.password}' | base64 -d
```
Then open `https://localhost:8080` (self-signed cert, browser warning expected) and log in as `admin`
with that password.

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
it's a fanout exchange specifically (built to support more than one independent consumer with zero
changes to `core` - see the [fraud-scoring service](#fraud-scoring-service) below, which is exactly
that second consumer).

## Fraud-scoring service

[fraud-service/](fraud-service/) is the second independent consumer bound to the same
`banking.transaction-events` fanout exchange - its own durable queue (`fraud.transaction-events`), so
it gets a full copy of every event regardless of whether `notification-service` is even running. Run
it on its own (RabbitMQ must already be up - `docker compose up -d rabbitmq`):
```bash
cd fraud-service
go run main.go
```
It applies two scoring rules to every event it receives:
- **Large-amount threshold** (`LARGE_AMOUNT_THRESHOLD`, minor units) - flags a single transaction
  outright if its amount meets or exceeds the threshold.
- **Velocity** (`VELOCITY_WINDOW_SECONDS`/`VELOCITY_MAX_COUNT`) - a sliding time window, keyed by
  `user_id` rather than account, so it catches a user spreading rapid activity across *several*
  accounts they own, not just one. Both amount and velocity can flag the same transaction at once.

`GET /flags` (`:9091`) lists every flagged transaction with its reason(s) - the easiest way to verify
the scoring end to end. See [architucture.md](architucture.md#fraud-scoring-service) for the full
design, including how `accountId`/`userId`/`counterpartyAccountId`/`counterpartyUserId` get resolved
onto the shared event from the double-entry ledger.

## FX-rate service

[fx-service/](fx-service/) is the third Go service, and the only one `core` talks to directly over
HTTP rather than through RabbitMQ - it answers a rate lookup `core` needs an actual answer to before it
can post a cross-currency transfer, not a side-effect notification the rest can fire-and-forget. Run
it on its own (no RabbitMQ, no Postgres, no other service needed at all):
```bash
cd fx-service
go run main.go
```
It seeds a small base table of simulated USD-per-unit rates (EUR/GBP/JPY/CAD/AUD/CHF) and nudges each
one with a small bounded random drift every few seconds - simulated rather than pulled from a real
external FX API, so the whole system stays self-contained and works fully offline, the same choice
already made for `notification-service`/`fraud-service`.

- `GET /rate?from=X&to=Y` (`:9092`) - the rate `core` actually calls, triangulated through USD
  (`rate(A->B) = rate(B)/rate(A)`) rather than a maintained N² pairwise table.
- `GET /rates` - the full live table, the easiest way to watch the drift happen across repeated calls.

With `fx-service` up, `POST /api/transactions/transfer` now accepts a destination account in a
*different* currency from the source - the request shape is unchanged (`amount`/`currency` are the
source side, exactly what leaves the source account), and the response's new `sourceAmount`/
`sourceCurrency`/`destinationAmount`/`destinationCurrency` fields show exactly what was sent and what
the destination account actually received. A same-currency transfer works exactly as before, with
`fx-service` never even contacted. See [architucture.md](architucture.md#fx-rate-service) for the full
design, including why the rate is fetched before the pessimistic account locks rather than after, and
two real bugs caught and fixed while building it.

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

fraud-service/
  main.go     Go fraud-scoring service - RabbitMQ consumer, velocity tracker, GET /flags
  go.mod/go.sum
  Dockerfile     multi-stage build for this service

fx-service/
  main.go        Go FX-rate service - simulated rate table + drift, GET /rate, GET /rates
  main_test.go   first Go tests in this project (triangulation math, drift bounds)
  go.mod/go.sum
  Dockerfile     multi-stage build for this service

.github/workflows/ci.yaml   GitHub Actions - test on every push, build/push to GHCR and deploy on
                             main/a deploy tag (see CI/CD pipeline above)

argocd/argocd-application.yaml   the ArgoCD Application - deliberately outside k8s/, see GitOps
                                   deployment above for why

k8s/   Kubernetes manifests for every service above, including rabbitmq-*.yaml and fraud-*.yaml
```
