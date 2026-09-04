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

Each service has its own `Dockerfile`, colocated in its own directory (`banking/Dockerfile`,
`notification-service/Dockerfile`) with its own `.dockerignore`, not one shared root-level Dockerfile
per service distinguished by suffix. This scales better as more services get added, and it means each
service's `docker-compose.yml` `build.context` points at that service's own directory, so its
Dockerfile's `COPY` paths are relative to itself rather than the repo root.

`banking/Dockerfile` is a two-stage build:
1. **`builder`** (`eclipse-temurin:25-jdk-*`) - copies the Gradle wrapper and `build.gradle` first and
   resolves dependencies before copying `src/`, so editing source code doesn't invalidate the
   dependency-download layer on rebuild. Then runs `./gradlew bootJar`.
2. **runtime** (`eclipse-temurin:25-jre-*`) - copies only the built jar out of `builder` via
   `COPY --from=builder`. No JDK, no Gradle, no source ever reaches this image - just a JRE and one
   jar.

`notification-service/Dockerfile` follows the same shape with Go's toolchain instead: a
`golang:*-alpine` builder stage runs `go build`, and a bare `alpine` runtime stage copies out just the
compiled binary - no Go toolchain or source in the final image either.

`docker-compose.yml` runs `core`, `notification`, `postgres`, and `rabbitmq` together, all on a
`backend` bridge network so Compose's internal DNS resolves each by service name - unlike the host
workflow, `localhost` inside any one container means that container itself, not any of the others.

This is why `application.properties`'s datasource/RabbitMQ settings are `${DB_URL:localhost}`-style
placeholders (and, on the Go side, `os.Getenv` with a hardcoded fallback, since Go has no built-in
placeholder syntax) rather than anything hardcoded: the defaults keep `./gradlew bootRun`/
`go run main.go` working unchanged on the host, while each container's `env_file: .env` overrides them
to point at the right service names instead.

`postgres` and `rabbitmq` each have their own healthcheck (`pg_isready`, `rabbitmq-diagnostics ping`),
and `core`/`notification` declare `depends_on: <service>: condition: service_healthy` rather than a
bare `depends_on` - a container starting isn't the same moment as the thing inside it actually being
ready to accept connections (especially on a first run), and neither Spring Boot nor the Go program
retries a failed initial connection.

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
  fail permanently. Its `env` also carries `RABBITMQ_HOST`/`RABBITMQ_PORT`/`RABBITMQ_USERNAME`/
  `FANOUT_EXCHANGE_NAME` (from `banking-config`) and `RABBITMQ_PASSWORD` (from `banking-secret`).
