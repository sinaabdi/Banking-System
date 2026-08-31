# Banking System

A double-entry bookkeeping banking API, built with Spring Boot as a learning project - Phase 1 of a
longer-term plan toward a polyglot microservices system (Go/Rust/Python, Redis, MQ, Kubernetes,
GitOps CI/CD). This phase covers a single monolithic service: users, accounts, deposits/withdrawals/
transfers/reversals, JWT authentication, and role/ownership-based authorization.

For the design decisions behind how this is built - the ledger model, concurrency strategy, and
authentication/authorization - see [architucture.md](architucture.md).

## Tech stack

- Java 25, Spring Boot 4.1.0 (Web MVC, Data JPA, Security)
- PostgreSQL, via Flyway migrations
- JWT auth (`io.jsonwebtoken`), BCrypt password hashing
- springdoc-openapi (Swagger UI)
- JUnit 5 + Mockito for unit tests, a `@SpringBootTest` integration test against a real Postgres
  instance for the concurrency guarantees Mockito alone can't prove

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

## Configuration

`application.properties` ships with a working local JWT secret (`jwt.secret`) and a 1-hour expiry (`jwt.expiration-ms`) so the app runs out of the box. **The committed secret is for local development only** - in any shared or deployed environment, this should come from an environment variable or a secrets manager instead, never from a file checked into version control.

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

A user can only act on their own resources (their own profile, their own accounts); an `ADMIN` (granted by hand in the database for now - there's no promotion endpoint yet) can act on anyone's.

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
banking/src/main/resources/db/migration/   Flyway migrations
banking/src/test/java/...                  unit tests (Mockito) + one concurrency integration test
```
