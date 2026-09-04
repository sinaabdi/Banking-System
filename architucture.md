## Database

`USER`
```
id                PK
first_name
last_name
username          unique
password_hash     -- BCrypt hash, via Spring Security's PasswordEncoder; never stored/logged raw
email             unique
status            (ACTIVE/DISABLED/DELETED)
role              (USER/ADMIN) -- see Authentication & Authorization below; promoted/demoted via
                  -- UserService.promoteToAdmin/demoteToUser (the seeded "system" user starts as ADMIN)
created_at
updated_at
```

`ACCOUNT`
```
id                PK
user_id           FK -> USER, not null -- even SYSTEM accounts have an owning user (a seeded "bank operations" user)
account_number    unique -- a DB sequence value with a Luhn check digit appended
account_type      (CHECKING/SAVINGS/SYSTEM)
currency
status            (ACTIVE/FROZEN/CLOSED)
version           -- optimistic lock; protects direct mutations to this row (freeze/close/activate) only.
                  -- does NOT protect the withdraw/transfer balance check - see Concurrency below.
created_at
updated_at
```

`TRANSACTION`
```
id                        PK
type                      (TRANSFER/DEPOSIT/WITHDRAWAL/FEE/REVERSAL) -- FEE is defined but not yet wired to any operation
status                    (PENDING/POSTED/FAILED/REVERSED)           -- FAILED is defined but no code path sets it yet
idempotency_key           unique, not null
reversed_transaction_id   FK -> TRANSACTION, nullable, self-referencing -- set on the reversal, pointing back at what it reverses
created_at
updated_at
```

`LEDGER_ENTRY`
```
id                 PK
transaction_id     FK -> TRANSACTION
account_id         FK -> ACCOUNT
direction          (CREDIT/DEBIT)
amount             -- Long, minor units (e.g. cents) to avoid floating-point rounding; always positive, direction carries the sign
currency
created_at         -- no updated_at, no setters: append-only. A mistake is corrected by posting a
                   -- new reversal, never by editing an existing entry.
```

Balance is never stored - it's always derived as `sum(CREDIT) - sum(DEBIT)` over an account's ledger entries, so it can never drift out of sync with the transaction history that produced it.

## Services

`USER` (UserService)
```
createUser              -- hashes the password before storing; rejects duplicate username/email
updateUser               -- updates first name, last name, email (not username)
changePassword            -- verifies current password via PasswordEncoder.matches, hashes the new one
disableUser
enableUser
promoteToAdmin           -- grants ADMIN
demoteToUser             -- revokes ADMIN; rejects a caller trying to demote their own account
getUserById
getUserByEmail
getUserByUsername
getAllUsers
```

`ACCOUNT` (AccountService)
```
createAccount              -- generates the account number from a DB sequence + Luhn check digit
freezeAccount
closeAccount
activeAccount              -- reactivates a frozen/closed account
getBalance                 -- computed from LEDGER_ENTRY, not stored
getAccountByAccountId
getAccountByAccountNumber
getAccountsForUser
```

`TRANSACTION` (TransactionService)

No generic "create transaction" - each operation is its own method with its own validation:
```
deposit    -- credits the account, debits the SYSTEM account for that currency (money entering
           -- the ledger from outside the bank)
withdraw   -- debits the account, credits the SYSTEM account; rejects if the amount exceeds the
           -- current balance; locks the account row for the duration (see Concurrency)
transfer   -- debits the source, credits the destination, no SYSTEM account involved; both
           -- accounts are locked, always in a fixed order, to avoid deadlock; only the source's
           -- balance can ever reject a transfer
reverse    -- posts a new REVERSAL transaction whose entries mirror the original's with direction
           -- flipped; only a POSTED, not-already-reversed, non-REVERSAL-type transaction is eligible
getTransactionById
getTransactionByIdempotencyKey
```

`deposit`/`withdraw`/`transfer`/`reverse` all take a client-supplied `idempotency_key` and check for an existing transaction with that key first - a retried request returns the original result instead of creating a duplicate.

`LEDGER_ENTRY`