- **`rabbitmq`** - **`replicas: 1`, deliberately, not 2.** Unlike `core`/`notification`, RabbitMQ isn't
  stateless - running 2 replicas via a plain `Deployment` with no clustering configuration would give
  two completely independent, unconnected broker instances, each with their own separate exchanges and
  queues. The `Service` in front would load-balance each new connection randomly between them, so
  `core`'s publish and `notification`'s consume could easily land on *different* instances - a message
  would then just silently never arrive, with no error anywhere. Real RabbitMQ clustering needs a
  `StatefulSet`, stable per-pod identity, and a peer-discovery mechanism; none of that exists here, so
  a single replica is the only correct choice at this scale - same reasoning as `postgres`. Backed by
  its own `PersistentVolumeClaim` mounted at `/var/lib/rabbitmq/` - without it, a pod restart would wipe
  every durable queue and anything waiting in it, defeating the entire point of the durability work in
  [Notification Service](#notification-service) below.
- **`notification`** - 2 replicas, `env` sourced the same way as `core`'s RabbitMQ settings, plus
  `NOTIFICATION_QUEUE_NAME`. No `PersistentVolumeClaim` - it's stateless (an in-memory store), same as
  `core`.

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

**`imagePullPolicy: Never` doesn't guarantee a rebuild actually takes effect**: `minikube image load`
can silently fail to refresh an already-loaded tag while a pod is still running on it. The reliable
sequence is scale the deployment to 0 first (so nothing holds the old image), remove it on each node
(`minikube ssh -n <node> -- docker rmi <image>:local`), reload, then scale back up.

**A brand-new RabbitMQ can drop its very first message** - encountered directly while first deploying
this: the exchange is only created lazily on `core`'s first publish, and `notification`'s queue is only
bound to it during `notification`'s own startup. On a truly fresh PVC, if the first publish happens
before the first successful bind, the message has nowhere to go - a fanout exchange doesn't buffer for
queues that don't exist yet at publish time. This can't recur once both exist (they're durable), so
it's a one-time concern specific to a brand-new environment, not an ongoing reliability gap.

## Notification Service

The first genuinely polyglot piece: [notification-service/](notification-service/) is a standalone Go
service that reacts to every posted transaction, over **RabbitMQ**. This started as a direct
synchronous HTTP call and was deliberately migrated to a message broker specifically to support more
than one independent consumer of the same event (a planned fraud/risk-scoring service is next) without
`core` ever needing to know how many consumers exist.

**Event flow (Java side)**: `TransactionService`'s `deposit`/`withdraw`/`transfer`/`reverse` each
publish a `TransactionPostedEvent` (via `ApplicationEventPublisher`) immediately after
`transaction.postedTransaction()` - for `reverse`, this is the newly-posted *reversal* transaction,
not the original being marked `REVERSED`. `TransactionEventPublisher` (`events/` package, renamed from
`TransactionNotificationListener` once its job stopped being "call notification-service specifically"
and became "publish a domain event for whoever's listening") consumes it via
`@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)`, not a plain `@EventListener`:

- **Why `AFTER_COMMIT` specifically**: publishing inside the same `@Transactional` method and reacting
  to it immediately would mean the broker call happens *while* the DB transaction (and, for
  `withdraw`/`transfer`, its row locks) is still open; and if the transaction then rolled back for an
  unrelated reason, an event would already have gone out for data that never actually committed.
  `AFTER_COMMIT` guarantees the listener only runs once the change is durably saved.
- **Fanout exchange, not direct/topic**: `RabbitMQConfig` declares a durable `FanoutExchange` named
  `banking.transaction-events`. A fanout exchange forwards every published message to *every* queue
  bound to it, ignoring routing keys entirely - exactly the shape needed for "N independent consumers,
  each wants their own full copy of every event," as opposed to routing different message *categories*
  to different places (what direct/topic exchanges are for). If `notification` and a future
  fraud-scoring service both consumed from the *same* queue instead, RabbitMQ would split messages
  between them (competing consumers - each message goes to exactly one consumer, right for scaling one
  service horizontally, wrong for two different services that each need every message).
- **Why the publish is still fire-and-forget**: an event is a side effect, not part of the banking
  domain's correctness - `RabbitTemplate.convertAndSend(...)` is wrapped in try/catch that logs and
  swallows any failure rather than rethrowing, same guarantee the old direct HTTP call had, just
  protecting against a broker being unreachable instead of an HTTP endpoint. A deposit succeeds or
  fails on its own merits regardless of RabbitMQ's availability.
- **JSON over AMQP isn't automatic**: unlike `RestClient`, Spring AMQP's default `RabbitTemplate` only
  serializes `String`/`byte[]`/`Serializable` payloads - a record like `NotificationRequest` isn't any
  of those and gets rejected at send time with a clear error, not silently mishandled. Fixed by adding
  a `Jackson2JsonMessageConverter`/`JacksonJsonMessageConverter` (the latter is this project's Spring
  AMQP version's actual class name - the "2" suffix was dropped once Jackson 1.x support was removed)
  bean; Spring Boot auto-wires it into the `RabbitTemplate` it builds once exactly one
  `MessageConverter` bean exists.
- **Field-name bridging**: the outgoing payload is a separate `NotificationRequest` record, not the
  event itself reused - Go's struct expects `snake_case` keys (`transaction_id`), so the mismatched
  field is annotated `@JsonProperty("transaction_id")` on that DTO rather than on the event (the enum
  fields `type`/`status` need no annotation - Jackson serializes an enum as its `name()` by default).

**The Go service itself**: its first real third-party dependency (`github.com/rabbitmq/amqp091-go`),
replacing the stdlib-only approach used until this stage. On startup it connects, declares its own
durable queue (`notification.transaction-events`), and binds it to the shared fanout exchange - then
consumes in a dedicated goroutine, running concurrently with `http.ListenAndServe` (which blocks
forever, so the consumer has to be a separate goroutine to run at all). Notifications are stored in the
same package-level slice guarded by a `sync.Mutex` as before; `GET /notifications` (still there) lists
everything received. `POST /notifications` is gone - messages arrive via the queue now, not an HTTP
push.

- **Manual ack, not auto-ack**: `ch.Consume(..., autoAck=false, ...)`, with `msg.Ack(false)` called only
  after a message is successfully stored. With auto-ack, RabbitMQ considers a message resolved the
  instant it's delivered - if the consumer then crashed while handling it, the message would just be
  gone. Manual ack means an unacknowledged message (consumer crashed, or never called `Ack`) goes back
  to the queue for redelivery instead. (A real, encountered bug from getting this backwards:
  `autoAck=true` *combined with* still calling `msg.Ack()` afterward is actually a protocol violation -
  acking an already-auto-acked delivery gets the whole channel force-closed by the broker, silently
  killing the consumer with no visible error after exactly one message.)
- **Durability**: the exchange, the queue, and each published message are all separately marked
  durable/persistent - all three have to agree for anything to survive a RabbitMQ restart. This is the
  concrete improvement over the old HTTP design: previously, if `notification-service` was down, an
  event was lost forever (a logged warning, nothing else); now it waits safely in the queue until a
  consumer is available - proven directly by stopping the Go service, depositing, confirming the
  deposit still succeeds, then restarting the service and watching it drain the backlog.

**Config**: `RABBITMQ_HOST`/`RABBITMQ_PORT`/`RABBITMQ_USERNAME`/`RABBITMQ_PASSWORD` (Java:
`application.properties`; Go: `os.Getenv` with hardcoded fallbacks, since Go has no built-in
placeholder syntax) plus `FANOUT_EXCHANGE_NAME`/`NOTIFICATION_QUEUE_NAME`, all defaulting to
`localhost`/dev credentials for the host workflow and overridden via `.env`/Kubernetes
`ConfigMap`/`Secret` elsewhere - both `docker-compose.yml` and `k8s/` now fully cover this service.