No dedicated service - accessed directly via `LedgerEntryRepository` from `AccountService`/`TransactionService`:
```
computeBalanceForAccount  -- the balance-derivation query
findByTransactionId       -- used by reverse to find the entries to mirror
```

`AUTH` (AuthenticationService)
```
login    -- authenticates username/password via AuthenticationManager (which delegates to
         -- AppUserDetailsService + PasswordEncoder under the hood), then issues a JWT
```

## Authentication & Authorization

Stateless JWT auth - no server-side session store. A client logs in once, gets a signed token back,
and sends it as `Authorization: Bearer <token>` on every later request.

**How a request gets authenticated:**
1. `POST /api/auth/login` (`AuthenticationController` -> `AuthenticationService`) verifies the
   username/password via Spring Security's `AuthenticationManager`, which under the hood calls
   `AppUserDetailsService` (loads the `USER` row, throws `UsernameNotFoundException` if missing)
   and checks the password against the stored BCrypt hash. A `DISABLED` user is rejected here too -
   see `AppUserPrincipal.isEnabled()`.
2. On success, `JwtService` signs a JWT (HMAC, key from `jwt.secret`) containing the username and
   an expiry (`jwt.expiration-ms`). The token can't be revoked early - a leaked token is only as
   dangerous as however long is left until it expires, which is why the expiry is kept short.
3. Every subsequent request passes through `JwtAuthenticationFilter` (runs once per request, before
   the rest of the chain). It validates the token's signature and expiry, loads the corresponding
   `AppUserPrincipal` again via `AppUserDetailsService`, and populates `SecurityContext` - or, if the
   token is missing/invalid, just leaves the request unauthenticated and lets `SecurityConfig`'s
   `authorizeHttpRequests` rules decide whether that's allowed for the endpoint being hit.

**Roles**: `USER`/`ADMIN` on `USER.role`, exposed to Spring Security as a `ROLE_USER`/`ROLE_ADMIN`
authority (`AppUserPrincipal.getAuthorities()`). Bank-operations actions - freezing/closing/
activating an account, reversing a transaction, disabling/enabling a user, listing all users - are
`@PreAuthorize("hasRole('ADMIN')")`-gated; an `ADMIN` can also act on any other user's resources.
Promotion itself (`UserService.promoteToAdmin`/`demoteToUser`) is admin-only too, with one extra
guard: an admin can't demote their own account, so there's always at least one ADMIN left to grant
roles - self-demotion throws `IllegalArgumentException` (400) rather than locking the caller out.

**Ownership**: everywhere else, a caller can only act on their own resources. Two different
mechanisms enforce this, depending on whether the identity being checked is already present in the
request or has to be looked up:
- Where the request already names a user id directly (a path variable like `UserController`'s
  `/{id}`, or a request-body field like `AccountController.createAccount`'s `userId`),
  `@PreAuthorize("#id == authentication.principal.id or hasRole('ADMIN')")`-style SpEL checks it
  against the authenticated caller with no database lookup at all.
- Where only an account/entity id is known (`AccountService.getAccountByAccountId`,
  `TransactionService.deposit`/`withdraw`/`transfer`), the row has to be loaded first to discover
  its owning user, then checked in the service layer (`checkAccountOwnershipOrThrow`) - an id alone
  carries no ownership information until the row behind it is read. A mismatch throws Spring
  Security's `AccessDeniedException`. `transfer` only checks the *source* account - the destination
  can belong to anyone, the same way paying another person works in any real bank.

## Concurrency

Two different locks protect two different things:

- **`@Version` (optimistic)** on `ACCOUNT` protects direct mutations to that row - `freeze`/`close`/`activate`.
- **A pessimistic row lock** (`findByIdForUpdate`, `SELECT ... FOR UPDATE`) protects the `withdraw`/`transfer` balance check. `@Version` alone can't do this job: balance is derived from `LEDGER_ENTRY`, not stored on `ACCOUNT`, so nothing ever writes to the account row during a withdrawal for `@Version` to catch. The row lock is acquired *before* computing the balance and held until the transaction commits, so a second concurrent withdrawal on the same account has to wait its turn rather than reading the same stale balance and overdrawing it.
- `transfer` locks **both** accounts, always in ascending id order regardless of which is source and which is destination - otherwise two transfers between the same two accounts in opposite directions could deadlock, each holding one lock while waiting on the other's.

A concurrency test (`TransactionServiceConcurrencyTest`) fires many simultaneous withdrawals at the same account against a real Postgres instance and asserts the final balance is exactly what it should be - this is the one thing the Mockito unit tests can't prove, since there's no real locking to verify without a real database and real threads.

## Error handling

A single `GlobalExceptionHandler` maps exceptions to HTTP responses: `NoSuchElementException` -> 404, `IllegalArgumentException` -> 400, `AuthenticationException` -> 401 (wrong credentials, unknown username, or a disabled account), `AccessDeniedException` -> 403 (a valid, authenticated caller who isn't the owner or an admin - see Authentication & Authorization above), anything else -> 500 (logged server-side with full detail; the caller only sees a generic message).

## API docs

`springdoc-openapi` is wired in - Swagger UI at `/swagger-ui/index.html`, raw OpenAPI spec at `/v3/api-docs`.

## Containerization

The `Dockerfile_core` (repo root) is a two-stage build:
1. **`builder`** (`eclipse-temurin:25-jdk-*`) - copies the Gradle wrapper and `build.gradle` first and
   resolves dependencies before copying `src/`, so editing source code doesn't invalidate the
   dependency-download layer on rebuild. Then runs `./gradlew bootJar`.
2. **runtime** (`eclipse-temurin:25-jre-*`) - copies only the built jar out of `builder` via
   `COPY --from=builder`. No JDK, no Gradle, no source ever reaches this image - just a JRE and one
   jar.

`docker-compose.yml` runs this alongside Postgres, both on a `backend` bridge network so Compose's
internal DNS resolves the service name `postgres` to the database container - unlike the host
workflow, `localhost` inside `core`'s container means the container itself, not the database.

This is why `application.properties`'s datasource settings are `${DB_URL:localhost}`-style
placeholders rather than the previously-hardcoded `localhost:5432`: the defaults keep
`./gradlew bootRun` working unchanged on the host, while `core`'s `env_file: .env` overrides
`DB_URL`/`DB_PORT`/`DB_USERNAME`/`DB_PASSWORD`/`DB_NAME` to point at the `postgres` service instead.

`postgres` has a `pg_isready` healthcheck, and `core` declares `depends_on: postgres: condition:
service_healthy` rather than a bare `depends_on` - a container starting isn't the same moment as
Postgres actually accepting connections (especially on a first run, before `initdb` finishes), and
Spring Boot doesn't retry a failed initial datasource connection.

## Kubernetes Deployment

`k8s/` holds the manifests for a `banking` namespace containing:

- **`banking-config`/`banking-secret`** - one shared `ConfigMap`/`Secret` for both pods below, since
  Postgres and the app need the same values from opposite sides of the same connection. Each side
  maps a key to its own env var name via `valueFrom.configMapKeyRef`/`secretKeyRef` - e.g. the
  Postgres pod's `POSTGRES_DB` env var reads the `ConfigMap`'s `DB_NAME` key, the same key the app
  reads as `DB_NAME` directly. `secret.yaml` is gitignored (base64 is encoding, not encryption -
  committing it would be no different from committing `.env`); only a shared `config.yaml` is
  tracked.
- **`postgres`** - a single-replica `Deployment` (a real DB doesn't need `StatefulSet`'s
  ordered-scaling guarantees at this scale) backed by a `PersistentVolumeClaim` (minikube's default
  storage class auto-provisions it, tied to whichever node the provisioner runs on - fine for a
  learning cluster, not a production storage story), fronted by a `Service` **named `postgres`**.
  That exact name is what makes `DB_URL=postgres` (the same value already used for Docker Compose)
  resolve correctly via Kubernetes' internal DNS, with zero app-side changes from the Docker stage.
- **`core`** - the app `Deployment`, 2 replicas (scheduled across the two worker nodes, demonstrating
  the cluster actually load-balancing rather than just running single-node), exposed via a `NodePort`
  `Service`. `imagePullPolicy: Never` on the container, since the image is loaded locally
  (`minikube image load`) rather than pulled from a registry - the default policy for an untagged/
  `:latest` image is `Always`, which would otherwise send kubelet looking for it on Docker Hub and
  fail permanently.

**Health probes**: added `spring-boot-starter-actuator` with
`management.endpoint.health.probes.enabled=true`, which exposes two Kubernetes-specific endpoints -
`/actuator/health/liveness` ("is this instance fundamentally broken, kill and restart it") and
`/actuator/health/readiness` ("can this instance currently serve traffic", reflecting real dependency
state like Postgres reachability, not just "the JVM didn't crash"). Both are permitted in
`SecurityConfig` alongside `/api/auth/**`, since the kubelet calling them has no JWT to send.

**Image distribution to a multi-node cluster**: `eval $(minikube docker-env)` only points at one
node's Docker daemon, insufficient for a multi-node cluster - `minikube image load` is the tool that
actually loads a locally-built image onto every node.

**Host access on the `docker` driver**: unlike a Linux-native setup, `NodePort`/node-IP access isn't
directly routable from the host on Windows/WSL with the `docker` driver - `minikube service <name>
--url` opens an active tunnel process (must stay running) rather than just printing a static URL.
This is a property of the driver/host combination, not something fixed by the cluster configuration.

## Notification Service

The first genuinely polyglot piece: [notification-service/](notification-service/) is a standalone Go
service, called by the Java app whenever a transaction posts. This is a synchronous REST call today,
not a message queue - a believable stepping stone toward introducing one later without a rewrite.

**Event flow (Java side)**: `TransactionService`'s `deposit`/`withdraw`/`transfer`/`reverse` each
publish a `TransactionPostedEvent` (via `ApplicationEventPublisher`) immediately after
`transaction.postedTransaction()` - for `reverse`, this is the newly-posted *reversal* transaction,
not the original being marked `REVERSED`. `TransactionEventPublisher` (`events/` package)
consumes it via `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)`, not a plain
`@EventListener`:

- **Why `AFTER_COMMIT` specifically**: publishing inside the same `@Transactional` method and reacting
  to it immediately would mean the HTTP call happens *while* the DB transaction (and, for
  `withdraw`/`transfer`, its row locks) is still open - slow or unavailable, it holds up the lock; and
  if the transaction then rolled back for an unrelated reason, a notification would already have gone
  out for data that never actually committed. `AFTER_COMMIT` guarantees the listener only runs once the
  change is durably saved.
- **Why the call is fire-and-forget**: a notification is a side effect, not part of the banking
  domain's correctness - `RestClient` is built once (constructor injection, not rebuilt per call) with
  a short (~2s) connect/read timeout, and the whole call is wrapped in try/catch that logs and swallows
  any failure rather than rethrowing. A deposit succeeds or fails on its own merits regardless of
  whether this service is reachable - verified directly by stopping the Go service and confirming a
  deposit still returns normally.
- **Field-name bridging**: the outgoing payload is a separate `NotificationRequest` record, not the
  event itself reused - Go's struct expects `snake_case` keys (`transaction_id`), so the mismatched
  field is annotated `@JsonProperty("transaction_id")` on that DTO rather than on the event (the enum
  fields `type`/`status` need no annotation - Jackson serializes an enum as its `name()` by default).

**The Go service itself**: standard library only (`net/http`'s Go 1.22+ pattern-based `ServeMux`, e.g.
`mux.HandleFunc("POST /notifications", ...)` - no framework needed at this size), storing notifications
in a package-level slice guarded by a `sync.Mutex` (Go's HTTP server runs each request on its own
goroutine, so the shared slice needs explicit protection on every read *and* write). `POST
/notifications` assigns an id + timestamp, stores, logs to stdout as the stand-in for actually sending
something, returns 201; `GET /notifications` lists everything received - the way to verify the flow
end to end without a database.

**Config**: `notification.service.url` (`application.properties`, defaults to
`http://localhost:9090`) - not yet wired into `docker-compose.yml`/`k8s/`; both currently only cover
the Java app + Postgres.
